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
    /** Long enough to still hold the CACH in front of a burst when that burst ends. */
    private final int[] hist = new int[DIBITS + 80];
    private int n, quietUntil;
    /** Same-slot bursts still to take after a voice sync. One slot, the other slot, and two CACH fields is 288 dibits. */
    private int follow, nextEnd;

    public DmrFramer(Sink sink) { this.sink = sink; }

    public void reset() { n = 0; quietUntil = 0; follow = 0; nextEnd = 0; }

    public void dibit(int dibit) {
        hist[n % hist.length] = dibit & 3;
        n++;
        if (follow > 0 && n == nextEnd) {
            boolean[] bits = burstBits(n);
            int emb = (DmrBits.read(bits, 108, 8) << 8) | DmrBits.read(bits, 148, 8);
            int info = DmrFec.qrDecode(emb);
            boolean[] frag = new boolean[32];
            System.arraycopy(bits, 116, frag, 0, 32);
            DmrBurst burst = info < 0
                    ? DmrBurst.embeddedVoice(bits, -1, -1, null)
                    : DmrBurst.embeddedVoice(bits, (info >> 3) & 15, info & 3, frag);
            sink.burst(burst, cachBefore(n));
            follow--;
            nextEnd += 288;
        }
        if (n < quietUntil || n < DIBITS) return;
        // Sync sits in the middle: 54 dibits, 24 sync dibits, 54 dibits.
        long sync = 0;
        int mid = n - DIBITS + 54;
        for (int i = 0; i < 24; i++) {
            int d = hist[(mid + i) % hist.length];
            sync = (sync << 2) | d;
        }
        DmrSync.Kind kind = DmrSync.match(sync, MAX_ERRORS);
        if (kind == DmrSync.Kind.NONE) return;
        quietUntil = n + 60;
        boolean[] bits = burstBits(n);
        DmrBurst burst = DmrBurst.parse(bits, kind);
        if (DmrSync.voice(kind)) {
            follow = 5; // bursts B C D E F of this timeslot
            nextEnd = n + 288;
        }
        if (burst != null) sink.burst(burst, cachBefore(n));
    }

    boolean[] burstBits(int end) {
        boolean[] bits = new boolean[DmrBurst.LENGTH];
        int start = end - DIBITS;
        for (int i = 0; i < DIBITS; i++) {
            int d = hist[(start + i) % hist.length];
            bits[i * 2] = ((d >> 1) & 1) == 1;
            bits[i * 2 + 1] = (d & 1) == 1;
        }
        return bits;
    }

    DmrCach cachBefore(int end) {
        if (end < DIBITS + 12) return null;
        boolean[] cbits = new boolean[24];
        int c0 = end - DIBITS - 12;
        for (int i = 0; i < 12; i++) {
            int d = hist[(c0 + i) % hist.length];
            cbits[i * 2] = ((d >> 1) & 1) == 1;
            cbits[i * 2 + 1] = (d & 1) == 1;
        }
        return DmrCach.parse(cbits);
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
