package p25monitor.sdr;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.*;
import java.util.*;

/**
 * Minimal libusb-1.0 binding (Java Foreign Function &amp; Memory API) for TrunkDeck's own USB drivers (the Airspy R2).
 * Windows: the bundled app/native/windows-x64/libusb-1.0.dll (official libusb 1.0.27 release; the receiver needs the
 * WinUSB driver, installed with Zadig). Linux: the system libusb-1.0.so.0 (package libusb1 on Fedora, installed by
 * default). libusb is LGPL-2.1: it is loaded at run time as a separate, replaceable library.
 */
public final class LibUsb {
    public static final int ENDPOINT_IN = 0x80, REQUEST_TYPE_VENDOR = 0x40, RECIPIENT_DEVICE = 0x00;
    public static final int TRANSFER_COMPLETED = 0, TRANSFER_ERROR = 1, TRANSFER_TIMED_OUT = 2, TRANSFER_CANCELLED = 3, TRANSFER_STALL = 4,
            TRANSFER_NO_DEVICE = 5, TRANSFER_OVERFLOW = 6;
    public static final int ERROR_ACCESS = -3, ERROR_NO_DEVICE = -4, ERROR_NOT_FOUND = -5, ERROR_BUSY = -6, ERROR_TIMEOUT = -7,
            ERROR_INTERRUPTED = -10, ERROR_NOT_SUPPORTED = -12;
    /** struct libusb_transfer field offsets (identical on 64-bit Linux and Windows). */
    public static final long T_DEV_HANDLE = 0, T_FLAGS = 8, T_ENDPOINT = 9, T_TYPE = 10, T_TIMEOUT = 12, T_STATUS = 16, T_LENGTH = 20,
            T_ACTUAL_LENGTH = 24, T_CALLBACK = 32, T_USER_DATA = 40, T_BUFFER = 48, T_NUM_ISO = 56, T_SIZE = 64;
    public static final int TRANSFER_TYPE_BULK = 2;

    final MethodHandle init, exit, getDeviceList, freeDeviceList, getDeviceDescriptor, open, close, getStringDescriptorAscii,
            kernelDriverActive, detachKernelDriver, setConfiguration, claimInterface, releaseInterface, controlTransfer, clearHalt,
            allocTransfer, freeTransfer, submitTransfer, cancelTransfer, handleEventsTimeoutCompleted, errorName, getBusNumber,
            getDeviceAddress, getVersion;
    public final String from;
    /** sizeof(struct timeval): two C longs (8 bytes each on Linux, 4 on Windows). */
    public final long timevalSize;

    private static LibUsb lib;
    private static String loadError;

    LibUsb(SymbolLookup l, String from) {
        this.from = from;
        timevalSize = win() ? 8 : 16;
        Linker k = Linker.nativeLinker();
        ValueLayout I = ValueLayout.JAVA_INT; AddressLayout P = ValueLayout.ADDRESS; ValueLayout B = ValueLayout.JAVA_BYTE, S = ValueLayout.JAVA_SHORT;
        ValueLayout SZ = ValueLayout.JAVA_LONG;   // ssize_t: 64-bit on both supported platforms
        init = h(k, l, "libusb_init", FunctionDescriptor.of(I, P));
        exit = h(k, l, "libusb_exit", FunctionDescriptor.ofVoid(P));
        getDeviceList = h(k, l, "libusb_get_device_list", FunctionDescriptor.of(SZ, P, P));
        freeDeviceList = h(k, l, "libusb_free_device_list", FunctionDescriptor.ofVoid(P, I));
        getDeviceDescriptor = h(k, l, "libusb_get_device_descriptor", FunctionDescriptor.of(I, P, P));
        open = h(k, l, "libusb_open", FunctionDescriptor.of(I, P, P));
        close = h(k, l, "libusb_close", FunctionDescriptor.ofVoid(P));
        getStringDescriptorAscii = h(k, l, "libusb_get_string_descriptor_ascii", FunctionDescriptor.of(I, P, B, P, I));
        kernelDriverActive = h(k, l, "libusb_kernel_driver_active", FunctionDescriptor.of(I, P, I));
        detachKernelDriver = h(k, l, "libusb_detach_kernel_driver", FunctionDescriptor.of(I, P, I));
        setConfiguration = h(k, l, "libusb_set_configuration", FunctionDescriptor.of(I, P, I));
        claimInterface = h(k, l, "libusb_claim_interface", FunctionDescriptor.of(I, P, I));
        releaseInterface = h(k, l, "libusb_release_interface", FunctionDescriptor.of(I, P, I));
        controlTransfer = h(k, l, "libusb_control_transfer", FunctionDescriptor.of(I, P, B, B, S, S, P, S, I));
        clearHalt = h(k, l, "libusb_clear_halt", FunctionDescriptor.of(I, P, B));
        allocTransfer = h(k, l, "libusb_alloc_transfer", FunctionDescriptor.of(P, I));
        freeTransfer = h(k, l, "libusb_free_transfer", FunctionDescriptor.ofVoid(P));
        submitTransfer = h(k, l, "libusb_submit_transfer", FunctionDescriptor.of(I, P));
        cancelTransfer = h(k, l, "libusb_cancel_transfer", FunctionDescriptor.of(I, P));
        handleEventsTimeoutCompleted = h(k, l, "libusb_handle_events_timeout_completed", FunctionDescriptor.of(I, P, P, P));
        errorName = h(k, l, "libusb_error_name", FunctionDescriptor.of(P, I));
        getBusNumber = h(k, l, "libusb_get_bus_number", FunctionDescriptor.of(B, P));
        getDeviceAddress = h(k, l, "libusb_get_device_address", FunctionDescriptor.of(B, P));
        getVersion = l.find("libusb_get_version").map(s -> k.downcallHandle(s, FunctionDescriptor.of(P))).orElse(null);
    }

    static MethodHandle h(Linker k, SymbolLookup l, String n, FunctionDescriptor d) {
        return k.downcallHandle(l.find(n).orElseThrow(() -> new UnsatisfiedLinkError("libusb-1.0 has no " + n)), d);
    }

    static boolean win() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }

    public static synchronized LibUsb get() {
        if (lib != null) return lib;
        if (loadError != null) throw new IllegalStateException(loadError);
        List<Object> candidates = new ArrayList<>();
        String override = System.getProperty("trunkdeck.libusb");
        if (override != null) candidates.add(Paths.get(override));
        if (win()) candidates.add(RtlSdr.nativeDir().resolve("libusb-1.0.dll"));
        else {
            candidates.add("libusb-1.0.so.0");
            for (String d : new String[]{"/usr/lib64", "/usr/lib/x86_64-linux-gnu", "/usr/local/lib", "/usr/lib", "/usr/lib/aarch64-linux-gnu", "/lib64", "/lib/x86_64-linux-gnu"})
                candidates.add(Paths.get(d, "libusb-1.0.so.0"));
            candidates.add(RtlSdr.nativeDir().resolve("libusb-1.0.so.0"));
        }
        List<String> tried = new ArrayList<>();
        for (Object c : candidates) {
            try {
                SymbolLookup l;
                if (c instanceof Path p) {
                    if (!Files.exists(p)) { tried.add(p + " (missing)"); continue; }
                    l = SymbolLookup.libraryLookup(p, Arena.global());
                } else l = SymbolLookup.libraryLookup((String) c, Arena.global());
                lib = new LibUsb(l, c.toString());
                return lib;
            } catch (Throwable t) { tried.add(c + " (" + t.getMessage() + ")"); }
        }
        loadError = "Could not load libusb-1.0. " + (win() ? "The bundled libusb-1.0.dll is missing - reinstall TrunkDeck."
                : "Install it with:  sudo dnf install libusb1   (Debian/Ubuntu: sudo apt install libusb-1.0-0)") + "\nTried: " + String.join("; ", tried);
        throw new IllegalStateException(loadError);
    }

    public static boolean available() { try { get(); return true; } catch (Throwable t) { return false; } }

    /** For the Engine page: "libusb 1.0.27 (path)" or why it is missing. */
    public static String info() {
        try { LibUsb u = get(); String v = u.version(); return "libusb " + (v.isBlank() ? "" : v + " ") + "(" + u.from + ")"; }
        catch (Throwable t) { return "libusb not available - " + t.getMessage().split("\n")[0]; }
    }

    public String version() {
        if (getVersion == null) return "";
        try {
            MemorySegment v = ((MemorySegment) getVersion.invoke()).reinterpret(16);
            return (v.get(ValueLayout.JAVA_SHORT, 0) & 0xffff) + "." + (v.get(ValueLayout.JAVA_SHORT, 2) & 0xffff) + "." + (v.get(ValueLayout.JAVA_SHORT, 4) & 0xffff);
        } catch (Throwable t) { return ""; }
    }

    public String error(int code) {
        try { MemorySegment p = (MemorySegment) errorName.invoke(code); return p.address() == 0 ? String.valueOf(code) : p.reinterpret(64).getString(0); }
        catch (Throwable t) { return String.valueOf(code); }
    }

    // thin wrappers (checked exceptions turned into runtime ones)
    public int call(MethodHandle m, Object... a) { try { Object r = m.invokeWithArguments(a); return r instanceof Integer i ? i : r instanceof Long l ? (int) (long) l : r instanceof Byte b ? b & 0xff : 0; } catch (Throwable t) { throw new IllegalStateException(t); } }
    public MemorySegment callP(MethodHandle m, Object... a) { try { return (MemorySegment) m.invokeWithArguments(a); } catch (Throwable t) { throw new IllegalStateException(t); } }
    public void callV(MethodHandle m, Object... a) { try { m.invokeWithArguments(a); } catch (Throwable t) { throw new IllegalStateException(t); } }

    /** Vendor control transfer; returns bytes transferred or a negative libusb error. */
    public int control(MemorySegment handle, boolean in, int request, int value, int index, MemorySegment data, int length, int timeoutMs) {
        int type = (in ? ENDPOINT_IN : 0) | REQUEST_TYPE_VENDOR | RECIPIENT_DEVICE;
        try { return (int) controlTransfer.invoke(handle, (byte) type, (byte) request, (short) value, (short) index, data, (short) length, timeoutMs); }
        catch (Throwable t) { throw new IllegalStateException(t); }
    }
}
