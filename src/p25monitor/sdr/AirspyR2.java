package p25monitor.sdr;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.util.*;

/**
 * Airspy R2 (and Airspy Mini) receiver driver written for TrunkDeck in Java on top of libusb-1.0 (see {@link LibUsb}):
 * no libairspy, airspy_rx or SDRTrunk is needed. The device's USB vendor requests (receiver mode, sample rate,
 * frequency, LNA / mixer / VGA gain, AGC, GPIO for the bias-T, packing) and the bulk IN sample endpoint are used as
 * documented by the public airspyone_host sources (BSD-3-Clause), which served as the protocol reference only.
 * <p>The receiver streams real 12-bit ADC samples at twice the complex rate (20 MS/s for 10 MS/s I/Q, 12 for 6, 6 for 3,
 * 5 for 2.5). Sample rates come from the same vendor request as libairspy's {@code airspy_get_samplerates};
 * {@link AirspyStream} turns them into I/Q. 16 bulk transfers of 256 KB stay queued (about 105 ms at 10 MS/s);
 * the libusb event loop runs on its own thread and only copies data.
 * <p>Gain: "linearity" and "sensitivity" presets 0-21 (the receiver's own LNA/mixer/VGA combinations, from the
 * airspyone_host tables, BSD-3-Clause) or manual LNA 0-14, mixer 0-15, VGA 0-15. Bias-T is off unless enabled.
 */
public final class AirspyR2 implements IqSource {
    public static final int VID = 0x1d50, PID = 0x60a1;
    static final int RECEIVER_MODE = 1, BOARD_ID_READ = 9, VERSION_STRING_READ = 10, BOARD_PARTID_SERIALNO_READ = 11, SET_SAMPLERATE = 12,
            SET_FREQ = 13, SET_LNA_GAIN = 14, SET_MIXER_GAIN = 15, SET_VGA_GAIN = 16, SET_LNA_AGC = 17, SET_MIXER_AGC = 18, GPIO_WRITE = 21,
            GET_SAMPLERATES = 25, SET_PACKING = 26;
    static final int EP_IN = 0x81, CTRL_TIMEOUT = 500, TRANSFERS = 16;
    public static final long MIN_HZ = 24_000_000L, MAX_HZ = 1_800_000_000L;
    /** Fallback when the firmware does not answer, matching libairspy: an R2's 10 and 2.5 MS/s. */
    public static final double[] RATES = {10e6, 2.5e6};

    /** R2 reports 10 and 2.5 MS/s. Mini reports 6 and 3 MS/s. Anything else stays unnamed. */
    public enum Model { R2, MINI, UNKNOWN }

    static boolean near(double a, double b) { return Math.abs(a - b) < 1; }

    /** Which receiver a firmware sample-rate table belongs to. */
    public static Model modelOf(double[] deviceRates) {
        boolean mini = false, r2 = false;
        if (deviceRates != null) for (double r : deviceRates) {
            if (near(r, 2.5e6)) r2 = true;
            if (near(r, 3e6) || near(r, 6e6)) mini = true;
        }
        if (mini && !r2) return Model.MINI;
        if (r2) return Model.R2;
        return Model.UNKNOWN;
    }

    /**
     * Rates the user can pick, widest first. {@code airspy_get_samplerates} (the firmware GET_SAMPLERATES table) lists
     * the Mini's 6 and 3 MS/s only; 10 MS/s is the Mini's alternate configuration and is selected by value, the same
     * way libairspy's {@code airspy_set_samplerate} does. An R2's table is already 10 and 2.5, so nothing is added.
     */
    public static double[] offerRates(double[] deviceRates) {
        if (deviceRates == null || deviceRates.length == 0) return RATES.clone();
        double[] v = deviceRates.clone();
        if (modelOf(deviceRates) == Model.MINI && !hasRate(v, 10e6)) {
            v = Arrays.copyOf(v, v.length + 1);
            v[v.length - 1] = 10e6;
        }
        Arrays.sort(v);
        for (int i = 0, j = v.length - 1; i < j; i++, j--) { double t = v[i]; v[i] = v[j]; v[j] = t; }
        return v;
    }

    static boolean hasRate(double[] v, double r) { for (double x : v) if (near(x, r)) return true; return false; }

    /** The saved rate when this receiver offers it, otherwise 10 MS/s if that is offered, otherwise the widest. */
    public static double chooseRate(double saved, double[] offered) {
        double[] o = offered == null || offered.length == 0 ? RATES : offered;
        for (double r : o) if (near(r, saved)) return r;
        for (double r : o) if (near(r, 10e6)) return 10e6;
        double best = o[0];
        for (double r : o) if (r > best) best = r;
        return best;
    }

    /**
     * wIndex for SET_SAMPLERATE. A rate in the firmware table is its index (libairspy {@code airspy_set_samplerate}).
     * The Mini's 10 MS/s is not in that table; it is sent by value as IQ rate × 2 / 1000, which is what
     * {@code airspy_set_samplerate} sends for an IQ rate the table does not list. -1 if the receiver cannot use it.
     */
    public static int sampleRateCommand(double complexRate, double[] deviceRates) {
        double[] dev = deviceRates == null || deviceRates.length == 0 ? RATES : deviceRates;
        for (int k = 0; k < dev.length; k++) if (near(dev[k], complexRate)) return k;
        if (modelOf(dev) == Model.MINI && near(complexRate, 10e6)) return (int) Math.round(complexRate * 2 / 1000.0);
        return -1;
    }

    /** Usable span, both sides, from the half-band converter (flat to 0.44 × the complex rate, minus one channel). */
    public static double coverageHz(double rate) { return 2 * (rate * 0.44 - 6_250); }

    public static String rateLabel(double rate) {
        double m = rate / 1e6;
        return Math.abs(m - Math.rint(m)) < 1e-6 ? String.format(Locale.ROOT, "%.0f MS/s", m) : String.format(Locale.ROOT, "%.1f MS/s", m);
    }

    public static String coverageText(double rate) { return String.format(Locale.ROOT, "about %.1f MHz", coverageHz(rate) / 1e6); }

    public static String rateTip(double[] offered) {
        double[] o = offered == null || offered.length == 0 ? RATES : offered;
        StringJoiner j = new StringJoiner("; ");
        for (double r : o) j.add(rateLabel(r) + " covers " + coverageText(r) + (near(r, 10e6) ? " (needs USB 2.0 at 40 MB/s)" : " and uses less CPU"));
        return j.toString();
    }

    public static String describeFound(List<Info> list) {
        int mini = 0, r2 = 0, other = 0;
        if (list != null) for (Info i : list) {
            if (i.model == Model.MINI) mini++;
            else if (i.model == Model.R2) r2++;
            else other++;
        }
        List<String> p = new ArrayList<>();
        if (r2 > 0) p.add(r2 + " Airspy R2");
        if (mini > 0) p.add(mini + " Airspy Mini");
        if (other > 0) p.add(other + " Airspy");
        return String.join(" + ", p);
    }

    // Gain presets (index 0 = least gain), airspyone_host libairspy/src/airspy.c, BSD-3-Clause (see licenses/THIRD-PARTY.txt)
    static final int[] LIN_VGA = {13, 12, 11, 11, 11, 11, 11, 10, 10, 10, 10, 10, 10, 10, 10, 10, 9, 8, 7, 6, 5, 4};
    static final int[] LIN_MIX = {12, 12, 11, 9, 8, 7, 6, 6, 5, 0, 0, 1, 0, 0, 2, 2, 1, 1, 1, 1, 0, 0};
    static final int[] LIN_LNA = {14, 14, 14, 13, 12, 10, 9, 9, 8, 9, 8, 6, 5, 3, 1, 0, 0, 0, 0, 0, 0, 0};
    static final int[] SENS_VGA = {13, 12, 11, 10, 9, 8, 7, 6, 5, 5, 5, 5, 5, 4, 4, 4, 4, 4, 4, 4, 4, 4};
    static final int[] SENS_MIX = {12, 12, 12, 12, 11, 10, 10, 9, 9, 8, 7, 4, 4, 4, 3, 2, 2, 1, 0, 0, 0, 0};
    static final int[] SENS_LNA = {14, 14, 14, 14, 14, 14, 14, 14, 14, 13, 12, 12, 9, 9, 8, 7, 6, 5, 3, 2, 1, 0};

    /** A gain setting: "lin:N" / "sens:N" (N = 0..21, higher = more gain) or "man:LNA,MIX,VGA". */
    public static final class Gain {
        public final String mode; public final int value, lna, mixer, vga;
        Gain(String mode, int value, int lna, int mixer, int vga) { this.mode = mode; this.value = value; this.lna = lna; this.mixer = mixer; this.vga = vga; }
        public static Gain parse(String s) {
            try {
                String[] p = s.trim().toLowerCase(Locale.ROOT).split(":");
                if (p[0].equals("man")) {
                    String[] v = p[1].split(",");
                    return manual(Integer.parseInt(v[0].trim()), Integer.parseInt(v[1].trim()), Integer.parseInt(v[2].trim()));
                }
                return preset(p[0].startsWith("s"), Integer.parseInt(p[1].trim()));
            } catch (Exception e) { return preset(false, 16); }
        }
        public static Gain preset(boolean sensitivity, int v) {
            v = Math.max(0, Math.min(21, v));
            int i = 21 - v;   // tables run from most to least gain
            return sensitivity ? new Gain("sens", v, SENS_LNA[i], SENS_MIX[i], SENS_VGA[i]) : new Gain("lin", v, LIN_LNA[i], LIN_MIX[i], LIN_VGA[i]);
        }
        public static Gain manual(int lna, int mix, int vga) {
            return new Gain("man", -1, Math.max(0, Math.min(14, lna)), Math.max(0, Math.min(15, mix)), Math.max(0, Math.min(15, vga)));
        }
        @Override public String toString() { return mode.equals("man") ? "man:" + lna + "," + mixer + "," + vga : mode + ":" + value; }
        public String describe() {
            String d = "LNA " + lna + ", mixer " + mixer + ", VGA " + vga;
            return mode.equals("man") ? "manual (" + d + ")" : (mode.equals("sens") ? "sensitivity " : "linearity ") + value + " (" + d + ")";
        }
    }

    // ------------------------------------------------------------------ enumeration
    public static final class Info {
        public int index, bus, address;
        public String serial = "", note = "";
        public boolean openable;
        /** R2 until the firmware answers. A device we could not ask stays labelled R2, as before. */
        public Model model = Model.R2;
        /** Firmware order (SET_SAMPLERATE indexes). Empty until the device, or an earlier look, answered. */
        public double[] deviceRates = {};
        /** Rates the user can pick, widest first. Empty until known. */
        public double[] rates = {};
        public String key() { return serial.isBlank() ? "airspy-idx" + index : "airspy-" + serial; }
        public String product() {
            if (model == Model.MINI) return "Airspy Mini";
            if (model == Model.UNKNOWN) return "Airspy";
            return "Airspy R2";
        }
        /** Known rates, or the R2 pair when this receiver has not answered yet. */
        public double[] rateChoices() { return rates.length > 0 ? rates.clone() : RATES.clone(); }
        public String label() { return product() + (serial.isBlank() ? " #" + index : "  SN " + serial) + (note.isBlank() ? "" : "  (" + note + ")"); }
        public String toString() { return label(); }
    }

    /** Last serial seen per USB bus/address: the device's serial can only be read while nobody else has it open. */
    static final Map<String, String> knownSerial = new HashMap<>();
    /** Sample-rate table last read for a serial or a bus:address, so a busy receiver keeps its name. */
    static final Map<String, double[]> knownRates = new HashMap<>();

    static void applyDeviceRates(Info info, double[] deviceRates) {
        info.deviceRates = deviceRates.clone();
        info.rates = offerRates(deviceRates);
        info.model = modelOf(deviceRates);
    }

    static void rememberRates(Info info) {
        if (info.deviceRates.length == 0) return;
        synchronized (knownSerial) {
            if (!info.serial.isBlank()) knownRates.put("sn:" + info.serial, info.deviceRates.clone());
            knownRates.put(info.bus + ":" + info.address, info.deviceRates.clone());
        }
    }

    static double[] cachedRates(Info info) {
        synchronized (knownSerial) {
            double[] r = knownRates.get(info.bus + ":" + info.address);
            if (r == null && !info.serial.isBlank()) r = knownRates.get("sn:" + info.serial);
            return r == null ? null : r.clone();
        }
    }

    public static List<Info> list() {
        LibUsb u = LibUsb.get();
        List<Info> out = new ArrayList<>();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment ctxp = a.allocate(ValueLayout.ADDRESS);
            int r = u.call(u.init, ctxp);
            if (r != 0) throw new IllegalStateException("libusb_init failed: " + u.error(r));
            MemorySegment ctx = ctxp.get(ValueLayout.ADDRESS, 0);
            try {
                MemorySegment listp = a.allocate(ValueLayout.ADDRESS);
                long n = (long) u.getDeviceList.invoke(ctx, listp);
                if (n < 0) throw new IllegalStateException("libusb_get_device_list failed: " + u.error((int) n));
                MemorySegment list = listp.get(ValueLayout.ADDRESS, 0).reinterpret(8 * (n + 1));
                MemorySegment desc = a.allocate(18, 8);
                int idx = 0;
                for (long i = 0; i < n; i++) {
                    MemorySegment dev = list.getAtIndex(ValueLayout.ADDRESS, i);
                    if (u.call(u.getDeviceDescriptor, dev, desc) != 0) continue;
                    int vid = desc.get(ValueLayout.JAVA_SHORT_UNALIGNED, 8) & 0xffff, pid = desc.get(ValueLayout.JAVA_SHORT_UNALIGNED, 10) & 0xffff;
                    if (vid != VID || pid != PID) continue;
                    Info info = new Info();
                    info.index = idx++;
                    info.bus = u.call(u.getBusNumber, dev); info.address = u.call(u.getDeviceAddress, dev);
                    int iSerial = desc.get(ValueLayout.JAVA_BYTE, 16) & 0xff;
                    MemorySegment hp = a.allocate(ValueLayout.ADDRESS);
                    int o = u.call(u.open, dev, hp);
                    String where = info.bus + ":" + info.address;
                    if (o == 0) {
                        MemorySegment h = hp.get(ValueLayout.ADDRESS, 0);
                        info.openable = true;
                        if (iSerial > 0) {
                            MemorySegment s = a.allocate(128);
                            int len = u.call(u.getStringDescriptorAscii, h, (byte) iSerial, s, 128);
                            if (len > 0) info.serial = serialFrom(s.getString(0));
                        }
                        try {
                            double[] rates = readSampleRates(u, h);
                            if (rates == null) {
                                if (!LibUsb.win() && u.call(u.kernelDriverActive, h, 0) == 1) u.call(u.detachKernelDriver, h, 0);
                                int c = u.call(u.setConfiguration, h, 1);
                                if (c == 0 || c == LibUsb.ERROR_BUSY) {
                                    int cl = u.call(u.claimInterface, h, 0);
                                    if (cl == 0) { rates = readSampleRates(u, h); u.call(u.releaseInterface, h, 0); }
                                }
                            }
                            if (rates != null) { applyDeviceRates(info, rates); rememberRates(info); }
                        } catch (RuntimeException ignored) {}
                        u.callV(u.close, h);
                        synchronized (knownSerial) { if (!info.serial.isBlank()) knownSerial.put(where, info.serial); }
                    } else {
                        synchronized (knownSerial) { info.serial = knownSerial.getOrDefault(where, ""); }
                        double[] cached = cachedRates(info);
                        if (cached != null) applyDeviceRates(info, cached);
                        info.note = o == LibUsb.ERROR_ACCESS ? "no permission - udev rules" : o == LibUsb.ERROR_NOT_SUPPORTED || o == LibUsb.ERROR_NOT_FOUND
                                ? (LibUsb.win() ? "needs the WinUSB driver - Zadig" : "no driver") : o == LibUsb.ERROR_BUSY ? "in use" : "cannot open: " + u.error(o);
                    }
                    out.add(info);
                }
                u.callV(u.freeDeviceList, listp.get(ValueLayout.ADDRESS, 0), 1);
            } finally { u.callV(u.exit, ctx); }
        } catch (IllegalStateException e) { throw e; }
        catch (Throwable t) { throw new IllegalStateException("Listing Airspy receivers failed: " + t, t); }
        return out;
    }

    /** "AIRSPY SN:26A464DC2B5A7C9F" -> "26A464DC2B5A7C9F". */
    static String serialFrom(String s) {
        s = s.trim();
        int c = s.lastIndexOf(':');
        return (c >= 0 ? s.substring(c + 1) : s).trim().toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ device
    final LibUsb u;
    final Info info;
    final Arena arena = Arena.ofShared();
    MemorySegment ctx = MemorySegment.NULL, handle = MemorySegment.NULL;
    double rate = 10e6;
    long center = 417_200_000L;
    Gain gain = Gain.preset(false, 16);
    boolean biasTee, packing;
    public volatile String firmware = "", boardId = "", partSerial = "", usbMode = "-";
    public volatile long transfersDone;
    /** Firmware order. R2's 10 then 2.5 until {@link #readSampleRates} replaces it. */
    double[] deviceRates = RATES.clone();
    AirspyStream stream;
    final MemorySegment[] transfers = new MemorySegment[TRANSFERS];
    volatile int outstanding;
    volatile boolean run;
    volatile String error;
    Thread usbThread;
    MemorySegment callbackStub;

    public AirspyR2(Info info) {
        this.info = info; this.u = LibUsb.get();
        if (info != null && info.deviceRates.length > 0) this.deviceRates = info.deviceRates.clone();
    }

    public Info info() { return info; }
    public AirspyStream stream() { return stream; }
    @Override public AirspyStream usbStream() { return stream; }
    public Gain gain() { return gain; }
    public boolean biasTee() { return biasTee; }
    public boolean packing() { return packing; }

    public synchronized void open() {
        MemorySegment ctxp = arena.allocate(ValueLayout.ADDRESS);
        int r = u.call(u.init, ctxp);
        if (r != 0) throw new IllegalStateException("libusb_init failed: " + u.error(r));
        ctx = ctxp.get(ValueLayout.ADDRESS, 0);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment listp = a.allocate(ValueLayout.ADDRESS);
            long n = (long) u.getDeviceList.invoke(ctx, listp);
            MemorySegment list = listp.get(ValueLayout.ADDRESS, 0).reinterpret(8 * (Math.max(0, n) + 1));
            MemorySegment desc = a.allocate(18, 8);
            int idx = 0, lastErr = LibUsb.ERROR_NOT_FOUND;
            for (long i = 0; i < n && handle.address() == 0; i++) {
                MemorySegment dev = list.getAtIndex(ValueLayout.ADDRESS, i);
                if (u.call(u.getDeviceDescriptor, dev, desc) != 0) continue;
                if ((desc.get(ValueLayout.JAVA_SHORT_UNALIGNED, 8) & 0xffff) != VID || (desc.get(ValueLayout.JAVA_SHORT_UNALIGNED, 10) & 0xffff) != PID) continue;
                int myIdx = idx++;
                MemorySegment hp = a.allocate(ValueLayout.ADDRESS);
                int o = u.call(u.open, dev, hp);
                if (o != 0) { lastErr = o; continue; }
                MemorySegment h = hp.get(ValueLayout.ADDRESS, 0);
                String sn = "";
                int iSerial = desc.get(ValueLayout.JAVA_BYTE, 16) & 0xff;
                if (iSerial > 0) { MemorySegment s = a.allocate(128); if (u.call(u.getStringDescriptorAscii, h, (byte) iSerial, s, 128) > 0) sn = serialFrom(s.getString(0)); }
                boolean match = info.serial.isBlank() ? myIdx == info.index : info.serial.equalsIgnoreCase(sn);
                if (!match) { u.callV(u.close, h); continue; }
                if (!LibUsb.win() && u.call(u.kernelDriverActive, h, 0) == 1) u.call(u.detachKernelDriver, h, 0);
                int c = u.call(u.setConfiguration, h, 1);
                if (c != 0 && c != LibUsb.ERROR_BUSY) { u.callV(u.close, h); lastErr = c; continue; }
                c = u.call(u.claimInterface, h, 0);
                if (c != 0) { u.callV(u.close, h); lastErr = c; continue; }
                handle = h;
            }
            u.callV(u.freeDeviceList, listp.get(ValueLayout.ADDRESS, 0), 1);
            if (handle.address() == 0) {
                u.callV(u.exit, ctx); ctx = MemorySegment.NULL;
                throw new IllegalStateException("Cannot open " + info.label() + ": " + u.error(lastErr) + (lastErr == LibUsb.ERROR_ACCESS
                        ? " - install the udev rules (Setup & Help) and replug." : lastErr == LibUsb.ERROR_NOT_SUPPORTED || lastErr == LibUsb.ERROR_NOT_FOUND
                        ? (LibUsb.win() ? " - install the WinUSB driver for the Airspy with Zadig (Setup & Help)." : "") : lastErr == LibUsb.ERROR_BUSY ? " - another program is using it." : ""));
            }
        } catch (IllegalStateException e) { throw e; }
        catch (Throwable t) { throw new IllegalStateException("Opening the Airspy failed: " + t, t); }
        readInfo();
        receiverMode(0);
        setBiasTee(false);   // always start with antenna power off
    }

    void readInfo() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment b = a.allocate(256);
            if (u.control(handle, true, BOARD_ID_READ, 0, 0, b, 1, CTRL_TIMEOUT) == 1) { int id = b.get(ValueLayout.JAVA_BYTE, 0) & 0xff; boardId = id == 0 ? "AIRSPY" : "board id " + id; }
            int n = u.control(handle, true, VERSION_STRING_READ, 0, 0, b, 127, CTRL_TIMEOUT);
            if (n > 0) { b.set(ValueLayout.JAVA_BYTE, Math.min(n, 127), (byte) 0); firmware = b.getString(0).trim(); }
            if (u.control(handle, true, BOARD_PARTID_SERIALNO_READ, 0, 0, b, 24, CTRL_TIMEOUT) == 24) {
                long s2 = b.get(ValueLayout.JAVA_INT_UNALIGNED, 16) & 0xffffffffL, s3 = b.get(ValueLayout.JAVA_INT_UNALIGNED, 20) & 0xffffffffL;
                partSerial = String.format("%08X%08X", s2, s3);
            }
            double[] dev = readSampleRates(u, handle);
            if (dev != null) { deviceRates = dev; applyDeviceRates(info, dev); rememberRates(info); }
        }
    }

    /**
     * The firmware half of libairspy {@code airspy_get_samplerates}: index 0 returns one little-endian count, then
     * index = count returns that many uint32 complex rates. A short or nonsense answer is ignored (the caller keeps
     * the previous table, which starts as the R2 pair).
     */
    static double[] readSampleRates(LibUsb u, MemorySegment handle) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment b = a.allocate(16 * 4L);
            int n = u.control(handle, true, GET_SAMPLERATES, 0, 0, b, 4, CTRL_TIMEOUT);
            if (n < 1) return null;
            int cnt = b.get(ValueLayout.JAVA_INT_UNALIGNED, 0);
            if (cnt <= 0 || cnt > 16) return null;
            b.fill((byte) 0);
            int got = u.control(handle, true, GET_SAMPLERATES, 0, cnt, b, cnt * 4, CTRL_TIMEOUT);
            if (got < cnt * 4) return null;
            double[] r = new double[cnt];
            for (int k = 0; k < cnt; k++) {
                long hz = b.get(ValueLayout.JAVA_INT_UNALIGNED, 4L * k) & 0xffffffffL;
                if (hz < 1_000_000L || hz > 40_000_000L) return null;
                r[k] = hz;
            }
            return r;
        } catch (RuntimeException e) { return null; }
    }

    /** Rates the firmware listed, in firmware order (not the extra Mini 10 MS/s). */
    public double[] supportedRates() { return deviceRates.clone(); }

    /** Rates this open receiver can actually be set to, widest first. */
    public double[] offeredRates() { return offerRates(deviceRates); }

    int ctrlIn1(int req, int value, int index) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment b = a.allocate(8);
            int r = u.control(handle, true, req, value, index, b, 1, CTRL_TIMEOUT);
            if (r < 1) throw new IllegalStateException("Airspy request " + req + " failed: " + u.error(r));
            return b.get(ValueLayout.JAVA_BYTE, 0) & 0xff;
        }
    }

    void ctrlOut(int req, int value, int index) {
        int r = u.control(handle, false, req, value, index, MemorySegment.NULL, 0, CTRL_TIMEOUT);
        if (r < 0) throw new IllegalStateException("Airspy request " + req + " failed: " + u.error(r));
    }

    void receiverMode(int m) { ctrlOut(RECEIVER_MODE, m, 0); }

    /** Sample rate (complex Hz), gain, bias-T and USB packing. Call before {@link #start}. */
    public synchronized void configure(double complexRate, Gain g, boolean bias, boolean pack) {
        int cmd = sampleRateCommand(complexRate, deviceRates);
        if (cmd < 0) throw new IllegalStateException(String.format(Locale.ROOT, "This Airspy does not offer %s (it has %s)", rateLabel(complexRate), rateList()));
        rate = complexRate;
        u.call(u.clearHalt, handle, (byte) EP_IN);
        ctrlIn1(SET_SAMPLERATE, 0, cmd);
        ctrlIn1(SET_PACKING, 0, pack ? 1 : 0);
        packing = pack;
        setGain(g);
        setBiasTee(bias);
    }

    String rateList() { StringJoiner j = new StringJoiner(", "); for (double r : offeredRates()) j.add(rateLabel(r)); return j.toString(); }

    public synchronized void setGain(Gain g) {
        gain = g;
        ctrlIn1(SET_MIXER_AGC, 0, 0);
        ctrlIn1(SET_LNA_AGC, 0, 0);
        ctrlIn1(SET_VGA_GAIN, 0, g.vga);
        ctrlIn1(SET_MIXER_GAIN, 0, g.mixer);
        ctrlIn1(SET_LNA_GAIN, 0, g.lna);
    }

    public synchronized void setBiasTee(boolean on) {
        biasTee = on;
        ctrlOut(GPIO_WRITE, on ? 1 : 0, (1 << 5) | 13);   // port 1, pin 13
    }

    @Override public synchronized boolean tune(long hz) {
        if (hz < MIN_HZ || hz > MAX_HZ || handle.address() == 0) return false;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment b = a.allocate(4);
            b.set(ValueLayout.JAVA_INT_UNALIGNED, 0, (int) hz);   // little-endian on x86-64 / arm64
            int r = u.control(handle, false, SET_FREQ, 0, 0, b, 4, CTRL_TIMEOUT);
            if (r < 4) return false;
        }
        center = hz;
        AirspyStream s = stream; if (s != null) s.retuned();
        return true;
    }

    @Override public double sampleRate() { return rate; }
    @Override public long centerHz() { return center; }
    @Override public String name() { return info.label(); }
    @Override public boolean running() { return run; }
    @Override public String error() { String e = error; if (e == null && stream != null) e = stream.error(); return e; }

    @Override public synchronized void start(Listener l) {
        if (handle.address() == 0) throw new IllegalStateException("Airspy not open");
        int queueMs = Integer.getInteger("trunkdeck.airspyQueueMs", 600);
        stream = new AirspyStream(rate, packing, queueMs);
        stream.setInvert(Boolean.getBoolean("trunkdeck.airspyInvert"));
        stream.start(l, "airspy-dsp");
        receiverMode(0);
        u.call(u.clearHalt, handle, (byte) EP_IN);
        receiverMode(1);
        // callback: void cb(struct libusb_transfer *)
        try {
            MethodHandle cb = MethodHandles.lookup().bind(this, "onTransfer", MethodType.methodType(void.class, MemorySegment.class));
            callbackStub = Linker.nativeLinker().upcallStub(cb, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), arena);
        } catch (Exception e) { throw new IllegalStateException(e); }
        run = true; outstanding = 0;
        for (int k = 0; k < TRANSFERS; k++) {
            MemorySegment t = u.callP(u.allocTransfer, 0).reinterpret(LibUsb.T_SIZE);
            MemorySegment buf = arena.allocate(stream.blockBytes, 512);
            t.set(ValueLayout.ADDRESS, LibUsb.T_DEV_HANDLE, handle);
            t.set(ValueLayout.JAVA_BYTE, LibUsb.T_FLAGS, (byte) 0);
            t.set(ValueLayout.JAVA_BYTE, LibUsb.T_ENDPOINT, (byte) EP_IN);
            t.set(ValueLayout.JAVA_BYTE, LibUsb.T_TYPE, (byte) LibUsb.TRANSFER_TYPE_BULK);
            t.set(ValueLayout.JAVA_INT, LibUsb.T_TIMEOUT, 0);
            t.set(ValueLayout.JAVA_INT, LibUsb.T_LENGTH, stream.blockBytes);
            t.set(ValueLayout.ADDRESS, LibUsb.T_CALLBACK, callbackStub);
            t.set(ValueLayout.ADDRESS, LibUsb.T_USER_DATA, MemorySegment.NULL);
            t.set(ValueLayout.ADDRESS, LibUsb.T_BUFFER, buf);
            t.set(ValueLayout.JAVA_INT, LibUsb.T_NUM_ISO, 0);
            transfers[k] = t;
        }
        for (MemorySegment t : transfers) {
            int r = u.call(u.submitTransfer, t);
            if (r != 0) { error = "USB transfer submit failed: " + u.error(r); break; }
            outstanding++;
        }
        usbMode = "libusb async " + TRANSFERS + " x " + stream.blockBytes / 1024 + " KB" + (packing ? " (packed 12-bit)" : "");
        usbThread = new Thread(this::eventLoop, "airspy-usb");
        usbThread.setDaemon(true);
        usbThread.setPriority(Thread.MAX_PRIORITY);
        usbThread.start();
    }

    void eventLoop() {
        MemorySegment tv = arena.allocate(u.timevalSize, 8);
        if (u.timevalSize == 16) { tv.set(ValueLayout.JAVA_LONG, 0, 0); tv.set(ValueLayout.JAVA_LONG, 8, 200_000); }
        else { tv.set(ValueLayout.JAVA_INT, 0, 0); tv.set(ValueLayout.JAVA_INT, 4, 200_000); }
        long stopAt = 0;
        try {
            while (true) {
                if (!run) {
                    if (stopAt == 0) { stopAt = System.currentTimeMillis(); for (MemorySegment t : transfers) if (t != null) u.call(u.cancelTransfer, t); }
                    if (outstanding <= 0 || System.currentTimeMillis() - stopAt > 2000) break;
                }
                int r = u.call(u.handleEventsTimeoutCompleted, ctx, tv, MemorySegment.NULL);
                if (r < 0 && r != LibUsb.ERROR_INTERRUPTED && r != LibUsb.ERROR_TIMEOUT) { if (run) error = "USB event handling failed: " + u.error(r); run = false; }
            }
        } catch (Throwable t) { if (run) error = "USB thread: " + t; run = false; }
    }

    /** libusb transfer callback (on the USB thread). Must never throw. */
    void onTransfer(MemorySegment t0) {
        try {
            MemorySegment t = t0.reinterpret(LibUsb.T_SIZE);
            int status = t.get(ValueLayout.JAVA_INT, LibUsb.T_STATUS), len = t.get(ValueLayout.JAVA_INT, LibUsb.T_ACTUAL_LENGTH);
            transfersDone++;
            if (status == LibUsb.TRANSFER_COMPLETED) {
                if (len > 0) stream.offer(t.get(ValueLayout.ADDRESS, LibUsb.T_BUFFER).reinterpret(len), len);
            } else if (status == LibUsb.TRANSFER_CANCELLED) { outstanding--; return; }
            else if (status == LibUsb.TRANSFER_NO_DEVICE) { outstanding--; error = "Airspy unplugged (USB device gone)"; run = false; stream.fail(error); return; }
            else { stream.usbErrors++; stream.gap = true; }
            if (!run) { outstanding--; return; }
            int r = u.call(u.submitTransfer, t);
            if (r != 0) { outstanding--; stream.usbErrors++; if (run) { error = "USB transfer resubmit failed: " + u.error(r); run = false; stream.fail(error); } }
        } catch (Throwable ignored) {}
    }

    @Override public void stop() {
        run = false;
        if (usbThread != null) try { usbThread.join(3000); } catch (InterruptedException ignored) {}
        usbThread = null;
        synchronized (this) {
            if (handle.address() != 0) {
                try { receiverMode(0); } catch (Throwable ignored) {}
                try { if (biasTee) setBiasTee(false); } catch (Throwable ignored) {}
            }
        }
        if (stream != null) stream.stop();
        synchronized (this) {
            if (outstanding <= 0) for (int k = 0; k < TRANSFERS; k++) if (transfers[k] != null) { u.callV(u.freeTransfer, transfers[k]); transfers[k] = null; }
            if (handle.address() != 0) { u.call(u.releaseInterface, handle, 0); u.callV(u.close, handle); handle = MemorySegment.NULL; }
            if (ctx.address() != 0) { u.callV(u.exit, ctx); ctx = MemorySegment.NULL; }
        }
        // the buffers / callback stub stay allocated until the arena is closed; only close it when nothing can call back
        if (outstanding <= 0) try { arena.close(); } catch (Throwable ignored) {}
    }
}
