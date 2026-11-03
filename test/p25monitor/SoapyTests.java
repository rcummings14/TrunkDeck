package p25monitor;

import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * 1.7.0: SDRplay / HackRF / LimeSDR / USRP through SoapySDR, tested WITHOUT the real receivers: the real SoapySDR 0.8
 * library and its real HackRF / Lime / UHD modules (no hardware attached), and a mock driver module
 * (test/mocksoapy/mock_soapy.cpp, built here once per driver name) that streams a P25 test signal in real time.
 * Needs a SoapySDR 0.8 install with headers and a C++ compiler: -Dsoapy.prefix=DIR (DIR/include, DIR/lib/libSoapySDR.so.0.8,
 * DIR/bin/*g++; a conda/radioconda env works; default: $CONDA_PREFIX). Without one, everything is skipped.
 * Example env: micromamba create -p ENV -c conda-forge soapysdr soapysdr-module-hackrf soapysdr-module-lms7 soapysdr-module-uhd gxx cmake
 */
public class SoapyTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) { pass++; System.out.println("  ok   " + what); } else { fail++; System.out.println("  FAIL " + what); } }

    static Path prefix, work, mockLib, brokenLib, src, rtlLib;
    static String java, cp;

    public static void main(String[] a) throws Exception {
        prefix = Path.of(System.getProperty("soapy.prefix", System.getenv().getOrDefault("CONDA_PREFIX", "/nonexistent")));
        src = Path.of(System.getProperty("trunkdeck.src", "."));
        Path lib = prefix.resolve("lib/libSoapySDR.so.0.8");
        Path cxx = null;
        if (Files.isDirectory(prefix.resolve("bin"))) try (var s = Files.list(prefix.resolve("bin"))) { cxx = s.filter(p -> p.getFileName().toString().endsWith("g++")).findFirst().orElse(null); }
        if (!Files.exists(lib) || cxx == null || !Files.exists(prefix.resolve("include/SoapySDR/Device.hpp"))) {
            System.out.println("SoapySDR 0.8 with headers and a C++ compiler not found under " + prefix + " (-Dsoapy.prefix) - SKIPPED");
            System.out.println("RESULT: SKIPPED"); return;
        }
        java = Path.of(System.getProperty("java.home"), "bin", "java").toString(); cp = System.getProperty("java.class.path");
        work = Files.createTempDirectory("soapytests");
        // mock modules, named like the real ones, next to a link to the real library (the radioconda / PothosSDR layout)
        Path mods = work.resolve("mock/lib/SoapySDR/modules0.8"); Files.createDirectories(mods);
        for (String[] d : new String[][]{{"hackrf", "libHackRFSupport.so"}, {"sdrplay", "libsdrPlaySupport.so"}, {"lime", "libLMS7Support.so"}, {"uhd", "libuhdSupport.so"}}) {
            int rc = new ProcessBuilder(cxx.toString(), "-O2", "-std=c++17", "-shared", "-fPIC", "-DMOCK_DRIVER=\"" + d[0] + "\"", "-I" + prefix.resolve("include"), "-L" + prefix.resolve("lib"),
                    "-Wl,-rpath," + prefix.resolve("lib"), "-o", mods.resolve(d[1]).toString(), src.resolve("test/mocksoapy/mock_soapy.cpp").toString(), "-lSoapySDR").inheritIO().start().waitFor();
            check(rc == 0, "mock " + d[0] + " module builds");
        }
        mockLib = work.resolve("mock/lib/libSoapySDR.so.0.8"); Files.createSymbolicLink(mockLib, lib);
        // a module that cannot be loaded (needs a missing library), as the SDRplay module without the SDRplay API
        Path bmods = work.resolve("broken/lib/SoapySDR/modules0.8"); Files.createDirectories(bmods);
        Path stub = work.resolve("libsdrplay_api.so.3");
        new ProcessBuilder("gcc", "-shared", "-fPIC", "-o", stub.toString(), "-Wl,-soname,libsdrplay_api.so.3", src.resolve("test/mocksoapy/stub_api.c").toString()).inheritIO().start().waitFor();
        new ProcessBuilder("gcc", "-shared", "-fPIC", "-o", bmods.resolve("libsdrPlaySupport.so").toString(), src.resolve("test/mocksoapy/broken_module.c").toString(), stub.toString()).inheritIO().start().waitFor();
        Files.delete(stub);
        brokenLib = work.resolve("broken/lib/libSoapySDR.so.0.8"); Files.createSymbolicLink(brokenLib, lib);

        // the mock librtlsdr (test/mockrtl) with no dongle: the engine's "nothing found" messages, as on a PC with TrunkDeck's bundled librtlsdr
        rtlLib = work.resolve("libmockrtlsdr.so");
        if (Files.exists(Path.of("/usr/bin/gcc"))) new ProcessBuilder("/usr/bin/gcc", "-O2", "-shared", "-fPIC", "-o", rtlLib.toString(), src.resolve("test/mockrtl/mock_rtlsdr.c").toString()).inheritIO().start().waitFor();
        realLibraryNoHardware(lib);
        noLibrary();
        switchedOff();
        allFourDefaults();
        settingsApplied();
        rateFallback();
        unplugAndOverflow();
        brokenModule();
        withRtl();
        liveDecode();
        System.out.println(fail == 0 ? "RESULT: PASS (" + pass + " checks)" : "RESULT: FAIL (" + fail + " of " + (pass + fail) + ")");
        System.exit(fail == 0 ? 0 : 1);
    }

    static String run(Map<String, String> env, List<String> props, String main, String... args) throws Exception {
        Path home = Files.createTempDirectory(work, "h");
        List<String> cmd = new ArrayList<>(List.of(java, "--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true", "-Dtrunkdeck.home=" + home, "-Dtrunkdeck.profile=" + home.resolve("p")));
        cmd.addAll(props); cmd.add("-cp"); cmd.add(cp); cmd.add(main); cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("MOCK_RTL_COUNT", "0");   // no RTL dongle unless a test adds one
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        if (Boolean.getBoolean("verbose")) System.out.println(out);
        return out;
    }
    static List<String> probeProps(Path soapyLib, String... more) {
        List<String> l = new ArrayList<>(List.of("-Dtrunkdeck.librtlsdr=" + (Files.exists(rtlLib) ? rtlLib : "/nonexistent/librtlsdr.so"), "-Dtrunkdeck.libsoapysdr=" + soapyLib, "-Dtrunkdeck.sdrplayApi=/etc/hostname"));
        l.addAll(List.of(more)); return l;
    }
    static Path mockLog() throws Exception { Path p = Files.createTempFile(work, "mock", ".log"); Files.delete(p); return p; }
    static String read(Path p) throws Exception { return Files.exists(p) ? Files.readString(p) : ""; }
    static boolean has(String s, String re) { return Pattern.compile(re, Pattern.MULTILINE).matcher(s).find(); }

    static void realLibraryNoHardware(Path lib) throws Exception {
        System.out.println("Real SoapySDR " + lib + " with its real HackRF / LimeSDR / USRP modules, no receiver attached:");
        String out = run(Map.of("MOCK_RTL_COUNT", "0"), probeProps(lib, "-Dtrunkdeck.sdrplayApi=/nonexistent"), "p25monitor.SoapyEngineProbe");
        check(out.contains("START-FAILED No RTL-SDR dongle, Airspy or other receiver found"), "engine: plain 'no receiver found' message");
        check(has(out, "LOG .*SoapySDR \\(SDRplay / HackRF / LimeSDR / USRP, untested\\): SoapySDR 0\\.8\\.\\d.*0 receiver\\(s\\) found"), "engine log: SoapySDR found, 0 receivers");
        check(out.contains("SoapySDR SDRplay RSP: SDRplay API not installed - download it from sdrplay.com"), "engine log: 'SDRplay API not installed - download it from sdrplay.com'");
        check(!has(out, "SoapySDR (HackRF One|LimeSDR|Ettus USRP):"), "engine log: real HackRF / Lime / UHD modules load (no hint for them)");
        check(!out.contains("rtlsdrSupport") && !out.contains("airspySupport"), "SoapySDR's own RTL-SDR / Airspy modules are never loaded");
    }

    static void noLibrary() throws Exception {
        System.out.println("No SoapySDR installed:");
        String out = run(Map.of("MOCK_RTL_COUNT", "0"), probeProps(Path.of("/nonexistent/libSoapySDR.so.0.8")), "p25monitor.SoapyEngineProbe");
        check(out.contains("START-FAILED No RTL-SDR dongle, Airspy or other receiver found") && out.contains("SDRplay / HackRF / LimeSDR / USRP: SoapySDR is not installed. Fedora: sudo dnf install SoapySDR"),
                "engine error names SoapySDR and the install command");
        check(has(out, "LOG .*librtlsdr: .*; 0 RTL-SDR dongle\\(s\\) found") && has(out, "LOG .*libusb \\(Airspy\\)"), "RTL-SDR and Airspy lines unchanged");
    }

    static void switchedOff() throws Exception {
        System.out.println("SoapySDR receivers switched off (native.soapy=false):");
        Path ml = mockLog();
        String out = run(Map.of("MOCK_SOAPY_LOG", ml.toString()), probeProps(mockLib, "-Dset.native.soapy=false"), "p25monitor.SoapyEngineProbe");
        check(out.contains("SoapySDR receivers switched off"), "logged as switched off");
        check(read(ml).isEmpty(), "SoapySDR not touched at all (mock driver never called)");
    }

    static void allFourDefaults() throws Exception {
        System.out.println("One of each (mock driver modules), default settings:");
        Path ml = mockLog();
        String out = run(Map.of("MOCK_SOAPY_LOG", ml.toString()), probeProps(mockLib), "p25monitor.SoapyEngineProbe");
        String m = read(ml);
        check(has(out, "^STARTED"), "engine starts");
        check(has(out, "LOG .*4 receiver\\(s\\) found"), "4 receivers found");
        check(has(out, "Control tuner T1: (HackRF|LimeSDR|Ettus).*\\(untested\\) \\(10\\.0 MS/s, wideband"), "control tuner: a 10 MS/s one (widest first), wideband, tagged (untested)");
        check(has(out, "Voice tuner T4: SDRplay.*\\(untested\\) \\(wideband"), "SDRplay (5 MS/s) roams for voice, wideband");
        check(out.contains("Note: Support for this receiver is new in 1.7.0 and has not been tested with real hardware") && out.contains("github.com/rcummings14/TrunkDeck/issues"), "untested note with the GitHub Issues link in the log");
        check(m.contains("hackrf setSampleRate 1e+07") && m.contains("lime setSampleRate 1e+07") && m.contains("uhd setSampleRate 1e+07") && m.contains("sdrplay setSampleRate 5e+06"), "default rates: 10 MS/s (HackRF, Lime, USRP), 5 MS/s (SDRplay)");
        check(m.contains("hackrf setGainElement AMP=0") && m.contains("hackrf setGainElement LNA=24") && m.contains("hackrf setGainElement VGA=20"), "HackRF default gain: amp off, LNA 24, VGA 20");
        check(m.contains("sdrplay setGainMode 1") && m.contains("sdrplay setGainElement RFGR=4") && m.contains("sdrplay writeSetting biasT_ctrl=false"), "SDRplay default: IF AGC, LNA state 4, bias-T off");
        check(m.contains("lime setGain 40") && m.contains("lime setAntenna LNAW") && m.contains("lime setBandwidth 1e+07"), "LimeSDR default: 40 dB, LNAW antenna, 10 MHz bandwidth");
        check(m.contains("uhd setGain 45.6"), "USRP default gain: 60 % of its range");
        check(!m.contains("hackrf setDCOffsetMode") && m.contains("lime setDCOffsetMode 1"), "hardware DC correction on where the driver has it (not HackRF: done in software)");
        check(has(m, "setFrequency 4\\.60\\d+e\\+08"), "control tuner tuned near the control channels");
        check(has(out, "ROW T1 control .*\\(untested\\).*0 dropped, 0 driver overflows, 0 read errors"), "diagnostics row: no drops / overflows");
        check(!has(out, "samples LOST") || !has(out, "[1-9]\\d* samples LOST"), "no samples lost by any mock receiver (engine kept up with 3 x 10 + 5 MS/s)");
        for (String d : new String[]{"hackrf", "sdrplay", "lime", "uhd"}) check(m.contains(d + " closeStream") && m.contains(d + " unmake"), d + ": stream closed and device released on stop");
        check(has(out, "PROBLEM null"), "no problem reported");
    }

    static void settingsApplied() throws Exception {
        System.out.println("Settings from the Engine page:");
        Path ml = mockLog();
        String out = run(Map.of("MOCK_SOAPY_LOG", ml.toString()), probeProps(mockLib,
                "-Dset.native.tuner.soapy-hackrf-MOCKhackrf0.role=control", "-Dset.native.tuner.soapy-hackrf-MOCKhackrf0.gain=db:60", "-Dset.native.tuner.soapy-hackrf-MOCKhackrf0.center=461.0",
                "-Dset.native.tuner.soapy-hackrf-MOCKhackrf0.ppm=10",
                "-Dset.native.tuner.soapy-sdrplay-MOCKsdrplay0.role=voice", "-Dset.native.tuner.soapy-sdrplay-MOCKsdrplay0.gain=agc:2", "-Dset.native.tuner.soapy-sdrplay-MOCKsdrplay0.bias=true",
                "-Dset.native.tuner.soapy-lime-MOCKlime0.role=off", "-Dset.native.tuner.soapy-uhd-MOCKuhd0.rate=5000000", "-Dset.native.tuner.soapy-uhd-MOCKuhd0.antenna=RX2"), "p25monitor.SoapyEngineProbe");
        String m = read(ml);
        check(has(out, "Control tuner T1: HackRF"), "role control: HackRF is the control tuner");
        check(m.contains("hackrf setGainElement LNA=32") && m.contains("hackrf setGainElement VGA=28"), "HackRF 60 dB -> LNA 32 + VGA 28");
        double want = 461e6 / (1 + 10e-6);
        check(has(m, "hackrf setFrequency " + String.format(Locale.ROOT, "%.6g", want).replace("e+", "e\\+")), "fixed centre 461.0 MHz with PPM 10 applied in software (" + String.format(Locale.ROOT, "%.6g", want) + ")");
        check(m.contains("sdrplay setGainElement RFGR=2") && m.contains("sdrplay writeSetting biasT_ctrl=true"), "SDRplay LNA state 2 and bias-T on");
        check(!m.contains("lime make"), "role off: LimeSDR not opened");
        check(m.contains("uhd setSampleRate 5e+06") && m.contains("uhd setAntenna RX2"), "USRP: 5 MS/s and antenna RX2 from the settings");
    }

    static void rateFallback() throws Exception {
        System.out.println("A receiver that does not accept the default rate:");
        Path ml = mockLog();
        String out = run(Map.of("MOCK_SOAPY_LOG", ml.toString(), "MOCK_SOAPY_RATES", "5000000,2500000", "MOCK_SOAPY_COUNT", "1"), probeProps(mockLib,
                "-Dset.native.tuner.soapy-sdrplay-MOCKsdrplay0.role=off", "-Dset.native.tuner.soapy-lime-MOCKlime0.role=off", "-Dset.native.tuner.soapy-uhd-MOCKuhd0.role=off"), "p25monitor.SoapyEngineProbe");
        check(has(out, "Opened HackRF.*5\\.00 MS/s.*10\\.00 MS/s not accepted"), "HackRF falls back to 5 MS/s (logged)");
        check(has(out, "Control tuner T1: HackRF.*\\(5\\.0 MS/s, wideband"), "still wideband at 5 MS/s");
    }

    static void unplugAndOverflow() throws Exception {
        System.out.println("Driver overflows and an unplugged receiver:");
        String out = run(Map.of("MOCK_SOAPY_OVERFLOW_MS", "400", "MOCK_SOAPY_COUNT", "1"), probeProps(mockLib, "-Dsecs=3"), "p25monitor.SoapyEngineProbe");
        check(has(out, "ROW T1 .* [1-9]\\d* driver overflows") && has(out, "PROBLEM null"), "overflows counted in the diagnostics, engine carries on");
        out = run(Map.of("MOCK_SOAPY_FAIL_MS", "1200", "MOCK_SOAPY_COUNT", "1"), probeProps(mockLib, "-Dsecs=3"), "p25monitor.SoapyEngineProbe");
        check(has(out, "PROBLEM (VOICE )?TUNER ERROR"), "unplug: tuner error shown");
        check(has(out, "LOG .*error: .*stopped streaming \\(.*\\) - unplugged\\?"), "unplug: plain message in the log");
        check(has(out, "LOG .*Engine stopped"), "engine stops cleanly after the unplug");
    }

    static void brokenModule() throws Exception {
        System.out.println("A module that cannot be loaded:");
        String out = run(Map.of(), probeProps(brokenLib), "p25monitor.SoapyEngineProbe");
        check(has(out, "SoapySDR SDRplay RSP: The SDRplay module for SoapySDR \\(SoapySDRPlay3\\) is not installed \\(the module is there but could not be loaded: dlopen\\(\\) failed: .*libsdrplay_api"), "module present, API library missing: loader message shown");
        out = run(Map.of(), probeProps(brokenLib, "-Dtrunkdeck.sdrplayApi=/nonexistent"), "p25monitor.SoapyEngineProbe");
        check(out.contains("SDRplay API not installed - download it from sdrplay.com"), "no SDRplay API at all: 'SDRplay API not installed - download from sdrplay.com'");
    }

    static void withRtl() throws Exception {
        System.out.println("Mixed with an RTL-SDR (mock librtlsdr):");
        Path rlib = rtlLib;
        if (!Files.exists(rlib)) { System.out.println("  gcc or test/mockrtl missing - skipped"); return; }
        Path cu8 = work.resolve("noise.cu8"); byte[] n = new byte[4_800_000]; new Random(1).nextBytes(n); Files.write(cu8, n);
        List<String> base = new ArrayList<>(List.of("-Dtrunkdeck.librtlsdr=" + rlib, "-Dtrunkdeck.libsoapysdr=" + mockLib, "-Dtrunkdeck.sdrplayApi=/etc/hostname",
                "-Dset.native.tuner.soapy-sdrplay-MOCKsdrplay0.role=off", "-Dset.native.tuner.soapy-lime-MOCKlime0.role=off", "-Dset.native.tuner.soapy-uhd-MOCKuhd0.role=off"));
        String out = run(Map.of("MOCK_RTL_FILE", cu8.toString(), "MOCK_RTL_COUNT", "1"), base, "p25monitor.SoapyEngineProbe");
        check(has(out, "Control tuner T1: HackRF") && has(out, "Voice tuner T2: #0  Mock RTL2838"), "auto: the wideband HackRF takes the control channel, the RTL dongle roams");
        List<String> b2 = new ArrayList<>(base); b2.add("-Dset.native.tuner.snMOCK0.role=control");
        out = run(Map.of("MOCK_RTL_FILE", cu8.toString(), "MOCK_RTL_COUNT", "1"), b2, "p25monitor.SoapyEngineProbe");
        check(has(out, "Control tuner T1: #0  Mock RTL2838") && has(out, "Voice tuner T2: HackRF.*\\(untested\\) \\(wideband"), "RTL set to control: RTL control, HackRF roams for voice");
    }

    static void liveDecode() throws Exception {
        System.out.println("Live decoding through SoapySDR (real library, mock receivers streaming a P25 voice signal in real time):");
        Path f = work.resolve("v25.cs16"), fdc = work.resolve("v25dc.cs16"), f10 = work.resolve("v10dc.cs16");
        new ProcessBuilder(java, "-cp", cp, "p25monitor.MakeCs16", f.toString(), "40", "350000", "2500000").inheritIO().start().waitFor();
        new ProcessBuilder(java, "-cp", cp, "p25monitor.MakeCs16", fdc.toString(), "40", "350000", "2500000", "0.05").inheritIO().start().waitFor();
        for (String d : new String[]{"hackrf", "sdrplay", "lime", "uhd"}) {
            String out = run(Map.of("MOCK_SOAPY_FILE", (d.equals("hackrf") ? fdc : f).toString()), List.of("-Dtrunkdeck.libsoapysdr=" + mockLib, "-Dtrunkdeck.sdrplayApi=/etc/hostname", "-Ddriver=" + d, "-Dsecs=6"),
                    "p25monitor.nativeengine.MockSoapyLive");
            String line = out.lines().filter(l -> l.startsWith(d + ":")).findFirst().orElse(out);
            System.out.println("     " + line.substring(0, Math.min(200, line.length())));
            Matcher w = Pattern.compile("wideband (\\d+) IMBE frames, ([0-9.]+)% bit-exact; full-rate (\\d+) frames, ([0-9.]+)% bit-exact").matcher(line);
            boolean ok = w.find() && Integer.parseInt(w.group(1)) >= 250 && Double.parseDouble(w.group(2)) >= 99.5 && Double.parseDouble(w.group(4)) >= 99.5;
            check(ok, d + " at 2.5 MS/s: voice frames bit-exact through the filter bank and at full rate" + (d.equals("hackrf") ? " (with a DC spike, removed in software)" : ""));
            check(out.contains(" 0 samples LOST") && line.contains("dropped 0") && line.contains("overflows 0"), d + ": no samples lost");
        }
        new ProcessBuilder(java, "-cp", cp, "p25monitor.MakeCs16", f10.toString(), "16", "-1650000", "10000000", "0.05").inheritIO().start().waitFor();
        String out = run(Map.of("MOCK_SOAPY_FILE", f10.toString(), "MOCK_SOAPY_MTU", "131072"), List.of("-Dtrunkdeck.libsoapysdr=" + mockLib, "-Ddriver=hackrf", "-Drate=10000000", "-Doff=-1650000", "-Dnarrow=false", "-Dsecs=5"),
                "p25monitor.nativeengine.MockSoapyLive");
        String line = out.lines().filter(l -> l.startsWith("hackrf:")).findFirst().orElse(out);
        System.out.println("     " + line.substring(0, Math.min(200, line.length())));
        Matcher w = Pattern.compile("wideband (\\d+) IMBE frames, ([0-9.]+)% bit-exact").matcher(line);
        check(w.find() && Integer.parseInt(w.group(1)) >= 150 && Double.parseDouble(w.group(2)) >= 99.5 && out.contains(" 0 samples LOST"), "HackRF at 10 MS/s (default): voice 1.65 MHz off centre bit-exact through the filter bank, nothing lost");
        Files.deleteIfExists(f10);
        for (String[] c : new String[][]{{"uhd", "2000"}, {"lime", "4080"}}) {
            out = run(Map.of("MOCK_SOAPY_MTU", c[1]), List.of("-Dtrunkdeck.libsoapysdr=" + mockLib, "-Ddriver=" + c[0], "-Drate=20000000", "-Dnarrow=false", "-Dsecs=5"), "p25monitor.nativeengine.MockSoapyLive");
            line = out.lines().filter(l -> l.startsWith(c[0] + ":")).findFirst().orElse(out);
            check(line.contains("20.00 MS/s") && line.contains("dropped 0") && out.contains(" 0 samples LOST"), c[0] + " at 20 MS/s with " + c[1] + "-sample reads: kept up, nothing lost");
        }
    }
}
