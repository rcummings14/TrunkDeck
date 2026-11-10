package p25monitor.dmr;

/**
 * Collects dibits and reports a burst when the 48-bit sync word is found in the middle of a 264-bit window.
 * Up to {@link #MAX_ERRORS} wrong sync bits are still accepted.
 */
public final class DmrFramer {
    public interface Sink { void burst(DmrBurst burst, DmrCach cach); }

    public static final int MAX_ERRORS = 4;
    static final int DIBITS = DmrBurst.LENGTH / 2;

    private final Sink sink;
    private final int[] hist = new int[DIBITS + 12];
    private int n, mute;

    public DmrFramer(Sink sink) { this.sink = sink; }

    public void reset() { n = 0; mute = 0; }

    public void dibit(int dibit) {
        hist[n % hist.length] = dibit & 3;
        n++;
        if (mute > 0) { mute--; return; }
        if (n < DIBITS) return;
        // Sync sits in the middle: 54 dibits, 24 sync dibits, 54 dibits.
        long sync = 0;
        int mid = n - DIBITS + 54;
        for (int i = 0; i < 24; i++) {
            int d = hist[(mid + i) % hist.length];
            sync = (sync << 2) | d;
        }
        DmrSync.Kind kind = DmrSync.match(sync, MAX_ERRORS);
        if (kind == DmrSync.Kind.NONE) return;
        mute = DIBITS - 1;
        boolean[] bits = new boolean[DmrBurst.LENGTH];
        int start = n - DIBITS;
        for (int i = 0; i < DIBITS; i++) {
            int d = hist[(start + i) % hist.length];
            bits[i * 2] = ((d >> 1) & 1) == 1;
            bits[i * 2 + 1] = (d & 1) == 1;
        }
        DmrCach cach = null;
        if (n >= DIBITS + 12) {
            boolean[] cbits = new boolean[24];
            int c0 = n - DIBITS - 12;
            for (int i = 0; i < 12; i++) {
                int d = hist[(c0 + i) % hist.length];
                cbits[i * 2] = ((d >> 1) & 1) == 1;
                cbits[i * 2 + 1] = (d & 1) == 1;
            }
            cach = DmrCach.parse(cbits);
        }
        DmrBurst burst = DmrBurst.parse(bits);
        if (burst != null && burst.sync == DmrSync.Kind.NONE) {
            burst = new DmrBurst(kind, burst.colorCode, burst.dataType, burst.slotOk);
            System.arraycopy(bits, 0, burst.info, 0, 96);
        }
        if (burst != null) sink.burst(burst, cach);
    }

    /** Feed a finished 264-bit burst (tests and the demod). */
    public void bits(boolean[] bits) {
        for (int i = 0; i < bits.length; i += 2) {
            int hi = bits[i] ? 1 : 0;
            int lo = i + 1 < bits.length && bits[i + 1] ? 1 : 0;
            dibit((hi << 1) | lo);
        }
    }
}
