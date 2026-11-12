package p25monitor.dmr;

/**
 * Collects dibits and reports a burst when the 48-bit sync word is found in the middle of a 264-bit window.
 * <p>While acquiring, at most {@link #ACQUIRE_ERRORS} wrong sync bits are accepted (tight enough that noise does
 * not lock, loose enough for a moderate signal). After two syncs land on the 144-dibit burst grid, the receiver
 * is locked and will take up to {@link #TRACK_ERRORS} errors, but only within {@link #BOUNDARY} dibits of that
 * grid. One missed burst is kept. A second missed grid point drops the lock and the tight rule returns.
 * An inverted pattern (every sync bit flipped) is accepted, and that polarity is kept after three inverted syncs
 * in a row until three normal ones replace it.
 */
public final class DmrFramer {
    public interface Sink { void burst(DmrBurst burst, DmrCach cach); }

    /** Kept for callers that still name the acquire limit. */
    public static final int MAX_ERRORS = 6;
    /** Bit errors allowed before the burst grid is known. Looser than a perfect sync, still under the false hits in a clean burst. */
    public static final int ACQUIRE_ERRORS = 6;
    /** Bit errors allowed once locked, and only on the burst grid. */
    /** Looser than acquire, and still tight enough that an all-zero window is not an inverted sync. */
    public static final int TRACK_ERRORS = 8;
    /** Dibits of slack around a predicted burst boundary. */
    public static final int BOUNDARY = 2;
    /** One burst plus the CACH in front of the next one. */
    public static final int BURST_GAP = 144;
    static final int DIBITS = DmrBurst.LENGTH / 2;

    private final Sink sink;
    /** Long enough to still hold the CACH in front of a burst when that burst ends. */
    private final int[] hist = new int[DIBITS + 80];
    private int n, quietUntil;
    /** Same-slot bursts still to take after a voice sync. One slot, the other slot, and two CACH fields is 288 dibits. */
    private int follow, nextEnd;
    private boolean locked, inverted, polarityHeld;
    private int streak, normalStreak, invertStreak;
    private int lastSync;
    private boolean haveSync;

    public DmrFramer(Sink sink) { this.sink = sink; }

    public void reset() {
        n = 0; quietUntil = 0; follow = 0; nextEnd = 0;
        locked = false; inverted = false; polarityHeld = false;
        streak = 0; normalStreak = 0; invertStreak = 0;
        haveSync = false;
    }

    public boolean locked() { return locked; }
    public boolean inverted() { return inverted; }

    public void dibit(int dibit) {
        hist[n % hist.length] = dibit & 3;
        n++;
        // Two burst periods with no sync: back to the tight acquire rule. One missed burst is not a loss.
        // A voice superframe is still being stepped by position, so that gap is not a loss either.
        if (locked && follow == 0 && haveSync && n > lastSync + 2L * BURST_GAP + BOUNDARY) {
            locked = false;
            streak = 0;
        }
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
        int allow = locked ? TRACK_ERRORS : ACQUIRE_ERRORS;
        DmrSync.Found hit = DmrSync.best(sync, allow, true);
        if (!hit.hit()) return;
        if (polarityHeld && hit.inverted != inverted) return;
        if (locked && haveSync && !onGrid(n - lastSync)) return;
        if (hit.inverted) { invertStreak++; normalStreak = 0; }
        else { normalStreak++; invertStreak = 0; }
        if (invertStreak >= 3) { inverted = true; polarityHeld = true; }
        if (normalStreak >= 3) { inverted = false; polarityHeld = true; }
        if (haveSync && onGrid(n - lastSync)) streak++;
        else streak = 1;
        if (streak >= 2) locked = true;
        lastSync = n;
        haveSync = true;
        quietUntil = n + 60;
        boolean[] bits = burstBits(n);
        if (hit.inverted) flip(bits);
        DmrSync.Kind kind = hit.kind;
        DmrBurst burst = DmrBurst.parse(bits, kind);
        DmrCach cach = cachBefore(n);
        if (hit.inverted && cach != null) cach = invertCach(n);
        if (DmrSync.voice(kind)) {
            follow = 5; // bursts B C D E F of this timeslot
            nextEnd = n + 288;
        }
        if (burst != null) sink.burst(burst, cach);
    }

    /** True when {@code delta} dibits is a whole number of bursts, within {@link #BOUNDARY}. */
    static boolean onGrid(int delta) {
        if (delta <= 0) return false;
        int mod = delta % BURST_GAP;
        return Math.min(mod, BURST_GAP - mod) <= BOUNDARY;
    }

    static void flip(boolean[] bits) { for (int i = 0; i < bits.length; i++) bits[i] = !bits[i]; }

    DmrCach invertCach(int end) {
        if (end < DIBITS + 12) return null;
        boolean[] cbits = new boolean[24];
        int c0 = end - DIBITS - 12;
        for (int i = 0; i < 12; i++) {
            int d = hist[(c0 + i) % hist.length] ^ 3;
            cbits[i * 2] = ((d >> 1) & 1) == 1;
            cbits[i * 2 + 1] = (d & 1) == 1;
        }
        return DmrCach.parse(cbits);
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
