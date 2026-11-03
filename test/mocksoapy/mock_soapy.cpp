/*
 * Test double for a SoapySDR driver module (NOT shipped). Built once per driver name (-DMOCK_DRIVER="hackrf", "sdrplay",
 * "lime", "uhd") into a file named like the real module (libHackRFSupport.so ...), so TrunkDeck's SoapySDR path - module
 * loading, enumeration, make, gains, antenna, settings, CS16 streaming, retune, overflow and unplug handling - runs
 * against the real libSoapySDR. Gain elements, ranges and antennas mimic the real modules.
 * The "receiver" produces samples in real time at the set rate from MOCK_SOAPY_FILE (CS16 interleaved, looped; default:
 * noise). Its FIFO holds MOCK_SOAPY_FIFO samples (default 4 M); what the host does not read in time is lost (counted,
 * and reported once as SOAPY_SDR_OVERFLOW, as real drivers do).
 * Env: MOCK_SOAPY_COUNT (receivers per driver), MOCK_SOAPY_LOG (file: every call), MOCK_SOAPY_RATES (comma list of
 * accepted rates), MOCK_SOAPY_FAIL_MS (stream errors after this many ms = unplugged), MOCK_SOAPY_OVERFLOW_MS
 * (an injected overflow every N ms), MOCK_SOAPY_MTU (max samples per readStream, default 16384).
 */
#include <SoapySDR/Device.hpp>
#include <SoapySDR/Registry.hpp>
#include <SoapySDR/Formats.hpp>
#include <SoapySDR/Errors.hpp>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <mutex>
#include <random>
#include <thread>
#include <vector>

#ifndef MOCK_DRIVER
#define MOCK_DRIVER "hackrf"
#endif
static const std::string DRV = MOCK_DRIVER;

static double envd(const char *n, double d) { const char *v = getenv(n); return v ? atof(v) : d; }
static void mlog(const std::string &s) {
    const char *f = getenv("MOCK_SOAPY_LOG"); if (!f) return;
    static std::mutex m; std::lock_guard<std::mutex> l(m);
    FILE *fp = fopen(f, "a"); if (fp) { fprintf(fp, "%s %s\n", DRV.c_str(), s.c_str()); fclose(fp); }
}
static std::string num(double v) { char b[64]; snprintf(b, sizeof b, "%.6g", v); return b; }

static std::vector<int16_t> &data() {
    static std::vector<int16_t> d; static bool loaded = false;
    if (!loaded) {
        loaded = true;
        const char *f = getenv("MOCK_SOAPY_FILE");
        if (f) { std::ifstream in(f, std::ios::binary | std::ios::ate); size_t n = in.tellg(); in.seekg(0); d.resize(n / 2); in.read((char *) d.data(), n & ~1); }
        if (d.empty()) { std::mt19937 r(5); std::normal_distribution<float> g(0, 300); d.resize(2 * 1048576); for (auto &x : d) x = (int16_t) g(r); }
    }
    return d;
}

class MockDevice : public SoapySDR::Device {
    std::string serial; double rate = 2e6, freq = 100e6, bw = 0; bool agc = false, dc = false, active = false;
    std::map<std::string, double> gains; std::map<std::string, std::string> settings; std::string antenna;
    std::chrono::steady_clock::time_point t0; long long pos = 0, lost = 0, delivered = 0; bool overflowPending = false;
    long long lastInjected = 0;
public:
    MockDevice(const SoapySDR::Kwargs &a) : serial(a.count("serial") ? a.at("serial") : "?") {
        mlog("make serial=" + serial);
        for (auto &g : listGains(SOAPY_SDR_RX, 0)) gains[g] = 0;
        antenna = listAntennas(SOAPY_SDR_RX, 0).front();
    }
    ~MockDevice() { mlog("unmake serial=" + serial); }
    std::string getDriverKey() const override { return DRV; }
    std::string getHardwareKey() const override { return "mock-" + DRV; }
    size_t getNumChannels(const int) const override { return 1; }
    std::vector<std::string> listAntennas(const int, const size_t) const override {
        if (DRV == "lime") return {"NONE", "LNAH", "LNAL", "LNAW"};
        if (DRV == "uhd") return {"TX/RX", "RX2"};
        if (DRV == "sdrplay") return {"RX"};
        return {"TX/RX"};
    }
    void setAntenna(const int, const size_t, const std::string &n) override { antenna = n; mlog("setAntenna " + n); }
    std::string getAntenna(const int, const size_t) const override { return antenna; }
    bool hasDCOffsetMode(const int, const size_t) const override { return DRV != "hackrf"; }
    void setDCOffsetMode(const int, const size_t, const bool a) override { dc = a; mlog(std::string("setDCOffsetMode ") + (a ? "1" : "0")); }
    std::vector<std::string> listGains(const int, const size_t) const override {
        if (DRV == "hackrf") return {"LNA", "AMP", "VGA"};
        if (DRV == "sdrplay") return {"IFGR", "RFGR"};
        if (DRV == "lime") return {"TIA", "LNA", "PGA"};
        return {"PGA0"};
    }
    bool hasGainMode(const int, const size_t) const override { return DRV == "sdrplay"; }
    void setGainMode(const int, const size_t, const bool a) override { agc = a; mlog(std::string("setGainMode ") + (a ? "1" : "0")); }
    SoapySDR::Range getGainRange(const int, const size_t, const std::string &n) const override {
        if (n == "AMP") return SoapySDR::Range(0, 14, 14); if (n == "LNA") return DRV == "lime" ? SoapySDR::Range(0, 30) : SoapySDR::Range(0, 40, 8);
        if (n == "VGA") return SoapySDR::Range(0, 62, 2); if (n == "IFGR") return SoapySDR::Range(20, 59); if (n == "RFGR") return SoapySDR::Range(0, 9);
        if (n == "TIA") return SoapySDR::Range(0, 12); if (n == "PGA") return SoapySDR::Range(-12, 19); return SoapySDR::Range(0, 76);
    }
    SoapySDR::Range getGainRange(const int d, const size_t c) const override {
        if (DRV == "uhd") return SoapySDR::Range(0, 76); if (DRV == "lime") return SoapySDR::Range(-12, 61);
        if (DRV == "hackrf") return SoapySDR::Range(0, 116); return SoapySDR::Range(0, 69);
    }
    void setGain(const int, const size_t, const double v) override { gains["TOTAL"] = v; mlog("setGain " + num(v)); }
    void setGain(const int, const size_t, const std::string &n, const double v) override { gains[n] = v; mlog("setGainElement " + n + "=" + num(v)); }
    double getGain(const int, const size_t, const std::string &n) const override { auto it = gains.find(n); return it == gains.end() ? 0 : it->second; }
    double getGain(const int d, const size_t c) const override { auto it = gains.find("TOTAL"); if (it != gains.end()) return it->second; double s = 0; for (auto &g : gains) s += g.second; return s; }
    void setFrequency(const int, const size_t, const double f, const SoapySDR::Kwargs &) override { freq = f; mlog("setFrequency " + num(f)); }
    double getFrequency(const int, const size_t) const override { return freq; }
    void setSampleRate(const int, const size_t, const double r) override {
        const char *ok = getenv("MOCK_SOAPY_RATES");
        if (ok) { bool f = false; std::string s(ok); size_t p = 0; while (p <= s.size()) { size_t q = s.find(',', p); if (q == std::string::npos) q = s.size(); if (fabs(atof(s.substr(p, q - p).c_str()) - r) < 1) f = true; p = q + 1; }
            if (!f) { mlog("setSampleRate " + num(r) + " rejected"); return; } }
        rate = r; mlog("setSampleRate " + num(r));
    }
    double getSampleRate(const int, const size_t) const override { return rate; }
    void setBandwidth(const int, const size_t, const double b) override { bw = b; mlog("setBandwidth " + num(b)); }
    void writeSetting(const std::string &k, const std::string &v) override { settings[k] = v; mlog("writeSetting " + k + "=" + v); }
    std::string readSetting(const std::string &k) const override { auto it = settings.find(k); return it == settings.end() ? "" : it->second; }

    SoapySDR::Stream *setupStream(const int, const std::string &fmt, const std::vector<size_t> &, const SoapySDR::Kwargs &) override {
        mlog("setupStream " + fmt);
        if (fmt != SOAPY_SDR_CS16) throw std::runtime_error("mock supports CS16 only");
        return (SoapySDR::Stream *) this;
    }
    void closeStream(SoapySDR::Stream *) override { mlog("closeStream"); }
    size_t getStreamMTU(SoapySDR::Stream *) const override { return (size_t) envd("MOCK_SOAPY_MTU", 16384); }
    int activateStream(SoapySDR::Stream *, const int, const long long, const size_t) override {
        mlog("activateStream rate=" + num(rate)); t0 = std::chrono::steady_clock::now(); pos = 0; lost = 0; delivered = 0; active = true; return 0;
    }
    int deactivateStream(SoapySDR::Stream *, const int, const long long) override {
        active = false;
        char b[200]; snprintf(b, sizeof b, "deactivateStream delivered=%lld lost=%lld", delivered, lost); mlog(b);
        fprintf(stderr, "mock-soapy %s: closed, %lld samples delivered, %lld samples LOST\n", DRV.c_str(), delivered, lost);
        return 0;
    }
    long long produced() const { return (long long) (std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count() * rate); }
    int readStream(SoapySDR::Stream *, void *const *buffs, const size_t n, int &flags, long long &timeNs, const long timeoutUs) override {
        flags = 0;
        double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        double failMs = envd("MOCK_SOAPY_FAIL_MS", -1);
        if (failMs >= 0 && ms > failMs) { std::this_thread::sleep_for(std::chrono::milliseconds(5)); return SOAPY_SDR_STREAM_ERROR; }
        double ovMs = envd("MOCK_SOAPY_OVERFLOW_MS", -1);
        if (ovMs > 0 && (long long) (ms / ovMs) > lastInjected) { lastInjected = (long long) (ms / ovMs); long long skip = (long long) (rate * 0.002); pos += skip; lost += skip; return SOAPY_SDR_OVERFLOW; }
        long long fifo = (long long) envd("MOCK_SOAPY_FIFO", 4194304);
        long long avail = produced() - pos;
        if (avail > fifo) { long long d = avail - fifo; lost += d; pos += d; avail = fifo; overflowPending = true; }
        if (overflowPending) { overflowPending = false; return SOAPY_SDR_OVERFLOW; }
        auto deadline = std::chrono::steady_clock::now() + std::chrono::microseconds(timeoutUs);
        while (avail <= 0) {
            if (std::chrono::steady_clock::now() > deadline) return SOAPY_SDR_TIMEOUT;
            std::this_thread::sleep_for(std::chrono::microseconds(200));
            avail = produced() - pos;
        }
        size_t k = (size_t) std::min<long long>({(long long) n, avail, (long long) getStreamMTU(nullptr)});
        auto &d = data(); size_t len = d.size() / 2; int16_t *out = (int16_t *) buffs[0];
        for (size_t j = 0; j < k; j++) { size_t s = (size_t) ((pos + (long long) j) % (long long) len); out[2 * j] = d[2 * s]; out[2 * j + 1] = d[2 * s + 1]; }
        pos += k; delivered += k; timeNs = 0;
        return (int) k;
    }
};

static SoapySDR::KwargsList findMock(const SoapySDR::Kwargs &) {
    SoapySDR::KwargsList out;
    int n = (int) envd("MOCK_SOAPY_COUNT", 1);
    for (int i = 0; i < n; i++) {
        SoapySDR::Kwargs k; k["driver"] = DRV; k["serial"] = "MOCK" + DRV + std::to_string(i);
        std::string prod = DRV == "hackrf" ? "HackRF One" : DRV == "sdrplay" ? "SDRplay Dev" + std::to_string(i) + " RSP1A" : DRV == "lime" ? "LimeSDR-USB" : "B200";
        k["label"] = prod + " #" + std::to_string(i) + " " + k["serial"];
        out.push_back(k);
    }
    mlog("find " + std::to_string(n));
    return out;
}
static SoapySDR::Device *makeMock(const SoapySDR::Kwargs &a) { return new MockDevice(a); }
static SoapySDR::Registry reg(MOCK_DRIVER, &findMock, &makeMock, SOAPY_SDR_ABI_VERSION);
