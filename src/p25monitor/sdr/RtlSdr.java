package p25monitor.sdr;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.*;
import java.util.*;

/**
 * RTL2832U dongle access through librtlsdr using Java's Foreign Function &amp; Memory API (Java 22+), so no JNA or
 * compiled glue code is needed. Windows: the bundled static rtlsdr.dll (rtl-sdr-blog v1.3.6, includes libusb; the
 * dongle needs the WinUSB driver from Zadig). Linux: the system librtlsdr (dnf/apt package "rtl-sdr") or the bundled
 * librtlsdr.so (needs libusb-1.0, present on all desktop distros).
 */
public final class RtlSdr implements IqSource {
    // ------------------------------------------------------------------ library binding
    static final class Lib {
        final MethodHandle count, name, usbStrings, open, close, setFreq, getFreq, setRate, setPpm, gainMode, setGain, getGains,
                agc, reset, readSync, biasTee, tunerType, directSampling, readAsync, cancelAsync, getGain;
        final String from;

        Lib(SymbolLookup l, String from) {
            this.from = from;
            Linker k = Linker.nativeLinker();
            ValueLayout I = ValueLayout.JAVA_INT; AddressLayout P = ValueLayout.ADDRESS;
            count = h(k, l, "rtlsdr_get_device_count", FunctionDescriptor.of(I));
            name = h(k, l, "rtlsdr_get_device_name", FunctionDescriptor.of(P, I));
            usbStrings = h(k, l, "rtlsdr_get_device_usb_strings", FunctionDescriptor.of(I, I, P, P, P));
            open = h(k, l, "rtlsdr_open", FunctionDescriptor.of(I, P, I));
            close = h(k, l, "rtlsdr_close", FunctionDescriptor.of(I, P));
            setFreq = h(k, l, "rtlsdr_set_center_freq", FunctionDescriptor.of(I, P, I));
            getFreq = h(k, l, "rtlsdr_get_center_freq", FunctionDescriptor.of(I, P));
            setRate = h(k, l, "rtlsdr_set_sample_rate", FunctionDescriptor.of(I, P, I));
            setPpm = h(k, l, "rtlsdr_set_freq_correction", FunctionDescriptor.of(I, P, I));
            gainMode = h(k, l, "rtlsdr_set_tuner_gain_mode", FunctionDescriptor.of(I, P, I));
            setGain = h(k, l, "rtlsdr_set_tuner_gain", FunctionDescriptor.of(I, P, I));
            getGains = h(k, l, "rtlsdr_get_tuner_gains", FunctionDescriptor.of(I, P, P));
            agc = h(k, l, "rtlsdr_set_agc_mode", FunctionDescriptor.of(I, P, I));
            reset = h(k, l, "rtlsdr_reset_buffer", FunctionDescriptor.of(I, P));
            readSync = h(k, l, "rtlsdr_read_sync", FunctionDescriptor.of(I, P, P, I, P));
            biasTee = hOpt(k, l, "rtlsdr_set_bias_tee", FunctionDescriptor.of(I, P, I));
            tunerType = h(k, l, "rtlsdr_get_tuner_type", FunctionDescriptor.of(I, P));
            directSampling = hOpt(k, l, "rtlsdr_set_direct_sampling", FunctionDescriptor.of(I, P, I));
            readAsync = hOpt(k, l, "rtlsdr_read_async", FunctionDescriptor.of(I, P, P, P, I, I));
            cancelAsync = hOpt(k, l, "rtlsdr_cancel_async", FunctionDescriptor.of(I, P));
            getGain = hOpt(k, l, "rtlsdr_get_tuner_gain", FunctionDescriptor.of(I, P));
        }

        static MethodHandle h(Linker k, SymbolLookup l, String n, FunctionDescriptor d) {
            return k.downcallHandle(l.find(n).orElseThrow(() -> new UnsatisfiedLinkError("librtlsdr has no " + n)), d);
        }
        static MethodHandle hOpt(Linker k, SymbolLookup l, String n, FunctionDescriptor d) {
            return l.find(n).map(s -> k.downcallHandle(s, d)).orElse(null);
        }
    }

    private static Lib lib;
    private static String loadError;

    /** Folder that holds bundled native libraries (app/native/<os>-<arch>). */
    public static Path nativeDir() {
        String home = System.getProperty("trunkdeck.home", ".");
        String os = System.getProperty("os.name", "").toLowerCase().contains("win") ? "windows" : "linux";
        String arch = System.getProperty("os.arch", "").contains("aarch64") ? "arm64" : "x64";
        return Paths.get(home, "app", "native", os + "-" + arch);
    }

    public static synchronized Lib lib() {
        if (lib != null) return lib;
        if (loadError != null) throw new IllegalStateException(loadError);
        List<String> tried = new ArrayList<>();
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<Object> candidates = new ArrayList<>();
        String override = System.getProperty("trunkdeck.librtlsdr");
        if (override != null) candidates.add(Paths.get(override));
        if (win) candidates.add(nativeDir().resolve("rtlsdr.dll"));
        else {
            candidates.addAll(List.of("librtlsdr.so.0", "librtlsdr.so.2", "librtlsdr.so"));
            for (String d : new String[]{"/usr/lib64", "/usr/lib/x86_64-linux-gnu", "/usr/local/lib", "/usr/lib", "/usr/lib/aarch64-linux-gnu"})
                candidates.add(Paths.get(d, "librtlsdr.so.0"));
            candidates.add(nativeDir().resolve("librtlsdr.so"));
        }
        for (Object c : candidates) {
            try {
                SymbolLookup l;
                if (c instanceof Path p) {
                    if (!Files.exists(p)) { tried.add(p + " (missing)"); continue; }
                    if (win) { Path usb = p.resolveSibling("libusb-1.0.dll"); if (Files.exists(usb)) SymbolLookup.libraryLookup(usb, Arena.global()); }
                    l = SymbolLookup.libraryLookup(p, Arena.global());
                } else l = SymbolLookup.libraryLookup((String) c, Arena.global());
                lib = new Lib(l, c.toString());
                return lib;
            } catch (Throwable t) { tried.add(c + " (" + t.getMessage() + ")"); }
        }
        loadError = "Could not load librtlsdr. " + (win ? "The bundled rtlsdr.dll is missing - reinstall TrunkDeck."
                : "Install it with:  sudo dnf install rtl-sdr   (Debian/Ubuntu: sudo apt install librtlsdr0)") + "\nTried: " + String.join("; ", tried);
        throw new IllegalStateException(loadError);
    }

    public static boolean available() { try { lib(); return true; } catch (Throwable t) { return false; } }
    public static String libraryInfo() { try { return lib().from; } catch (Throwable t) { return t.getMessage(); } }

    // ------------------------------------------------------------------ enumeration
    public static final class Info {
        public int index;
        public String name = "", manufacturer = "", product = "", serial = "";
        /** Stable key for per-dongle settings: serial if it looks unique, else index. */
        public String key() { return serial.isBlank() || serial.equals("00000001") || serial.equals("0") ? "idx" + index : "sn" + serial; }
        public String label() { return "#" + index + "  " + (product.isBlank() ? name : manufacturer + " " + product).trim() + (serial.isBlank() ? "" : "  SN " + serial); }
        public String toString() { return label(); }
    }

    public static List<Info> list() {
        List<Info> out = new ArrayList<>();
        Lib l = lib();
        try (Arena a = Arena.ofConfined()) {
            int n = (int) l.count.invoke();
            for (int i = 0; i < n; i++) {
                Info d = new Info();
                d.index = i;
                MemorySegment nm = (MemorySegment) l.name.invoke(i);
                d.name = cstr(nm);
                MemorySegment m = a.allocate(256), p = a.allocate(256), s = a.allocate(256);
                if ((int) l.usbStrings.invoke(i, m, p, s) == 0) { d.manufacturer = m.getString(0); d.product = p.getString(0); d.serial = s.getString(0); }
                out.add(d);
            }
        } catch (Throwable t) { throw new IllegalStateException("Listing RTL-SDR dongles failed: " + t, t); }
        return out;
    }

    static String cstr(MemorySegment p) {
        if (p == null || p.address() == 0) return "";
        return p.reinterpret(256).getString(0);
    }

    // ------------------------------------------------------------------ device
    public static final String[] TUNERS = {"Unknown", "E4000", "FC0012", "FC0013", "FC2580", "R820T/R820T2", "R828D"};
    final int index;
    final Lib l;
    MemorySegment dev = MemorySegment.NULL;
    final Arena arena = Arena.ofShared();
    double rate = 2_400_000;
    volatile long center;
    int ppm;
    int gainTenths = -1; // -1 = auto (tuner AGC)
    boolean bias;
    volatile boolean run;
    volatile String error;
    volatile int flush;  // buffers to drop after retune
    Thread thread;
    final Info info;
    public volatile long overruns, buffers;
    /**
     * USB blocks thrown away because the decoders fell more than ~1.3 s behind (each one is a gap in the sample stream;
     * the decoders are told, so they re-sync instead of decoding shifted bits). Should stay 0.
     */
    public volatile long droppedBlocks;
    /** Largest number of USB blocks waiting for the decoders (each ~55 ms). */
    public volatile int queuePeak;
    /** How the USB stream is read ("async 16 x 256 KB" normally). */
    public volatile String usbMode = "-";
    /** Gain the driver reports after setting it (tenths of dB, Integer.MIN_VALUE = unknown), and the return codes. */
    public volatile int gainReadback = Integer.MIN_VALUE;
    public volatile String gainReport = "";
    /** Fraction of recent samples (I or Q) at the ADC limits 0 or 255: the dongle is overloaded when this is not ~0. */
    public volatile double clipFraction;
    /** Recent peak sample level in dB relative to the ADC full scale. */
    public volatile double peakDb = -99;

    public RtlSdr(Info info) { this.info = info; this.index = info.index; this.l = lib(); }

    public synchronized void open() throws Exception {
        MemorySegment pp = arena.allocate(ValueLayout.ADDRESS);
        int r = call(l.open, pp, index);
        if (r < 0) throw new IllegalStateException("Could not open RTL-SDR #" + index + " (error " + r + "). " +
                (System.getProperty("os.name", "").toLowerCase().contains("win") ? "Is the WinUSB driver installed with Zadig, and is no other SDR program using it?"
                        : "Is another program using it, and are the udev rules installed (Setup & Help)? Also unplug/replug after installing rules."));
        dev = pp.get(ValueLayout.ADDRESS, 0);
        call(l.setRate, dev, (int) rate);
    }

    static int call(MethodHandle h, Object... args) {
        try { return (int) h.invokeWithArguments(args); } catch (Throwable t) { throw new IllegalStateException(t); }
    }

    public String tunerName() { int t = call(l.tunerType, dev); return t >= 0 && t < TUNERS.length ? TUNERS[t] : "?"; }

    int[] gainCache;
    /** Gain steps the tuner supports (tenths of dB). */
    public int[] gains() {
        if (gainCache != null) return gainCache;
        if (dev == null || dev.address() == 0) return new int[0];
        int n = call(l.getGains, dev, MemorySegment.NULL);
        if (n <= 0) return new int[0];
        try (Arena a = Arena.ofConfined()) {
            MemorySegment g = a.allocate(ValueLayout.JAVA_INT, n);
            call(l.getGains, dev, g);
            int[] out = new int[n];
            for (int i = 0; i < n; i++) out[i] = g.getAtIndex(ValueLayout.JAVA_INT, i);
            gainCache = out;
            return out;
        }
    }

    public synchronized void configure(int ppm, int gainTenths, boolean biasTee) {
        this.ppm = ppm; this.gainTenths = gainTenths; this.bias = biasTee;
        if (ppm != 0) call(l.setPpm, dev, ppm); // returns -2 if unchanged; harmless
        int rMode, rGain = 0;
        if (gainTenths < 0) { rMode = call(l.gainMode, dev, 0); }
        else {
            rMode = call(l.gainMode, dev, 1);
            int best = gainTenths, bd = Integer.MAX_VALUE;
            for (int g : gains()) if (Math.abs(g - gainTenths) < bd) { bd = Math.abs(g - gainTenths); best = g; }
            rGain = call(l.setGain, dev, best);
            this.gainTenths = best;
        }
        int rAgc = call(l.agc, dev, 0);   // RTL2832 digital AGC off (it would raise weak signals into clipping)
        gainReadback = l.getGain != null ? call(l.getGain, dev) : Integer.MIN_VALUE;
        gainReport = (gainTenths < 0 ? "tuner AGC" : "manual " + gainTenths / 10.0 + " dB") + (gainReadback != Integer.MIN_VALUE ? ", driver reports " + gainReadback / 10.0 + " dB" : "")
                + (rMode != 0 || rGain != 0 || rAgc != 0 ? String.format(Locale.ROOT, " (driver returned mode %d, gain %d, agc %d)", rMode, rGain, rAgc) : "");
        if (l.biasTee != null) call(l.biasTee, dev, biasTee ? 1 : 0);
    }

    public void setSampleRate(double r) { rate = r; if (dev.address() != 0) call(l.setRate, dev, (int) r); }

    @Override public synchronized boolean tune(long hz) {
        if (dev.address() == 0) return false;
        int r = call(l.setFreq, dev, (int) hz);
        if (r < 0) { error = "Tuning to " + hz + " Hz failed (" + r + ")"; return false; }
        center = hz;
        filled.drainTo(new ArrayList<>()); // queued blocks are from the old frequency (pool refilled below)
        while (free.size() < QUEUE && free.offer(new byte[BUF_LEN])) {}
        flush = 2;
        return true;
    }

    @Override public double sampleRate() { return rate; }
    @Override public long centerHz() { return center; }
    @Override public String name() { return info.label(); }
    @Override public boolean running() { return run; }
    @Override public String error() { return error; }
    public Info info() { return info; }
    public int ppmSetting() { return ppm; }
    /** Manual gain in tenths of dB (-1 = tuner AGC). */
    public int gainSetting() { return gainTenths; }

    static final int BUF_LEN = 1 << 18;   // 262144 bytes = 131072 complex samples (~55 ms at 2.4 MS/s)
    static final int USB_BUFS = 16;       // transfers queued in the USB stack (~0.9 s): the dongle never waits for us
    static final int QUEUE = 24;          // blocks waiting for the decoders (~1.3 s) before whole blocks are dropped
    final java.util.concurrent.ArrayBlockingQueue<byte[]> filled = new java.util.concurrent.ArrayBlockingQueue<>(QUEUE + 2),
            free = new java.util.concurrent.ArrayBlockingQueue<>(QUEUE + 2);
    volatile boolean gap;
    Thread usbThread;
    /** Catches processing failures, restarts with backoff, counts restarts and drops while stopped. */
    public final ProcessingGuard guard = new ProcessingGuard();
    volatile int workGen;
    Listener listener;
    long watchBufs;

    @Override public ProcessingGuard guard() { return guard; }

    void startWork(boolean reset) {
        int gen = ++workGen;
        Thread t = new Thread(() -> work(listener, gen, reset), "rtl-" + index + (gen > 1 ? "-r" + (gen - 1) : ""));
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY - 1);
        thread = t;
        t.start();
    }

    /** Stall watchdog: the decoder thread ended, or processed nothing for stallMs while USB delivered: a new one takes over. */
    @Override public boolean watchdog(long stallMs) {
        Thread t = thread;
        if (!run || t == null || listener == null) return false;
        long b = buffers; boolean delivering = b > watchBufs; watchBufs = b;
        boolean dead = !t.isAlive();
        long idle = (System.nanoTime() - guard.lastProgressNanos) / 1_000_000L;
        if (!dead && (!delivering || guard.recovering || idle < stallMs)) return false;
        String why = dead ? "RTL-SDR sample processing thread ended" : "RTL-SDR sample processing made no progress for " + idle / 1000 + " s while USB kept delivering";
        StackTraceElement[] st = t.getStackTrace();
        if (!dead && st.length > 0) { Throwable where = new Throwable("stuck"); where.setStackTrace(st); why += " (" + ProcessingGuard.describe(where, 6).replaceFirst("^java.lang.Throwable: stuck ", "") + ")"; }
        guard.stalled(why);
        t.interrupt();
        int have = free.size() + filled.size();
        for (int k = have; k < QUEUE; k++) free.offer(new byte[BUF_LEN]);   // blocks a stuck thread holds are replaced
        gap = true;
        guard.progress();
        startWork(true);
        return true;
    }
    static final byte[] END = new byte[0];

    /**
     * Streams with rtlsdr_read_async: librtlsdr keeps 16 USB transfers queued, and the callback only copies each buffer
     * into a queue. The decoding runs on a separate thread. 1.1.2 and older called rtlsdr_read_sync and decoded on the
     * same thread, so while a block was being processed no transfer was pending, the dongle's small FIFO overflowed and
     * samples were silently lost at every block boundary (every 55 ms): the P25 bit stream slipped and voice frames
     * after the slip were garbage until the next frame sync.
     */
    @Override public void start(Listener listener) {
        run = true;
        filled.clear(); free.clear();
        for (int k = 0; k < QUEUE; k++) free.add(new byte[BUF_LEN]);
        this.listener = listener;
        guard.progress(); watchBufs = buffers;
        startWork(false);
        usbThread = new Thread(this::usbLoop, "rtl-usb-" + index);
        usbThread.setDaemon(true);
        usbThread.setPriority(Thread.MAX_PRIORITY);
        usbThread.start();
    }

    /** USB side: as little work as possible, never blocks. */
    void usbLoop() {
        try {
            call(l.reset, dev);
            if (l.readAsync != null && !Boolean.getBoolean("trunkdeck.rtlSync")) {
                usbMode = "async " + USB_BUFS + " x " + BUF_LEN / 1024 + " KB";
                MethodHandle cb = java.lang.invoke.MethodHandles.lookup().bind(this, "onUsb",
                        java.lang.invoke.MethodType.methodType(void.class, MemorySegment.class, int.class, MemorySegment.class));
                MemorySegment stub = Linker.nativeLinker().upcallStub(cb, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS), arena);
                int r = (int) l.readAsync.invoke(dev, stub, MemorySegment.NULL, USB_BUFS, BUF_LEN);
                if (r < 0 && run) error = "USB streaming failed (" + r + ") - dongle unplugged?";
            } else {   // fallback (very old librtlsdr): a reader thread that only copies; still far better than decoding in between
                usbMode = "sync reader thread";
                try (Arena a = Arena.ofConfined()) {
                    MemorySegment buf = a.allocate(BUF_LEN, 512), nread = a.allocate(ValueLayout.JAVA_INT);
                    while (run) {
                        int r = (int) l.readSync.invoke(dev, buf, BUF_LEN, nread);
                        if (r < 0) { error = "USB read failed (" + r + ") - dongle unplugged?"; break; }
                        accept(buf, nread.get(ValueLayout.JAVA_INT, 0));
                    }
                }
            }
        } catch (Throwable t) { if (run) error = t.toString(); }
        finally { run = false; filled.offer(END); }
    }

    /** librtlsdr callback (on the USB thread). Must never throw: an exception in a native callback ends the JVM. */
    void onUsb(MemorySegment buf, int len, MemorySegment ctx) {
        try {
            if (!run) { if (l.cancelAsync != null) call(l.cancelAsync, dev); return; }
            accept(buf.reinterpret(len), len);
        } catch (Throwable ignored) {}
    }

    void accept(MemorySegment buf, int n) {
        buffers++;
        if (n < BUF_LEN) overruns++;
        if (flush > 0) { flush--; return; }
        byte[] b = free.poll();
        if (b == null) { droppedBlocks++; guard.dropped(); gap = true; return; }   // decoders too slow: drop a whole block, flag the gap
        int len = Math.min(n, BUF_LEN);
        MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, 0, b, 0, len);
        if (len < BUF_LEN) b = java.util.Arrays.copyOf(b, len);   // rare short read: own array (not returned to the pool)
        filled.offer(b);
        int q = filled.size(); if (q > queuePeak) queuePeak = q;
    }

    /** Decoder side. */
    void work(Listener listener, int gen, boolean resetFirst) {
        float[] i = new float[BUF_LEN / 2], q = new float[BUF_LEN / 2];
        float[] lut = new float[256];
        for (int k = 0; k < 256; k++) lut[k] = (k - 127.4f) / 128f;
        if (resetFirst) reset(listener);
        try {
            while (workGen == gen) {
                byte[] raw = filled.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (raw == null) { if (!run) break; continue; }
                if (raw == END) break;
                if (workGen != gen) { filled.offer(raw); break; }
                try {
                    if (gap) { gap = false; listener.gap(); }
                    int m = raw.length / 2;
                    int clip = 0, peak = 0;
                    for (int k = 0; k < m; k++) {
                        int ra = raw[2 * k] & 0xff, rb = raw[2 * k + 1] & 0xff;
                        i[k] = lut[ra]; q[k] = lut[rb];
                        if (ra == 0 || ra == 255) clip++;
                        if (rb == 0 || rb == 255) clip++;
                        int pa = Math.max(Math.abs(ra - 127), Math.abs(rb - 127)); if (pa > peak) peak = pa;
                    }
                    if (raw.length == BUF_LEN) free.offer(raw);
                    raw = null;
                    clipFraction = 0.8 * clipFraction + 0.2 * (clip / (2.0 * Math.max(1, m)));
                    peakDb = 0.8 * peakDb + 0.2 * (20 * Math.log10(Math.max(1, peak) / 128.0));
                    listener.samples(i, q, m);
                    guard.progress();
                } catch (Throwable t) {                         // a decoder threw: reset and carry on (never stay dead)
                    if (t instanceof InterruptedException) throw (InterruptedException) t;
                    if (raw != null && raw.length == BUF_LEN) free.offer(raw);
                    if (!run || workGen != gen) break;
                    long w = guard.failed(t, "RTL-SDR sample processing");
                    reset(listener);
                    gap = true;
                    guard.pause(w);
                }
            }
        } catch (InterruptedException ignored) {
        } finally { if (workGen == gen && run) guard.events.add("RTL-SDR sample processing thread ended unexpectedly"); }
    }

    void reset(Listener listener) {
        try { listener.reset(); } catch (Throwable t) { guard.events.add("resetting the decoders failed: " + ProcessingGuard.describe(ProcessingGuard.root(t), 6)); }
    }

    @Override public void stop() {
        run = false;
        if (l.cancelAsync != null && dev.address() != 0 && usbThread != null) call(l.cancelAsync, dev);
        if (usbThread != null) try { usbThread.join(2000); } catch (InterruptedException ignored) {}
        filled.offer(END);
        if (thread != null) try { thread.join(1500); } catch (InterruptedException ignored) {}
        synchronized (this) {
            if (dev.address() != 0) { call(l.close, dev); dev = MemorySegment.NULL; }
        }
    }
}
