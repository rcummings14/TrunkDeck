package p25monitor.dmr;

/**
 * Collects dibits and reports a burst when a 48-bit sync word is found in the middle of a 264-bit window.
 *
 * <p>Acquire and lock: while acquiring, at most {@link #ACQUIRE_ERRORS} wrong sync bits are accepted. After two syncs land
 * on the 144-dibit burst grid the receiver is locked and takes up to {@link #TRACK_ERRORS}, but only within
 * {@link #BOUNDARY} dibits of that grid. Two burst periods with no sync (and no voice burst still due) drop the lock.
 *
 * <p>Voice: a voice sync starts a superframe. Bursts B to F follow at 288-dibit steps and have no sync, so each voice
 * sync adds an entry to a short schedule. Both timeslots can be sending voice, so there are several entries at once.
 * A scheduled burst counts as signal and keeps the lock alive.
 *
 * <p>Polarity: negating a 4FSK signal flips the high bit of every dibit. Every voice sync is its data sync with those bits
 * flipped, so sync alone cannot show inversion. The slot-type field can: it is a Golay word, and a chance match is rare.
 * When the other polarity reads a data burst with a valid slot type, and the current polarity does not, that is a vote.
 * Enough votes in a short span flip the polarity, which is then applied to every dibit read from the history.
 * Voice with no data burst in view (a call joined mid-way) has no slot type to check. With a colour code set, a sync that
 * reads as data with a bad slot type is probed: its five EMB fields are read in the other polarity, and three that carry
 * the wanted colour code flip it.
 *
 * <p>Placement: each burst carries its number on the burst grid so a receiver can keep the two slots apart through a
 * missed burst, and direct-mode syncs name the slot themselves.
 */
public final class DmrFramer {
    public interface Sink { void burst(DmrBurst burst, DmrCach cach); }

    /** Kept for callers that still name the acquire limit. */
    public static final int MAX_ERRORS = 6;
    /** Bit errors allowed before the burst grid is known. Looser than a perfect sync, still under the false hits in a clean burst. */
    public static final int ACQUIRE_ERRORS = 6;
    /** Looser than acquire, and still tight enough that an all-zero window is not a sync. Used once locked, on the grid only. */
    public static final int TRACK_ERRORS = 8;
    /** Dibits of slack around a predicted burst boundary. */
    public static final int BOUNDARY = 2;
    /** One burst plus the CACH in front of the next one. */
    public static final int BURST_GAP = 144;
    static final int DIBITS = DmrBurst.LENGTH / 2;
    private static final int MAX_FOLLOW = 4;
    /** A vote older than this many dibits is forgotten. */
    private static final long VOTE_SPAN = 10L * BURST_GAP;

    private final Sink sink;
    /** Long enough to still hold the CACH in front of a burst when that burst ends. */
    private final int[] hist = new int[DIBITS + 80];
    private long n, quietUntil, lastSync, gridBase, lastVote;
    private boolean locked, inverted, haveSync;
    private int streak, epoch, votes, syncSerial, wantColor = -1;
    // Voice bursts B..F still to come, one entry per voice sync that has not finished.
    private final long[] fEnd = new long[MAX_FOLLOW];
    private final int[] fLeft = new int[MAX_FOLLOW];
    private final DmrSync.Kind[] fKind = new DmrSync.Kind[MAX_FOLLOW];
    private int fCount;
    // Voice that would only be voice in the other polarity: its EMB fields are checked there (needs a colour code).
    private final long[] pEnd = new long[2];
    private final int[] pLeft = new int[2], pHit = new int[2];
    private int pCount;
    // Best sync hit seen in the last few dibits, waiting to be accepted.
    private DmrSync.Found pend, pendTwin;
    private long pendEnd;
    /** End dibit of the last burst reported (for tools that want the raw CACH). */
    long lastEnd;
    /** Counters for a status line. */
    public long slotRejected, polarityFlips;

    public DmrFramer(Sink sink) { this.sink = sink; }

    /** Full reset: forgets polarity and the grid. */
    public void reset() {
        n = 0; quietUntil = 0; lastSync = 0; gridBase = 0; lastVote = 0;
        locked = false; inverted = false; haveSync = false;
        streak = 0; votes = 0; fCount = 0; pCount = 0; pend = null; epoch++;
    }

    /** Drop the lock and the grid but keep the polarity and the dibit count. Used when the symbol clock is stepped. */
    public void resync() {
        fCount = 0; locked = false; haveSync = false; streak = 0; pend = null; pCount = 0;
        quietUntil = n + DIBITS; epoch++;
    }

    /** Colour code the receiver wants, or -1. Only used to make a polarity vote harder to fake. */
    public void setColorCode(int colorCode) { wantColor = colorCode; }

    public boolean locked() { return locked; }
    /** True while a possible inverted voice sync is being checked. The symbol clock should not be stepped meanwhile. */
    public boolean probing() { return pCount > 0; }
    /**
     * True while bursts B to F of a voice superframe are still due. A radio's own voice has a sync only once every 360 ms,
     * so the symbol clock should not be stepped between them.
     */
    public boolean following() { return fCount > 0; }
    public boolean inverted() { return inverted; }
    /** Counts accepted sync words. A caller can watch this to learn that a sync has just landed. */
    public int syncSerial() { return syncSerial; }

    public void dibit(int dibit) {
        hist[(int) (n % hist.length)] = dibit & 3;
        n++;
        followBursts();
        // Two burst periods with no sync and nothing scheduled: back to the tight acquire rule.
        if (locked && fCount == 0 && haveSync && n > lastSync + 2L * BURST_GAP + BOUNDARY) {
            locked = false;
            streak = 0;
        }
        // A hit that is not perfect is held for the boundary tolerance, so a shifted window that happens to match another
        // sync word within the error limit cannot beat the real one a dibit or two later.
        if (pend != null && n > pendEnd + BOUNDARY) {
            DmrSync.Found h = pend, t = pendTwin;
            long e = pendEnd;
            pend = null;
            accept(e, h, t);
        }
        if (n < quietUntil || n < DIBITS) return;
        // Sync sits in the middle: 54 dibits, 24 sync dibits, 54 dibits.
        long raw = syncWindow();
        long cur = inverted ? raw ^ DmrSync.POLARITY : raw;
        long alt = inverted ? raw : raw ^ DmrSync.POLARITY;
        int allow = locked ? TRACK_ERRORS : ACQUIRE_ERRORS;
        DmrSync.Found hit = DmrSync.best(cur, allow, false);
        DmrSync.Found twin = DmrSync.best(alt, allow, false);
        if (!hit.hit()) return;
        if (locked && haveSync && !onGrid(n - lastSync)) return;
        if (hit.errors == 0) {
            pend = null;
            accept(n, hit, twin);
        } else if (pend == null || hit.errors < pend.errors) {
            pend = hit; pendTwin = twin; pendEnd = n;
        }
    }

    /** A sync window ending at {@code end} was chosen as the best one. Decide polarity, check the burst and report it. */
    private void accept(long end, DmrSync.Found hit, DmrSync.Found twin) {
        if (DmrSync.voice(hit.kind) && twin.hit() && DmrSync.data(twin.kind)) {
            if (looksLikeData(end, !inverted)) {
                // This window reads as a valid data burst in the other polarity, so it is probably not voice.
                if (!vote(end)) return;
                hit = twin;
            }
        } else if (DmrSync.data(hit.kind)) {
            if (looksLikeData(end, inverted)) votes = 0;
            else if (wantColor >= 0 && twin.hit() && DmrSync.voice(twin.kind)) addProbe(end + 2L * BURST_GAP);
        }
        boolean[] bits = burstBits(end, inverted);
        DmrSync.Kind kind = hit.kind;
        DmrBurst burst = DmrBurst.parse(bits, kind);
        // A sync word alone is not enough to hold the clock. Noise matches it often enough to stay
        // locked through a gap, and the symbol clock then misses the next key-up. A data burst has to
        // pass the slot-type check. A voice sync has to be close, not merely inside the track limit.
        if (burst != null && !DmrSync.voice(kind) && !burst.slotOk) slotRejected++;
        if (burst == null || (!DmrSync.voice(kind) && !burst.slotOk) || (DmrSync.voice(kind) && hit.errors > ACQUIRE_ERRORS)) return;
        if (haveSync && onGrid(end - lastSync)) {
            streak++;
        } else {
            streak = 1;
            gridBase = end;
            epoch++;
        }
        if (streak >= 2) locked = true;
        lastSync = end;
        haveSync = true;
        quietUntil = end + 60;
        syncSerial++;
        lastEnd = end;
        if (DmrSync.voice(kind)) addFollow(end + 2L * BURST_GAP, 5, kind); // bursts B C D E F of this timeslot
        burst.epoch = epoch;
        burst.gridIndex = gridIndex(end);
        burst.slotHint = DmrSync.directSlot(kind);
        burst.syncErrors = hit.errors;
        sink.burst(burst, hasCach(kind) ? cachBefore(end) : null);
    }

    private static boolean hasCach(DmrSync.Kind kind) { return !DmrSync.mobile(kind) && !DmrSync.direct(kind); }

    /** A sync that read as data with a bad slot type might be an inverted voice sync. Look at its five EMB fields. */
    private void addProbe(long end) {
        for (int p = 0; p < pCount; p++) if (Math.abs(pEnd[p] - end) <= BOUNDARY) return;
        if (pCount == pEnd.length) return;
        pEnd[pCount] = end; pLeft[pCount] = 5; pHit[pCount] = 0;
        pCount++;
    }

    private void probes() {
        for (int p = 0; p < pCount; ) {
            if (n == pEnd[p]) {
                boolean[] bits = burstBits(n, !inverted);
                int emb = (DmrBits.read(bits, 108, 8) << 8) | DmrBits.read(bits, 148, 8);
                int info = DmrFec.qrDecode(emb, 1);
                if (info >= 0 && ((info >> 3) & 15) == wantColor) pHit[p]++;
                pLeft[p]--;
                pEnd[p] += 2L * BURST_GAP;
                // Three EMB fields with the wanted colour code and at most one wrong bit is not a chance event.
                if (pHit[p] >= 3) { flipPolarity(); return; }
            }
            if (pLeft[p] <= 0) {
                pCount--;
                pEnd[p] = pEnd[pCount]; pLeft[p] = pLeft[pCount]; pHit[p] = pHit[pCount];
            } else {
                p++;
            }
        }
    }

    private void flipPolarity() {
        polarityFlips++;
        inverted = !inverted;
        votes = 0; fCount = 0; pCount = 0; pend = null;
        locked = false; haveSync = false; streak = 0; epoch++;
    }

    /** Emit every scheduled voice burst that ends on this dibit. */
    private void followBursts() {
        probes();
        for (int f = 0; f < fCount; ) {
            if (n == fEnd[f]) emitFollow(f);
            if (fLeft[f] <= 0) {
                fCount--;
                fEnd[f] = fEnd[fCount]; fLeft[f] = fLeft[fCount]; fKind[f] = fKind[fCount];
            } else {
                f++;
            }
        }
    }

    private void emitFollow(int f) {
        boolean[] bits = burstBits(n, inverted);
        int emb = (DmrBits.read(bits, 108, 8) << 8) | DmrBits.read(bits, 148, 8);
        int info = DmrFec.qrDecode(emb);
        boolean[] frag = new boolean[32];
        System.arraycopy(bits, 116, frag, 0, 32);
        DmrBurst burst = info < 0
                ? DmrBurst.embeddedVoice(bits, -1, -1, null)
                : DmrBurst.embeddedVoice(bits, (info >> 3) & 15, info & 3, frag);
        burst.epoch = epoch;
        burst.gridIndex = gridIndex(n);
        burst.slotHint = DmrSync.directSlot(fKind[f]);
        fLeft[f]--;
        fEnd[f] += 2L * BURST_GAP;
        lastSync = n; // a scheduled burst is on the grid and is signal: it keeps the lock
        sink.burst(burst, hasCach(fKind[f]) ? cachBefore(n) : null);
    }

    private void addFollow(long end, int count, DmrSync.Kind kind) {
        for (int f = 0; f < fCount; f++) {
            if (fEnd[f] == end) { fLeft[f] = count; fKind[f] = kind; return; }
        }
        if (fCount == MAX_FOLLOW) {
            for (int f = 1; f < fCount; f++) { fEnd[f - 1] = fEnd[f]; fLeft[f - 1] = fLeft[f]; fKind[f - 1] = fKind[f]; }
            fCount--;
        }
        fEnd[fCount] = end; fLeft[fCount] = count; fKind[fCount] = kind;
        fCount++;
    }

    /** Record a vote that the polarity is wrong. True when there are enough votes and the polarity has been flipped. */
    private boolean vote(long end) {
        if (end - lastVote > VOTE_SPAN) votes = 0;
        lastVote = end;
        votes++;
        if (votes < (wantColor >= 0 ? 2 : 3)) return false;
        flipPolarity();
        return true;
    }

    /** True when the burst ending now reads as a data burst in the given polarity with a valid slot type. */
    private boolean looksLikeData(long end, boolean inv) {
        boolean[] bits = burstBits(end, inv);
        int word = (DmrBits.read(bits, 98, 10) << 10) | DmrBits.read(bits, 156, 10);
        int info = DmrFec.golayDecode(word, 1);
        if (info < 0 || (info & 15) > 10) return false;
        return wantColor < 0 || ((info >> 4) & 15) == wantColor;
    }

    private long syncWindow() {
        long sync = 0;
        long mid = n - DIBITS + 54;
        for (int i = 0; i < 24; i++) sync = (sync << 2) | hist[(int) ((mid + i) % hist.length)];
        return sync;
    }

    private long gridIndex(long end) { return Math.round((end - gridBase) / (double) BURST_GAP); }

    /** True when {@code delta} dibits is a whole number of bursts, within {@link #BOUNDARY}. */
    static boolean onGrid(long delta) {
        if (delta <= 0) return false;
        long mod = delta % BURST_GAP;
        return Math.min(mod, BURST_GAP - mod) <= BOUNDARY;
    }

    boolean[] burstBits(long end, boolean inv) {
        boolean[] bits = new boolean[DmrBurst.LENGTH];
        long start = end - DIBITS;
        int flip = inv ? 2 : 0;
        for (int i = 0; i < DIBITS; i++) {
            int d = hist[(int) ((start + i) % hist.length)] ^ flip;
            bits[i * 2] = ((d >> 1) & 1) == 1;
            bits[i * 2 + 1] = (d & 1) == 1;
        }
        return bits;
    }

    /** Raw 24 CACH bits before the burst ending at {@code end}, polarity applied. */
    boolean[] cachBits(long end) {
        boolean[] cbits = new boolean[24];
        long c0 = end - DIBITS - 12;
        int flip = inverted ? 2 : 0;
        for (int i = 0; i < 12; i++) {
            int d = hist[(int) ((c0 + i) % hist.length)] ^ flip;
            cbits[i * 2] = ((d >> 1) & 1) == 1;
            cbits[i * 2 + 1] = (d & 1) == 1;
        }
        return cbits;
    }

    /** The 24 CACH bits that precede the burst ending at {@code end}, or null if the history is too short. */
    DmrCach cachBefore(long end) {
        if (end < DIBITS + 12) return null;
        boolean[] cbits = new boolean[24];
        long c0 = end - DIBITS - 12;
        int flip = inverted ? 2 : 0;
        for (int i = 0; i < 12; i++) {
            int d = hist[(int) ((c0 + i) % hist.length)] ^ flip;
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
