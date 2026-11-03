package p25monitor.sdr;

import java.nio.file.*;
import java.util.*;

/**
 * The receivers TrunkDeck drives through SoapySDR (1.7.0), with their sensible defaults and plain-English install hints.
 * <p><b>UNTESTED:</b> none of these was tested with real hardware (only with a simulated SoapySDR module and the real
 * SoapySDR library). Airspy R2 and RTL-SDR keep their own, tested, native paths and are never opened through SoapySDR.
 */
public enum SoapyDriver {
    //        key        name            module   default  sample rates offered (all split by the wideband filter bank)
    SDRPLAY("sdrplay", "SDRplay RSP",  "sdrplay", 5e6,  new double[]{5e6, 10e6, 2.5e6}),
    HACKRF ("hackrf",  "HackRF One",   "hackrf",  10e6, new double[]{10e6, 20e6, 5e6, 2.5e6}),
    LIME   ("lime",    "LimeSDR",      "lms7",    10e6, new double[]{10e6, 5e6, 20e6, 2.5e6}),
    UHD    ("uhd",     "Ettus USRP",   "uhd",     10e6, new double[]{10e6, 5e6, 2.5e6, 20e6});

    /** SoapySDR driver key ("driver=..."), product name, module file name fragment. */
    public final String key, product, module;
    public final double defaultRate;
    public final double[] rates;

    SoapyDriver(String key, String product, String module, double defaultRate, double[] rates) {
        this.key = key; this.product = product; this.module = module; this.defaultRate = defaultRate; this.rates = rates;
    }

    /** Shown next to every one of these receivers in the app. */
    public static final String UNTESTED = "(untested)";
    public static final String ISSUES_URL = "https://github.com/rcummings14/TrunkDeck/issues";
    public static final String UNTESTED_NOTE = "Support for this receiver is new in 1.7.0 and has not been tested with real hardware. "
            + "If something doesn't work, please report it on GitHub Issues (" + ISSUES_URL + ") with the Engine page log.";

    public static SoapyDriver of(String key) {
        for (SoapyDriver d : values()) if (d.key.equalsIgnoreCase(key)) return d;
        return null;
    }

    /** True if a module file (e.g. "libHackRFSupport.so", "LMS7Support.dll") is this driver's SoapySDR module. */
    public boolean isModule(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.contains(module) && n.contains("support");
    }

    /** Whether the bias-T (antenna power) switch is offered: only where the SoapySDR module has a documented setting. */
    public boolean hasBiasTee() { return this == SDRPLAY; }

    /** HackRF has a strong DC spike and no DC correction in hardware: removed in software, and kept away from channels. */
    public boolean softwareDc() { return this == HACKRF; }
    public long dcGuardHz() { return this == HACKRF ? 40_000 : -1; }

    /** Gain setting codes offered in the receiver picker ("default", "agc", "agc:N" SDRplay LNA state, "db:N"). */
    public List<String> gainCodes() {
        List<String> c = new ArrayList<>();
        c.add("default");
        switch (this) {
            case SDRPLAY -> { for (int s = 0; s <= 9; s++) c.add("agc:" + s); }
            case HACKRF -> { for (int g = 70; g >= 10; g -= 4) c.add("db:" + g); }
            case LIME -> { for (int g = 60; g >= 0; g -= 5) c.add("db:" + g); }
            case UHD -> { for (int g = 70; g >= 0; g -= 5) c.add("db:" + g); }
        }
        return c;
    }

    public String describeGain(String code) {
        if (code == null || code.equals("default")) return "Default (" + defaultGainText() + ")";
        if (code.equals("agc")) return "Auto (AGC)";
        if (code.startsWith("agc:")) return "IF AGC, LNA state " + code.substring(4) + (code.equals("agc:0") ? " (most gain)" : "");
        if (code.startsWith("db:")) return code.substring(3) + " dB";
        return code;
    }

    public String defaultGainText() {
        return switch (this) {
            case SDRPLAY -> "IF AGC, LNA state 4";
            case HACKRF -> "LNA 24 + VGA 20 dB, amp off";
            case LIME -> "40 dB";
            case UHD -> "about 60 % of the range";
        };
    }

    static boolean win() { return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"); }

    // ------------------------------------------------------------------ install hints (plain English)
    /** SoapySDR itself is missing. */
    public static String libraryHint(boolean win) {
        return win
                ? "SoapySDR is not installed. For SDRplay, HackRF, LimeSDR and USRP receivers install PothosSDR (one installer that includes SoapySDR "
                  + "and the drivers): https://downloads.myriadrf.org/builds/PothosSDR/ (PothosSDR-2021.07.25-vc16-x64.exe). Then restart TrunkDeck."
                : "SoapySDR is not installed. Fedora: sudo dnf install SoapySDR   Debian/Ubuntu: sudo apt install libsoapysdr0.8 soapysdr-tools   "
                  + "(or install radioconda in your home folder - TrunkDeck finds it). Then restart TrunkDeck.";
    }

    /** The driver's SoapySDR module is missing (or failed to load; err = loader message, may be null). */
    public String moduleHint(boolean win, String err) {
        if (this == SDRPLAY && !SoapySdr.sdrplayApiInstalled())
            return "SDRplay API not installed - download it from sdrplay.com (Downloads \u2192 API), install it, then restart TrunkDeck."
                    + (win ? "" : " It also needs the SoapySDRPlay3 module (see Setup & Help).");
        String failed = err == null || err.isBlank() ? "" : " (the module is there but could not be loaded: " + err.trim() + ")";
        return switch (this) {
            case SDRPLAY -> win
                    ? "The SDRplay module for SoapySDR could not be used" + failed + ". Reinstall the SDRplay API from sdrplay.com, then PothosSDR (it includes the module)."
                    : "The SDRplay module for SoapySDR (SoapySDRPlay3) is not installed" + failed + ". Build it once: install SoapySDR-devel (Fedora) or libsoapysdr-dev (Debian), cmake, g++ and git, then: "
                      + "git clone https://github.com/pothosware/SoapySDRPlay3 && cd SoapySDRPlay3 && mkdir build && cd build && cmake .. && make && sudo make install";
            case HACKRF -> win
                    ? "HackRF support for SoapySDR is missing" + failed + ". It is part of PothosSDR - reinstall it. The HackRF also needs the WinUSB driver (Zadig, as for the Airspy)."
                    : "HackRF support for SoapySDR is not installed" + failed + ". Debian/Ubuntu: sudo apt install soapysdr-module-hackrf   Fedora: not packaged - use radioconda, or build SoapyHackRF "
                      + "(sudo dnf install hackrf-devel SoapySDR-devel cmake gcc-c++ git; git clone https://github.com/pothosware/SoapyHackRF; then cmake/make/sudo make install as for any CMake project).";
            case LIME -> win
                    ? "LimeSDR support for SoapySDR is missing" + failed + ". It is part of PothosSDR (Lime Suite) - reinstall it."
                    : "LimeSDR support for SoapySDR is not installed" + failed + ". Debian/Ubuntu: sudo apt install soapysdr-module-lms7   Fedora: use radioconda, or build Lime Suite from github.com/myriadrf/LimeSuite.";
            case UHD -> win
                    ? "USRP support for SoapySDR is missing" + failed + ". It is part of PothosSDR (UHD) - reinstall it, then run uhd_images_downloader from the PothosSDR bin folder once."
                    : "USRP support for SoapySDR is not installed" + failed + ". Fedora: sudo dnf install soapy-uhd uhd   Debian/Ubuntu: sudo apt install soapysdr-module-uhd uhd-host   then once: sudo uhd_images_downloader";
        };
    }

    /** The module works but found no receiver of this kind: what to check. */
    public String notFoundHint(boolean win) {
        return switch (this) {
            case SDRPLAY -> win ? "No SDRplay RSP found. Plug it in; if SDRuno or SDRconnect is running, close it (only one program can use the RSP)."
                    : "No SDRplay RSP found. Plug it in and make sure the SDRplay API service is running (sudo systemctl restart sdrplay); close other SDR programs.";
            case HACKRF -> win ? "No HackRF found. Plug it in and install the WinUSB driver for \"HackRF One\" with Zadig." : "No HackRF found. Plug it in; Fedora/Debian need the hackrf udev rules (package hackrf), then replug.";
            case LIME -> win ? "No LimeSDR found. Plug it in; the Lime Suite USB driver comes with PothosSDR." : "No LimeSDR found. Plug it in; install Lime Suite's udev rules, then replug.";
            case UHD -> win ? "No USRP found. USB models need the Ettus/WinUSB driver; network models must be reachable (ping 192.168.10.2)."
                    : "No USRP found. USB models need uhd's udev rules and firmware images (sudo uhd_images_downloader); network models must be reachable.";
        };
    }
}
