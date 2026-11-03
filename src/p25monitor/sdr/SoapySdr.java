package p25monitor.sdr;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * SDRplay, HackRF, LimeSDR and Ettus USRP receivers through the SoapySDR C API (ABI 0.8), with Java's Foreign
 * Function &amp; Memory API like the RTL-SDR and Airspy paths: no compiled glue code. SoapySDR and the drivers are
 * installed by the user (PothosSDR on Windows, distribution packages or radioconda on Linux); TrunkDeck bundles none of
 * them (the SDRplay API may not be redistributed at all).
 * <p>Only the four driver modules TrunkDeck uses are loaded ({@code SoapySDR_loadModule}, which also turns off
 * SoapySDR's automatic loading of every installed module), so SoapySDR's own RTL-SDR / Airspy / network modules never
 * touch the receivers TrunkDeck drives natively, and enumeration asks only those four drivers.
 * <p>Streaming: a reader thread calls readStream (CS16) into a native buffer and copies whole blocks into a pool;
 * a decoder thread converts them and runs the DSP - the same split, queue, drop accounting, processing guard and stall
 * watchdog as {@link RtlSdr}.
 * <p><b>UNTESTED with real hardware</b> - tested against the real SoapySDR 0.8.1 library with a simulated module.
 */
public final class SoapySdr implements IqSource {
    static final int RX = 1;
    static final int TIMEOUT = -1, STREAM_ERROR = -2, CORRUPTION = -3, OVERFLOW = -4, NOT_SUPPORTED = -5;
    static final boolean WIN = SoapyDriver.win();

    // ------------------------------------------------------------------ library binding
    static final class Lib {
        final MethodHandle abi, libVersion, lastError, enumerate, kwargsListClear, make, unmake, setRate, getRate, setFreq, getFreq,
                listGains, setGain, setGainElement, getGain, getGainRange, getGainElementRange, hasGainMode, setGainMode,
                listAntennas, setAntenna, getAntenna, hasDc, setDc, setBandwidth, writeSetting, setupStream, closeStream, mtu,
                activate, deactivate, readStream, stringsClear, free, listModules, listModulesPath, loadModule, moduleVersion, errToStr,
                setLogLevel, registerLogHandler;
        final String from;
        final Path dir;

        Lib(SymbolLookup l, String from, Path dir) {
            this.from = from; this.dir = dir;
            Linker k = Linker.nativeLinker();
            ValueLayout I = ValueLayout.JAVA_INT, J = ValueLayout.JAVA_LONG, D = ValueLayout.JAVA_DOUBLE; ValueLayout Z = ValueLayout.JAVA_BOOLEAN;
            AddressLayout P = ValueLayout.ADDRESS;
            ValueLayout CLONG = WIN ? I : J;   // C "long": 32-bit on Windows
            abi = h(k, l, "SoapySDR_getABIVersion", FunctionDescriptor.of(P));
            libVersion = h(k, l, "SoapySDR_getLibVersion", FunctionDescriptor.of(P));
            lastError = h(k, l, "SoapySDRDevice_lastError", FunctionDescriptor.of(P));
            enumerate = h(k, l, "SoapySDRDevice_enumerate", FunctionDescriptor.of(P, P, P));
            kwargsListClear = h(k, l, "SoapySDRKwargsList_clear", FunctionDescriptor.ofVoid(P, J));
            make = h(k, l, "SoapySDRDevice_make", FunctionDescriptor.of(P, P));
            unmake = h(k, l, "SoapySDRDevice_unmake", FunctionDescriptor.of(I, P));
            setRate = h(k, l, "SoapySDRDevice_setSampleRate", FunctionDescriptor.of(I, P, I, J, D));
            getRate = h(k, l, "SoapySDRDevice_getSampleRate", FunctionDescriptor.of(D, P, I, J));
            setFreq = h(k, l, "SoapySDRDevice_setFrequency", FunctionDescriptor.of(I, P, I, J, D, P));
            getFreq = h(k, l, "SoapySDRDevice_getFrequency", FunctionDescriptor.of(D, P, I, J));
            listGains = h(k, l, "SoapySDRDevice_listGains", FunctionDescriptor.of(P, P, I, J, P));
            setGain = h(k, l, "SoapySDRDevice_setGain", FunctionDescriptor.of(I, P, I, J, D));
            setGainElement = h(k, l, "SoapySDRDevice_setGainElement", FunctionDescriptor.of(I, P, I, J, P, D));
            getGain = h(k, l, "SoapySDRDevice_getGain", FunctionDescriptor.of(D, P, I, J));
            StructLayout RANGE = MemoryLayout.structLayout(D.withName("min"), D.withName("max"), D.withName("step"));
            getGainRange = h(k, l, "SoapySDRDevice_getGainRange", FunctionDescriptor.of(RANGE, P, I, J));
            getGainElementRange = h(k, l, "SoapySDRDevice_getGainElementRange", FunctionDescriptor.of(RANGE, P, I, J, P));
            hasGainMode = h(k, l, "SoapySDRDevice_hasGainMode", FunctionDescriptor.of(Z, P, I, J));
            setGainMode = h(k, l, "SoapySDRDevice_setGainMode", FunctionDescriptor.of(I, P, I, J, Z));
            listAntennas = h(k, l, "SoapySDRDevice_listAntennas", FunctionDescriptor.of(P, P, I, J, P));
            setAntenna = h(k, l, "SoapySDRDevice_setAntenna", FunctionDescriptor.of(I, P, I, J, P));
            getAntenna = h(k, l, "SoapySDRDevice_getAntenna", FunctionDescriptor.of(P, P, I, J));
            hasDc = h(k, l, "SoapySDRDevice_hasDCOffsetMode", FunctionDescriptor.of(Z, P, I, J));
            setDc = h(k, l, "SoapySDRDevice_setDCOffsetMode", FunctionDescriptor.of(I, P, I, J, Z));
            setBandwidth = h(k, l, "SoapySDRDevice_setBandwidth", FunctionDescriptor.of(I, P, I, J, D));
            writeSetting = h(k, l, "SoapySDRDevice_writeSetting", FunctionDescriptor.of(I, P, P, P));
            setupStream = h(k, l, "SoapySDRDevice_setupStream", FunctionDescriptor.of(P, P, I, P, P, J, P));
            closeStream = h(k, l, "SoapySDRDevice_closeStream", FunctionDescriptor.of(I, P, P));
            mtu = h(k, l, "SoapySDRDevice_getStreamMTU", FunctionDescriptor.of(J, P, P));
            activate = h(k, l, "SoapySDRDevice_activateStream", FunctionDescriptor.of(I, P, P, I, J, J));
            deactivate = h(k, l, "SoapySDRDevice_deactivateStream", FunctionDescriptor.of(I, P, P, I, J));
            readStream = h(k, l, "SoapySDRDevice_readStream", FunctionDescriptor.of(I, P, P, P, J, P, P, CLONG));
            stringsClear = h(k, l, "SoapySDRStrings_clear", FunctionDescriptor.ofVoid(P, J));
            free = h(k, l, "SoapySDR_free", FunctionDescriptor.ofVoid(P));
            listModules = h(k, l, "SoapySDR_listModules", FunctionDescriptor.of(P, P));
            listModulesPath = h(k, l, "SoapySDR_listModulesPath", FunctionDescriptor.of(P, P, P));
            loadModule = h(k, l, "SoapySDR_loadModule", FunctionDescriptor.of(P, P));
            moduleVersion = h(k, l, "SoapySDR_getModuleVersion", FunctionDescriptor.of(P, P));
            errToStr = h(k, l, "SoapySDR_errToStr", FunctionDescriptor.of(P, I));
            setLogLevel = h(k, l, "SoapySDR_setLogLevel", FunctionDescriptor.ofVoid(I));
            registerLogHandler = h(k, l, "SoapySDR_registerLogHandler", FunctionDescriptor.ofVoid(P));
        }

        static MethodHandle h(Linker k, SymbolLookup l, String n, FunctionDescriptor d) {
            return k.downcallHandle(l.find(n).orElseThrow(() -> new UnsatisfiedLinkError("SoapySDR has no " + n + " (TrunkDeck needs SoapySDR 0.8)")), d);
        }
    }

    private static Lib lib;
    private static String loadError;
    private static final List<String> tried = new ArrayList<>();

    static String home() { return System.getProperty("user.home", ""); }

    /** Where SoapySDR is looked for: an override, the system library, PothosSDR / radioconda installs. */
    static List<Object> candidates() {
        List<Object> c = new ArrayList<>();
        String ov = System.getProperty("trunkdeck.libsoapysdr", System.getenv("TRUNKDECK_SOAPYSDR"));
        if (ov != null && !ov.isBlank()) { c.add(Paths.get(ov.trim())); return c; }
        if (WIN) {
            List<String> roots = new ArrayList<>();
            for (String pf : new String[]{System.getenv("ProgramFiles"), "C:\\Program Files"}) if (pf != null) roots.add(pf + "\\PothosSDR\\bin");
            roots.add(home() + "\\radioconda\\Library\\bin");
            String la = System.getenv("LOCALAPPDATA"); if (la != null) roots.add(la + "\\radioconda\\Library\\bin");
            String pd = System.getenv("ProgramData"); roots.add((pd == null ? "C:\\ProgramData" : pd) + "\\radioconda\\Library\\bin");
            String path = System.getenv("PATH");
            if (path != null) for (String d : path.split(";")) if (!d.isBlank()) roots.add(d.trim());
            Set<String> seen = new HashSet<>();
            for (String r : roots) if (seen.add(r.toLowerCase(Locale.ROOT))) c.add(Paths.get(r, "SoapySDR.dll"));
        } else {
            c.add("libSoapySDR.so.0.8");
            for (String d : new String[]{"/usr/lib64", "/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu", "/usr/lib/arm-linux-gnueabihf", "/usr/local/lib", "/usr/local/lib64", "/usr/lib",
                    home() + "/radioconda/lib", home() + "/miniforge3/lib", home() + "/mambaforge/lib", "/opt/radioconda/lib"})
                c.add(Paths.get(d, "libSoapySDR.so.0.8"));
        }
        return c;
    }

    /** False when the user switched SoapySDR receivers off (Engine page), so nothing of SoapySDR is loaded. */
    public static volatile boolean enabled = true;

    public static synchronized Lib lib() {
        if (lib != null) return lib;
        if (!enabled) throw new IllegalStateException("SoapySDR receivers are switched off (Engine page).");
        if (loadError != null) throw new IllegalStateException(loadError);
        tried.clear();
        for (Object c : candidates()) {
            try {
                SymbolLookup l; Path dir = null;
                if (c instanceof Path p) {
                    if (!Files.exists(p)) { tried.add(p + " (missing)"); continue; }
                    dir = p.toAbsolutePath().getParent();
                    if (WIN) windowsDllDirectory(dir);
                    l = SymbolLookup.libraryLookup(p, Arena.global());
                } else l = SymbolLookup.libraryLookup((String) c, Arena.global());
                Lib x = new Lib(l, c.toString(), dir);
                String abi = cstr((MemorySegment) x.abi.invoke());
                if (!abi.startsWith("0.8")) { tried.add(c + " (SoapySDR ABI " + abi + ", TrunkDeck needs 0.8)"); continue; }
                x.setLogLevel.invoke(Integer.getInteger("trunkdeck.soapyLogLevel", 4));   // warnings and errors
                installLogHandler(x);
                lib = x;
                return lib;
            } catch (Throwable t) { tried.add(c + " (" + t.getMessage() + ")"); }
        }
        loadError = SoapyDriver.libraryHint(WIN);
        throw new IllegalStateException(loadError);
    }

    public static boolean available() { try { lib(); return true; } catch (Throwable t) { return false; } }
    /** "SoapySDR 0.8.1 (path)" or why it could not be loaded. */
    public static String libraryInfo() {
        try { Lib l = lib(); return "SoapySDR " + cstr((MemorySegment) l.libVersion.invoke()) + " (" + l.from + ")"; }
        catch (Throwable t) { return t.getMessage(); }
    }
    /** Every place SoapySDR was looked for, and why each one did not work (diagnostics). */
    public static synchronized List<String> triedPaths() { return new ArrayList<>(tried); }

    /**
     * Windows: the driver DLLs next to SoapySDR.dll (PothosSDR\bin, radioconda\Library\bin) must be found when the
     * modules are loaded, even if that folder is not on PATH (the usual cause of "LoadLibrary() failed" with
     * SoapySDR on Windows). The SDRplay API DLL lives in its own folder: loaded first by its full path.
     */
    static void windowsDllDirectory(Path dir) {
        try (Arena a = Arena.ofConfined()) {
            SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
            MethodHandle set = Linker.nativeLinker().downcallHandle(k32.find("SetDllDirectoryW").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            byte[] w = (dir.toString() + "\0").getBytes(StandardCharsets.UTF_16LE);
            MemorySegment s = a.allocate(w.length + 2); s.copyFrom(MemorySegment.ofArray(w));
            int ok = (int) set.invoke(s);
            if (ok == 0) tried.add("SetDllDirectoryW(" + dir + ") failed");
        } catch (Throwable t) { tried.add("SetDllDirectoryW: " + t); }
        Path api = sdrplayApi();
        if (api != null) try { SymbolLookup.libraryLookup(api, Arena.global()); } catch (Throwable t) { tried.add(api + " (" + t.getMessage() + ")"); }
    }

    /** The installed SDRplay API library, or null. */
    public static Path sdrplayApi() {
        String ov = System.getProperty("trunkdeck.sdrplayApi");
        if (ov != null) { Path p = Paths.get(ov); return Files.exists(p) ? p : null; }
        List<Path> c = new ArrayList<>();
        if (WIN) {
            for (String pf : new String[]{System.getenv("ProgramFiles"), "C:\\Program Files"}) if (pf != null) c.add(Paths.get(pf, "SDRplay", "API", "x64", "sdrplay_api.dll"));
        } else for (String d : new String[]{"/usr/local/lib", "/usr/lib", "/usr/lib64", "/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu", "/usr/local/lib64"})
            c.add(Paths.get(d, "libsdrplay_api.so.3"));
        for (Path p : c) if (Files.exists(p)) return p;
        return null;
    }
    public static boolean sdrplayApiInstalled() { return sdrplayApi() != null; }

    // ------------------------------------------------------------------ driver log (shown in the engine log)
    /** Recent messages from SoapySDR and its drivers (warnings and errors), drained by the engine into its log. */
    public static final ConcurrentLinkedQueue<String> driverLog = new ConcurrentLinkedQueue<>();
    static MemorySegment logStub;

    static void installLogHandler(Lib x) {
        try {
            MethodHandle cb = MethodHandles.lookup().findStatic(SoapySdr.class, "onLog", MethodType.methodType(void.class, int.class, MemorySegment.class));
            logStub = Linker.nativeLinker().upcallStub(cb, FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT, ValueLayout.ADDRESS), Arena.global());
            x.registerLogHandler.invoke(logStub);
        } catch (Throwable t) { tried.add("log handler: " + t); }
    }

    /** SoapySDR log callback (any native thread). Must never throw. */
    static void onLog(int level, MemorySegment msg) {
        try {
            String s = cstr(msg).trim();
            if (s.isEmpty()) return;
            driverLog.add((level <= 3 ? "error: " : level == 4 ? "warning: " : "") + s.replace('\n', ' '));
            while (driverLog.size() > 200) driverLog.poll();
        } catch (Throwable ignored) {}
    }

    // ------------------------------------------------------------------ modules
    /** Per driver: its module file and the loader result ("" = loaded). Missing driver = no module file found. */
    public static final class Module { public String path = "", error = "", version = ""; public boolean ok() { return error.isEmpty(); } }
    private static Map<SoapyDriver, Module> modules;

    public static synchronized Map<SoapyDriver, Module> modules() {
        if (modules != null) return modules;
        Lib l = lib();
        Map<SoapyDriver, Module> m = new EnumMap<>(SoapyDriver.class);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment n = a.allocate(ValueLayout.JAVA_LONG);
            MemorySegment list = (MemorySegment) l.listModules.invoke(n);
            long count = n.get(ValueLayout.JAVA_LONG, 0);
            List<String> paths = new ArrayList<>(strings(list, count));
            l.stringsClear.invoke(a.allocateFrom(ValueLayout.ADDRESS, list), count);
            // modules next to a SoapySDR that was found by its path (radioconda / PothosSDR), even if its built-in root is wrong
            if (l.dir != null) for (Path md : new Path[]{l.dir.resolve("SoapySDR/modules0.8"), l.dir.resolveSibling("lib").resolve("SoapySDR").resolve("modules0.8")}) {
                if (!Files.isDirectory(md)) continue;
                MemorySegment ml = (MemorySegment) l.listModulesPath.invoke(a.allocateFrom(md.toString()), n);
                long mc = n.get(ValueLayout.JAVA_LONG, 0);
                for (String x : strings(ml, mc)) if (!paths.contains(x)) paths.add(x);
                if (ml.address() != 0) l.stringsClear.invoke(a.allocateFrom(ValueLayout.ADDRESS, ml), mc);
            }
            Set<String> seen = new HashSet<>();
            paths.removeIf(x -> { try { return !seen.add(Paths.get(x).toRealPath().toString()); } catch (Exception e) { return !seen.add(x); } });
            for (String p : paths) {
                SoapyDriver d = null;
                for (SoapyDriver x : SoapyDriver.values()) if (x.isModule(Paths.get(p))) d = x;
                if (d == null || (m.containsKey(d) && m.get(d).ok())) continue;   // not one of ours (rtlsdr, airspy, remote ...): never loaded
                Module mod = new Module(); mod.path = p;
                MemorySegment err = (MemorySegment) l.loadModule.invoke(a.allocateFrom(p));
                mod.error = cstr(err); if (err.address() != 0) l.free.invoke(err);
                if (mod.error.contains("already loaded")) mod.error = "";
                if (mod.ok()) { MemorySegment v = (MemorySegment) l.moduleVersion.invoke(a.allocateFrom(p)); mod.version = cstr(v); if (v.address() != 0) l.free.invoke(v); }
                m.put(d, mod);
            }
        } catch (Throwable t) { throw new IllegalStateException("Listing SoapySDR modules failed: " + t, t); }
        modules = m;
        return m;
    }

    /** One line per driver for the Engine page and Setup &amp; Help: ready, or what to install. */
    public static List<String> status() {
        List<String> out = new ArrayList<>();
        Map<SoapyDriver, Module> m;
        try { m = modules(); } catch (Throwable t) { out.add(t.getMessage()); return out; }
        for (SoapyDriver d : SoapyDriver.values()) {
            Module x = m.get(d);
            if (x != null && x.ok() && !(d == SoapyDriver.SDRPLAY && !sdrplayApiInstalled())) out.add(d.product + ": driver ready" + (x.version.isBlank() ? "" : " (" + x.version + ")"));
            else out.add(d.product + ": " + d.moduleHint(WIN, x == null ? null : x.error));
        }
        return out;
    }

    // ------------------------------------------------------------------ enumeration
    public static final class Info {
        public SoapyDriver driver;
        public int index;
        public final Map<String, String> args = new LinkedHashMap<>();
        public String serial() { return args.getOrDefault("serial", ""); }
        /** Stable key for per-receiver settings ("soapy-hackrf-SERIAL"). */
        public String key() {
            String s = serial().replaceAll("[^A-Za-z0-9]", "");
            return "soapy-" + driver.key + "-" + (s.isEmpty() ? "idx" + index : s);
        }
        public String label() {
            String l = args.getOrDefault("label", "").trim();
            if (l.isEmpty()) { String p = args.getOrDefault("product", args.getOrDefault("device", driver.product)); l = p + (serial().isBlank() ? " #" + index : "  SN " + serial()); }
            if (!l.toLowerCase(Locale.ROOT).contains(driver.product.split(" ")[0].toLowerCase(Locale.ROOT).substring(0, 4))) l = driver.product + " " + l;
            return l;
        }
        /** The label with the "(untested)" tag (receiver picker, engine log). */
        public String tagged() { return label() + "  " + SoapyDriver.UNTESTED; }
        public String toString() { return tagged(); }
    }

    /** Receivers of all four kinds; each driver is asked separately and given at most timeoutMs. */
    public static List<Info> list() { return list(Long.getLong("trunkdeck.soapyFindMs", 8000)); }

    public static List<Info> list(long timeoutMs) {
        Lib l = lib();
        Map<SoapyDriver, Module> m = modules();
        List<Info> out = new ArrayList<>();
        ExecutorService ex = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "soapy-find"); t.setDaemon(true); return t; });
        try {
            Map<SoapyDriver, Future<List<Info>>> f = new EnumMap<>(SoapyDriver.class);
            for (SoapyDriver d : SoapyDriver.values()) if (m.containsKey(d) && m.get(d).ok()) f.put(d, ex.submit(() -> { try { return find(l, d); } catch (Throwable t) { throw new Exception(t); } }));
            long end = System.currentTimeMillis() + timeoutMs;
            for (var e : f.entrySet()) {
                try { out.addAll(e.getValue().get(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS)); }
                catch (TimeoutException t) { driverLog.add(e.getKey().product + ": searching for receivers took longer than " + timeoutMs / 1000 + " s - skipped"); }
                catch (Exception t) { driverLog.add(e.getKey().product + ": searching for receivers failed: " + t.getCause()); }
            }
        } finally { ex.shutdown(); }
        lastFound = out;
        return out;
    }

    static volatile List<Info> lastFound;
    /** The result of the last search (the UI shows it at once while a new search runs in the background). */
    public static List<Info> lastFound() { List<Info> l = lastFound; return l == null ? List.of() : l; }
    /** True once a search has finished. */
    public static boolean searched() { return lastFound != null; }

    static List<Info> find(Lib l, SoapyDriver d) throws Throwable {
        List<Info> out = new ArrayList<>();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment args = kwargs(a, Map.of("driver", d.key)), n = a.allocate(ValueLayout.JAVA_LONG);
            MemorySegment res = (MemorySegment) l.enumerate.invoke(args, n);
            long count = n.get(ValueLayout.JAVA_LONG, 0);
            if (res.address() == 0 || count == 0) return out;
            MemorySegment arr = res.reinterpret(24 * count);
            for (int i = 0; i < count; i++) {
                Info info = new Info(); info.driver = d; info.index = i;
                info.args.putAll(readKwargs(arr.asSlice(24L * i, 24)));
                if (info.args.containsKey("driver") && !info.args.get("driver").equalsIgnoreCase(d.key)) continue;
                info.args.put("driver", d.key);
                out.add(info);
            }
            l.kwargsListClear.invoke(res, count);
        }
        return out;
    }

    static MemorySegment kwargs(Arena a, Map<String, String> m) {
        MemorySegment kw = a.allocate(24, 8), keys = a.allocate(ValueLayout.ADDRESS, Math.max(1, m.size())), vals = a.allocate(ValueLayout.ADDRESS, Math.max(1, m.size()));
        int i = 0;
        for (var e : m.entrySet()) { keys.setAtIndex(ValueLayout.ADDRESS, i, a.allocateFrom(e.getKey())); vals.setAtIndex(ValueLayout.ADDRESS, i, a.allocateFrom(e.getValue())); i++; }
        kw.set(ValueLayout.JAVA_LONG, 0, m.size()); kw.set(ValueLayout.ADDRESS, 8, keys); kw.set(ValueLayout.ADDRESS, 16, vals);
        return kw;
    }

    static Map<String, String> readKwargs(MemorySegment kw) {
        Map<String, String> m = new LinkedHashMap<>();
        long n = kw.get(ValueLayout.JAVA_LONG, 0);
        if (n <= 0 || n > 256) return m;
        MemorySegment keys = kw.get(ValueLayout.ADDRESS, 8).reinterpret(8 * n), vals = kw.get(ValueLayout.ADDRESS, 16).reinterpret(8 * n);
        for (int i = 0; i < n; i++) m.put(cstr(keys.getAtIndex(ValueLayout.ADDRESS, i)), cstr(vals.getAtIndex(ValueLayout.ADDRESS, i)));
        return m;
    }

    static List<String> strings(MemorySegment list, long n) {
        List<String> out = new ArrayList<>();
        if (list == null || list.address() == 0 || n <= 0) return out;
        MemorySegment arr = list.reinterpret(8 * n);
        for (int i = 0; i < n; i++) out.add(cstr(arr.getAtIndex(ValueLayout.ADDRESS, i)));
        return out;
    }

    static String cstr(MemorySegment p) {
        if (p == null || p.address() == 0) return "";
        return p.reinterpret(4096).getString(0);
    }

    // ------------------------------------------------------------------ device
    final Info info;
    final SoapyDriver driver;
    final Lib l;
    final Arena arena = Arena.ofShared();
    MemorySegment dev = MemorySegment.NULL, stream = MemorySegment.NULL;
    double rate;
    volatile long center;
    int ppm;
    String gainCode = "default";
    boolean bias;
    volatile boolean run;
    volatile String error;
    volatile int flush;
    /** What was applied when the receiver was opened (engine log, diagnostics). */
    public volatile String configReport = "", antenna = "", gainReport = "";
    public volatile long overflows, timeouts, readErrors, blocks, droppedBlocks;
    public volatile int queuePeak;
    public volatile double clipFraction, peakDb = -99;
    public volatile long mtuSamples;

    public SoapySdr(Info info) { this.info = info; this.driver = info.driver; this.l = lib(); this.rate = driver.defaultRate; }

    static String lastError(Lib l) { try { return cstr((MemorySegment) l.lastError.invoke()); } catch (Throwable t) { return t.toString(); } }
    String err(int code) { try { return cstr((MemorySegment) l.errToStr.invoke(code)); } catch (Throwable t) { return String.valueOf(code); } }

    /** Opens the receiver and applies sample rate, bandwidth, antenna, gain and settings. */
    public synchronized void open(double wantRate, String gain, int ppm, boolean biasTee, String antennaSetting) {
        this.ppm = ppm; this.gainCode = gain == null ? "default" : gain; this.bias = biasTee;
        Map<String, String> a = new LinkedHashMap<>(info.args); a.remove("label");
        try (Arena t = Arena.ofConfined()) {
            dev = (MemorySegment) l.make.invoke(kwargs(t, a));
        } catch (Throwable x) { throw new IllegalStateException("Opening " + info.label() + " failed: " + x, x); }
        if (dev.address() == 0) {
            String e = lastError(l);
            throw new IllegalStateException("Could not open " + info.label() + (e.isBlank() ? "" : ": " + e) + ". Is another program using it? " + driver.notFoundHint(WIN));
        }
        List<String> rep = new ArrayList<>();
        try {
            // sample rate: the wanted one, else the next one the filter bank can split, else whatever the driver set
            List<Double> order = new ArrayList<>(); order.add(wantRate); for (double r : driver.rates) if (!order.contains(r)) order.add(r);
            double got = 0;
            for (double r : order) {
                int rc = (int) l.setRate.invoke(dev, RX, 0L, r);
                got = (double) l.getRate.invoke(dev, RX, 0L);
                if (rc == 0 && Math.abs(got - r) < 1) break;
                rep.add(String.format(Locale.ROOT, "%.2f MS/s not accepted (driver set %.4f MS/s)", r / 1e6, got / 1e6));
            }
            if (got <= 0) throw new IllegalStateException("the driver accepted none of the sample rates " + order);
            rate = got;
            if (driver == SoapyDriver.LIME || driver == SoapyDriver.UHD) { int rc = (int) l.setBandwidth.invoke(dev, RX, 0L, rate); if (rc != 0) rep.add("bandwidth " + err(rc)); }
            // antenna: Lime's wide-band input covers every P25 band; others keep the driver's default unless set
            List<String> ants = listStrings(l.listAntennas);
            String ant = antennaSetting != null && !antennaSetting.isBlank() ? antennaSetting.trim() : driver == SoapyDriver.LIME && ants.contains("LNAW") ? "LNAW" : "";
            if (!ant.isEmpty()) try (Arena t = Arena.ofConfined()) { int rc = (int) l.setAntenna.invoke(dev, RX, 0L, t.allocateFrom(ant)); if (rc != 0) rep.add("antenna " + ant + ": " + err(rc)); else antenna = ant; }
            if (antenna.isEmpty() && !ants.isEmpty()) { MemorySegment g = (MemorySegment) l.getAntenna.invoke(dev, RX, 0L); String cur = cstr(g); if (g.address() != 0) l.free.invoke(g); antenna = (cur.isEmpty() ? ants.get(0) : cur) + " (driver default)"; }
            if ((boolean) l.hasDc.invoke(dev, RX, 0L) && !driver.softwareDc()) l.setDc.invoke(dev, RX, 0L, true);
            applyGain();
            if (driver.hasBiasTee()) setting("biasT_ctrl", biasTee ? "true" : "false");
            try (Arena t = Arena.ofConfined()) {
                MemorySegment ch = t.allocateFrom(ValueLayout.JAVA_LONG, 0L);
                stream = (MemorySegment) l.setupStream.invoke(dev, RX, t.allocateFrom("CS16"), ch, 1L, MemorySegment.NULL);
            }
            if (stream.address() == 0) throw new IllegalStateException("setting up the sample stream failed: " + lastError(l));
            mtuSamples = (long) l.mtu.invoke(dev, stream);
        } catch (Throwable x) {
            closeDevice();
            throw x instanceof IllegalStateException ise && ise.getMessage().startsWith("Could not") ? ise : new IllegalStateException("Setting up " + info.label() + " failed: " + x.getMessage(), x);
        }
        configReport = String.format(Locale.ROOT, "%.2f MS/s, gain %s, antenna %s%s%s%s", rate / 1e6, gainReport, antenna.isEmpty() ? "-" : antenna,
                driver.hasBiasTee() ? (biasTee ? ", bias-T ON" : ", bias-T off") : "", ppm != 0 ? ", PPM " + ppm : "", rep.isEmpty() ? "" : " (" + String.join("; ", rep) + ")");
    }

    List<String> listStrings(MethodHandle h) throws Throwable {
        try (Arena t = Arena.ofConfined()) {
            MemorySegment n = t.allocate(ValueLayout.JAVA_LONG);
            MemorySegment s = (MemorySegment) h.invoke(dev, RX, 0L, n);
            long c = n.get(ValueLayout.JAVA_LONG, 0);
            List<String> out = strings(s, c);
            if (s.address() != 0) l.stringsClear.invoke(t.allocateFrom(ValueLayout.ADDRESS, s), c);
            return out;
        }
    }

    double[] range(String element) throws Throwable {
        try (Arena t = Arena.ofConfined()) {
            MemorySegment r = element == null ? (MemorySegment) l.getGainRange.invoke((SegmentAllocator) t, dev, RX, 0L)
                    : (MemorySegment) l.getGainElementRange.invoke((SegmentAllocator) t, dev, RX, 0L, t.allocateFrom(element));
            return new double[]{r.get(ValueLayout.JAVA_DOUBLE, 0), r.get(ValueLayout.JAVA_DOUBLE, 8), r.get(ValueLayout.JAVA_DOUBLE, 16)};
        }
    }

    int element(String name, double v) throws Throwable {
        try (Arena t = Arena.ofConfined()) { return (int) l.setGainElement.invoke(dev, RX, 0L, t.allocateFrom(name), v); }
    }

    void setting(String k, String v) throws Throwable {
        try (Arena t = Arena.ofConfined()) { int rc = (int) l.writeSetting.invoke(dev, t.allocateFrom(k), t.allocateFrom(v)); if (rc != 0) driverLog.add(info.label() + ": setting " + k + "=" + v + " returned " + rc); }
    }

    static double clamp(double v, double[] r) { return Math.max(r[0], Math.min(r[1], v)); }

    /** Applies the gain code: driver-specific defaults, SDRplay IF AGC with an LNA state, or a total in dB. */
    void applyGain() throws Throwable {
        List<String> els = listStrings(l.listGains);
        boolean hasAgc = (boolean) l.hasGainMode.invoke(dev, RX, 0L);
        String c = gainCode;
        List<String> rep = new ArrayList<>();
        if (driver == SoapyDriver.SDRPLAY && (c.equals("default") || c.startsWith("agc"))) {
            int st = c.startsWith("agc:") ? Integer.parseInt(c.substring(4)) : 4;
            if (hasAgc) { l.setGainMode.invoke(dev, RX, 0L, true); rep.add("IF AGC"); }
            if (els.contains("RFGR")) { double v = clamp(st, range("RFGR")); element("RFGR", v); rep.add("LNA state " + (int) v); }
        } else if (driver == SoapyDriver.HACKRF && (c.equals("default") || c.startsWith("db:"))) {
            if (hasAgc) l.setGainMode.invoke(dev, RX, 0L, false);
            double total = c.startsWith("db:") ? Double.parseDouble(c.substring(3)) : 44;
            double lna = Math.max(0, Math.min(40, Math.floor(total * 0.55 / 8) * 8)), vga = Math.max(0, Math.min(62, Math.round((total - lna) / 2) * 2));
            if (els.contains("AMP")) element("AMP", 0);
            if (els.contains("LNA")) element("LNA", lna);
            if (els.contains("VGA")) element("VGA", vga);
            rep.add(String.format(Locale.ROOT, "LNA %.0f + VGA %.0f dB, amp off", lna, vga));
        } else {
            if (hasAgc) l.setGainMode.invoke(dev, RX, 0L, false);
            double[] r = range(null);
            double want = c.startsWith("db:") ? Double.parseDouble(c.substring(3)) : driver == SoapyDriver.UHD ? r[0] + 0.6 * (r[1] - r[0]) : 40;
            double v = clamp(want, r);
            int rc = (int) l.setGain.invoke(dev, RX, 0L, v);
            rep.add(String.format(Locale.ROOT, "%.1f dB", v) + (v != want ? String.format(Locale.ROOT, " (range %.0f-%.0f dB)", r[0], r[1]) : "") + (rc != 0 ? " [" + err(rc) + "]" : ""));
        }
        double now = (double) l.getGain.invoke(dev, RX, 0L);
        gainReport = String.join(", ", rep) + String.format(Locale.ROOT, "; driver reports %.1f dB", now);
    }

    @Override public synchronized boolean tune(long hz) {
        if (dev.address() == 0) return false;
        double req = hz / (1 + ppm * 1e-6);   // software PPM correction (works the same for every driver)
        int r;
        try { r = (int) l.setFreq.invoke(dev, RX, 0L, req, MemorySegment.NULL); } catch (Throwable t) { error = "Tuning failed: " + t; return false; }
        if (r != 0) { error = "Tuning to " + hz + " Hz failed (" + err(r) + ")"; return false; }
        center = hz;
        filled.drainTo(new ArrayList<>());
        while (free.size() < queue && free.offer(new short[BLOCK * 2])) {}
        flush = 2; gap = true;
        return true;
    }

    @Override public double sampleRate() { return rate; }
    @Override public long centerHz() { return center; }
    @Override public String name() { return info.tagged(); }
    @Override public boolean running() { return run; }
    @Override public String error() { return error; }
    @Override public long dcGuardHz() { return driver.dcGuardHz(); }
    public Info info() { return info; }
    public SoapyDriver driver() { return driver; }

    // ------------------------------------------------------------------ streaming
    /** Complex samples per block (~6.5 ms at 10 MS/s, 26 ms at 2.5 MS/s). */
    static final int BLOCK = 1 << 16;
    int queue = 32;
    final ArrayBlockingQueue<short[]> filled = new ArrayBlockingQueue<>(512), free = new ArrayBlockingQueue<>(512);
    volatile boolean gap;
    Thread readThread, thread;
    public final ProcessingGuard guard = new ProcessingGuard();
    volatile int workGen;
    Listener listener;
    long watchBlocks;
    static final short[] END = new short[0];
    @Override public ProcessingGuard guard() { return guard; }

    @Override public void start(Listener listener) {
        if (stream.address() == 0) throw new IllegalStateException(info.label() + " is not open");
        queue = (int) Math.max(16, Math.min(400, Math.round(1.2 * rate / BLOCK)));   // ~1.2 s of samples before blocks are dropped
        filled.clear(); free.clear();
        for (int k = 0; k < queue; k++) free.add(new short[BLOCK * 2]);
        this.listener = listener;
        int rc;
        try { rc = (int) l.activate.invoke(dev, stream, 0, 0L, 0L); } catch (Throwable t) { throw new IllegalStateException("Starting " + info.label() + " failed: " + t, t); }
        if (rc != 0) throw new IllegalStateException("Starting the sample stream of " + info.label() + " failed (" + err(rc) + ")");
        run = true;
        guard.progress(); watchBlocks = blocks;
        startWork(false);
        readThread = new Thread(this::readLoop, "soapy-usb-" + driver.key + "-" + info.index);
        readThread.setDaemon(true); readThread.setPriority(Thread.MAX_PRIORITY);
        readThread.start();
    }

    void startWork(boolean reset) {
        int gen = ++workGen;
        Thread t = new Thread(() -> work(listener, gen, reset), "soapy-" + driver.key + "-" + info.index + (gen > 1 ? "-r" + (gen - 1) : ""));
        t.setDaemon(true); t.setPriority(Thread.MAX_PRIORITY - 1);
        thread = t;
        t.start();
    }

    /** Reader side: only readStream and a copy, never blocks on the decoders. */
    void readLoop() {
        long bad = 0, quietSince = System.nanoTime();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment buf = a.allocate(BLOCK * 4L, 64), buffs = a.allocate(ValueLayout.ADDRESS), flags = a.allocate(ValueLayout.JAVA_INT), time = a.allocate(ValueLayout.JAVA_LONG);
            int pos = 0;
            while (run) {
                buffs.set(ValueLayout.ADDRESS, 0, buf.asSlice(pos * 4L));
                int r = WIN ? (int) l.readStream.invoke(dev, stream, buffs, (long) (BLOCK - pos), flags, time, 100_000)
                        : (int) l.readStream.invoke(dev, stream, buffs, (long) (BLOCK - pos), flags, time, 100_000L);
                if (r > 0) {
                    bad = 0; quietSince = System.nanoTime();
                    pos += r;
                    if (pos >= BLOCK) { accept(buf); pos = 0; }
                } else if (r == TIMEOUT || r == 0) {
                    timeouts++;
                    if (System.nanoTime() - quietSince > 5_000_000_000L && error == null) { error = info.label() + " delivers no samples (5 s) - unplugged, or used by another program?"; }
                } else if (r == OVERFLOW) {
                    overflows++; pos = 0; gap = true;   // the driver lost samples: the partial block is not continuous
                } else {
                    readErrors++; bad++; pos = 0; gap = true;
                    if (bad >= 50) { error = info.label() + " stopped streaming (" + err(r) + ") - unplugged?"; break; }
                    Thread.sleep(2);
                }
            }
        } catch (Throwable t) { if (run) error = "Reading samples from " + info.label() + " failed: " + t; }
        finally {
            try { l.deactivate.invoke(dev, stream, 0, 0L); } catch (Throwable ignored) {}
            filled.offer(END);
        }
    }

    void accept(MemorySegment buf) {
        blocks++;
        if (flush > 0) { flush--; return; }
        short[] b = free.poll();
        if (b == null) { droppedBlocks++; guard.dropped(); gap = true; return; }
        MemorySegment.copy(buf, ValueLayout.JAVA_SHORT, 0, b, 0, BLOCK * 2);
        filled.offer(b);
        int q = filled.size(); if (q > queuePeak) queuePeak = q;
    }

    /** Decoder side: CS16 -> floats (HackRF: DC removed), level statistics, then the DSP. */
    void work(Listener listener, int gen, boolean resetFirst) {
        float[] i = new float[BLOCK], q = new float[BLOCK];
        final float s = 1f / 32768f;
        final boolean dc = driver.softwareDc();
        float dcI = 0, dcQ = 0;
        if (resetFirst) reset(listener);
        try {
            while (workGen == gen) {
                short[] raw = filled.poll(500, TimeUnit.MILLISECONDS);
                if (raw == null) { if (!run) break; continue; }
                if (raw == END) break;
                if (workGen != gen) { filled.offer(raw); break; }
                try {
                    if (gap) { gap = false; listener.gap(); }
                    int clip = 0, peak = 0; double si = 0, sq = 0;
                    for (int k = 0; k < BLOCK; k++) {
                        int a = raw[2 * k], b = raw[2 * k + 1];
                        i[k] = a * s - dcI; q[k] = b * s - dcQ;
                        if (dc) { si += a; sq += b; }
                        int pa = Math.max(Math.abs(a), Math.abs(b));
                        if (pa >= 32000) clip++;
                        if (pa > peak) peak = pa;
                    }
                    if (dc) { dcI += 0.25f * ((float) (si / BLOCK) * s - dcI); dcQ += 0.25f * ((float) (sq / BLOCK) * s - dcQ); }
                    free.offer(raw); raw = null;
                    clipFraction = 0.8 * clipFraction + 0.2 * (clip / (double) BLOCK);
                    peakDb = 0.8 * peakDb + 0.2 * (20 * Math.log10(Math.max(1, peak) / 32768.0));
                    listener.samples(i, q, BLOCK);
                    guard.progress();
                } catch (Throwable t) {
                    if (t instanceof InterruptedException ie) throw ie;
                    if (raw != null && raw.length == BLOCK * 2) free.offer(raw);
                    if (!run || workGen != gen) break;
                    long w = guard.failed(t, driver.product + " sample processing");
                    reset(listener);
                    gap = true;
                    guard.pause(w);
                }
            }
        } catch (InterruptedException ignored) {
        } finally { if (workGen == gen && run) guard.events.add(driver.product + " sample processing thread ended unexpectedly"); }
    }

    void reset(Listener listener) {
        try { listener.reset(); } catch (Throwable t) { guard.events.add("resetting the decoders failed: " + ProcessingGuard.describe(ProcessingGuard.root(t), 6)); }
    }

    @Override public boolean watchdog(long stallMs) {
        Thread t = thread;
        if (!run || t == null || listener == null) return false;
        long b = blocks; boolean delivering = b > watchBlocks; watchBlocks = b;
        boolean dead = !t.isAlive();
        long idle = (System.nanoTime() - guard.lastProgressNanos) / 1_000_000L;
        if (!dead && (!delivering || guard.recovering || idle < stallMs)) return false;
        String why = dead ? driver.product + " sample processing thread ended" : driver.product + " sample processing made no progress for " + idle / 1000 + " s while the receiver kept delivering";
        guard.stalled(why);
        t.interrupt();
        int have = free.size() + filled.size();
        for (int k = have; k < queue; k++) free.offer(new short[BLOCK * 2]);
        gap = true;
        guard.progress();
        startWork(true);
        return true;
    }

    @Override public void stop() {
        run = false;
        Thread r = readThread;
        if (r != null) try { r.join(3000); } catch (InterruptedException ignored) {}
        filled.offer(END);
        if (thread != null) try { thread.join(1500); } catch (InterruptedException ignored) {}
        synchronized (this) { if (r == null || !r.isAlive()) closeDevice(); }
    }

    synchronized void closeDevice() {
        try {
            if (dev.address() != 0 && stream.address() != 0) l.closeStream.invoke(dev, stream);
            stream = MemorySegment.NULL;
            if (dev.address() != 0) l.unmake.invoke(dev);
        } catch (Throwable t) { driverLog.add(info.label() + ": closing failed: " + t); }
        dev = MemorySegment.NULL;
    }
}
