package p25monitor.nativeengine;

import p25monitor.dsp.*;
import p25monitor.p25.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/**
 * One P25 channel inside a wideband source: channelizer -> demodulator(s) -> framer, with automatic frequency control.
 * <ul>
 * <li><b>Modulation</b>: C4FM (FM discriminator), LSM/CQPSK (coherent pi/4-DQPSK receiver with equalizer), or
 *     <b>Auto</b>: both run side by side on the same samples, and the one with fewer bit errors is used. The error
 *     rate comes from the FEC (NID BCH, TSBK trellis, LDU Hamming): corrected bits per checked bit, measured in 0.5 s
 *     windows, with missed frames counted against a demodulator. Control channel TSBKs are taken from both (duplicates
 *     dropped), so a TSBK that either one decodes counts. Voice frames come from the selected one only.</li>
 * <li><b>FLL (before sync)</b>: while no frame sync is found, the mean of the discriminator output measures the carrier
 *     offset directly. When the signal looks like a modulated carrier (low spread), the channel filter is pulled onto it.
 *     If another channel on the same dongle is locked, its offset is the anchor: the FLL may not wander more than
 *     1.5 kHz from it (an idle channel next to a strong carrier would otherwise be pulled onto the neighbour).</li>
 * <li><b>AFC (after sync)</b>: every frame sync measures the residual offset and re-centres the filter.</li>
 * </ul>
 * Optionally writes the channel's I/Q (48 kS/s, about +/-20 kHz around the nominal frequency, no AFC) to a WAV file.
 */
final class ChannelDecoder {
    enum Modulation {
        AUTO("Auto (C4FM or LSM)"), C4FM("C4FM"), LSM("LSM / CQPSK (simulcast)"), TDMA("H-DQPSK (Phase 2 TDMA)");
        final String label;
        Modulation(String l) { label = l; }
        static Modulation parse(String s) {
            if (s == null) return AUTO;
            String t = s.trim().toLowerCase(java.util.Locale.ROOT);
            return t.startsWith("c4") ? C4FM : t.startsWith("lsm") || t.startsWith("cqpsk") ? LSM : AUTO;
        }
        @Override public String toString() { return label; }
    }

    static final double AFC_LIMIT_HZ = 15000, ANCHOR_RANGE_HZ = 1500;
    /** Hold the FLL once frames were decoded (false only to reproduce the 1.2.0 behaviour in tests). */
    static boolean holdAfterLock = !Boolean.getBoolean("trunkdeck.fllNoHold");
    static final int WINDOW = 24000;           // 0.5 s of 48 kHz samples per demodulator comparison
    final long freqHz;
    final Channelizer ch;
    /** Centre of the stream this decoder is fed (tuner centre, or its sub-band's centre on a wideband tuner). */
    volatile long centerHz;
    /** Sub-band of a wideband tuner this decoder reads (set by the tuner's sample loop). */
    int band;
    final Modulation modulation;
    final Demod[] paths;
    volatile Demod active;
    final P25Framer.Listener out;
    /** Phase 2 TDMA channel: the burst framer (both time slots), else null. */
    final P2Framer p2;
    double afcHz;               // correction applied on top of the nominal offset
    volatile long lastFrameAt, lastTsbkAt;
    volatile long lastNidSampleAt = -1_000_000;
    float[] oi = new float[0], oq = new float[0];
    double power;               // channel power (dB relative to the ADC's full scale)
    /**
     * Same level as {@link #power}, for the control-channel signal meter. NaN until the first block of samples, so the
     * meter does not flash the 0 dB that {@code power} starts at. RTL-SDR, Airspy and SoapySDR all reach this from
     * the samples they already deliver; nothing here invents a dBm figure.
     */
    volatile double signalDb = Double.NaN;
    volatile double syncPower = Double.NaN;   // channel power while frames are being received
    volatile boolean enabled = true;
    /** Exceptions thrown by this decoder (within 10 s of each other) and whether it is to be replaced. */
    volatile int faults; volatile long lastFaultAt; volatile boolean faulted;
    volatile long fllSteps, afcSteps, demodSwitches;
    /** Carrier offset (Hz) measured by another locked channel on the same dongle, scaled to this frequency, or NaN. */
    volatile java.util.function.DoubleSupplier anchor;
    long samples, windowAt;
    // diagnostic I/Q recording
    volatile IqTap tap;
    // recent TSBKs (for dropping the second copy when both demodulators decode the same block)
    final java.util.ArrayDeque<long[]> recentTsbk = new java.util.ArrayDeque<>();
    /** Debug trace of frequency-control and demodulator events (enabled with -Dtrunkdeck.trace=true). */
    static final boolean TRACE = Boolean.getBoolean("trunkdeck.trace");
    final java.util.ArrayDeque<String> trace = new java.util.ArrayDeque<>();
    volatile long anchorJumps;
    void trace(String what) {
        if (!TRACE) return;
        synchronized (trace) { if (trace.size() >= 60) trace.pollFirst(); trace.add(String.format(java.util.Locale.ROOT, "%.2fs %s", samples / 48000.0, what)); }
    }
    String traceText() { synchronized (trace) { return String.join(" | ", trace); } }

    /** One demodulator with its own framer, and its error statistics. */
    final class Demod implements C4fmDemod.Sink {
        final SymbolDemod demod;
        final P25Framer framer;
        P2Framer p2f;
        long f0, b0, e0;                // window baselines
        double ber = Double.NaN;        // smoothed bit error rate estimate
        int windows;
        Demod(boolean lsm, int nac) {
            framer = new P25Framer(new P25Framer.Listener() {
                public void nid(int n, int duid, int e) { lastFrameAt = System.currentTimeMillis(); lastNidSampleAt = samples; if (active == Demod.this) out.nid(n, duid, e); }
                public void tsbk(Tsbk t) { if (fresh(t, Demod.this)) { lastTsbkAt = System.currentTimeMillis(); out.tsbk(t); } }
                public void tsbkError() { if (active == Demod.this) out.tsbkError(); }
                public void ldu(int n, int duid, byte[][] imbe, P25Framer.LinkControl lc, P25Framer.EncryptionSync ess) { if (active == Demod.this) out.ldu(n, duid, imbe, lc, ess); }
                public void hdu(int n) { if (active == Demod.this) out.hdu(n); }
                public void terminator(int n, int duid) { if (active == Demod.this) out.terminator(n, duid); }
                public void nidError() { if (active == Demod.this) out.nidError(); }
            });
            framer.expectedNac = nac;
            demod = lsm ? new CqpskDemod(this) : new C4fmDemod(this);
        }
        /** Phase 2: H-DQPSK at 6000 baud into the TDMA framer. */
        Demod(P2Framer f) {
            framer = new P25Framer(new P25Framer.Listener() {});
            p2f = f;
            demod = CqpskDemod.phase2(this);
        }
        String name() { return demod.name(); }
        @Override public void sync(float q) {
            if (p2f != null) p2f.syncEvent(); else framer.sync(q);
            if (active != this) return;
            syncPower = Double.isNaN(syncPower) ? power : 0.7 * syncPower + 0.3 * power;
            // AFC: pull the channel filter onto the carrier (RTL crystals can be several kHz off at 450 MHz)
            double e = demod.freqErrorHz;
            if (Math.abs(e) > 250 && Math.abs(afcHz + e * 0.6) < AFC_LIMIT_HZ) {
                afcHz += e * 0.6;
                ch.setOffset(freqHz - centerHz + afcHz);
                for (Demod p : paths) p.demod.adjustDc(-e * 0.6);
                afcSteps++;
                trace(String.format(java.util.Locale.ROOT, "AFC e=%+.0f -> afc %+.0f", e, afcHz));
            }
        }
        @Override public void symbol(int dibit, float level) { if (p2f != null) p2f.symbol(dibit); else framer.symbol(dibit, level); }
    }

    ChannelDecoder(double rate, long centerHz, long freqHz, P25Framer.Listener l, double afcStart) {
        this(rate, centerHz, freqHz, l, afcStart, Modulation.AUTO, false, -1);
    }

    /** A Phase 2 TDMA voice channel: one RF channel, both time slots go to the listener. */
    ChannelDecoder(double rate, long centerHz, long freqHz, P2Framer.Listener l, double afcStart) {
        this.freqHz = freqHz; this.centerHz = centerHz; this.afcHz = afcStart; this.out = new P25Framer.Listener() {}; this.modulation = Modulation.TDMA;
        ch = new Channelizer(rate, freqHz - centerHz + afcStart);
        p2 = new P2Framer(new P2Framer.Listener() {
            public void voice(int c, byte[][] f, int duid) { l.voice(c, f, duid); }
            public void mac(int c, P2Mac m) { l.mac(c, m); }
            public void fragment() { lastFrameAt = System.currentTimeMillis(); lastNidSampleAt = samples; l.fragment(); }
            public void syncLost() { l.syncLost(); }
        });
        paths = new Demod[]{new Demod(p2)};
        active = paths[0];
    }

    ChannelDecoder(double rate, long centerHz, long freqHz, P25Framer.Listener l, double afcStart, Modulation mod, boolean preferLsm, int nac) {
        this.p2 = null;
        this.freqHz = freqHz; this.centerHz = centerHz; this.afcHz = afcStart; this.out = l; this.modulation = mod;
        ch = new Channelizer(rate, freqHz - centerHz + afcStart);
        paths = switch (mod) {
            case C4FM -> new Demod[]{new Demod(false, nac)};
            case LSM -> new Demod[]{new Demod(true, nac)};
            default -> new Demod[]{new Demod(false, nac), new Demod(true, nac)};
        };
        active = paths.length > 1 && preferLsm ? paths[1] : paths[0];
    }

    /** The wideband tuner was re-centred: same channel, new position in the stream (the tuner reports the gap). */
    void recenter(long newCenterHz) {
        centerHz = newCenterHz;
        ch.setOffset(freqHz - newCenterHz + afcHz);
        IqTap t = tap; if (t != null) t.ch.setOffset(freqHz - newCenterHz);
    }

    void setExpectedNac(int nac) { for (Demod p : paths) p.framer.expectedNac = nac; }

    /**
     * Drops the second demodulator's copy of a TSBK. Control channels repeat identical blocks back to back, so a block
     * is forwarded as many times as the path that decoded it most often did within ~100 ms, not just once.
     */
    synchronized boolean fresh(Tsbk t, Demod from) {
        if (paths.length == 1) return true;
        long a = 0, b = 0;
        for (int k = 0; k < 8; k++) a = (a << 8) | (t.b[k] & 0xff);
        for (int k = 8; k < t.b.length && k < 16; k++) b = (b << 8) | (t.b[k] & 0xff);
        while (!recentTsbk.isEmpty() && samples - recentTsbk.peekFirst()[2] > 4800) recentTsbk.pollFirst();
        int idx = from == paths[0] ? 0 : 1, mine = 1, other = 0;
        for (long[] r : recentTsbk) if (r[0] == a && r[1] == b) { if (r[3] == idx) mine++; else other++; }
        recentTsbk.add(new long[]{a, b, samples, idx});
        return mine > other;
    }

    /** Samples gaps reported by the dongle, and carrier phase jumps seen while frames were being decoded. */
    volatile long gaps, phaseJumps, jumpSamples;
    float jpi, jpq; final float[] jd = new float[3], jmag = new float[4]; int jk, jcool; double carrierP; volatile double envCv;
    static final double ENV_CV_C4FM = 0.35;

    /** The source lost samples: re-sync instead of decoding shifted bits (a voice frame in progress is concealed). */
    void gap() {
        if (!enabled) return;
        gaps++;
        boolean voice = false;
        for (Demod p : paths) { p.demod.unlock(); boolean v = p.framer.abort(); if (p == active) voice = v; }
        if (p2 != null) { p2.reset(); return; }
        if (voice) out.nidError();
    }

    /** Tests: run at the start of every block (fault injection). */
    volatile Runnable faultHook;

    void process(float[] i, float[] q, int n) {
        if (!enabled) return;
        Runnable fh = faultHook; if (fh != null) fh.run();
        IqTap t = tap;
        if (t != null) t.process(i, q, n);
        int need = ch.maxOut(n);
        if (oi.length < need) { oi = new float[need]; oq = new float[need]; }
        int m = ch.process(i, q, n, oi, oq);
        double p = 0;
        for (int k = 0; k < m; k++) p += oi[k] * oi[k] + oq[k] * oq[k];
        if (m > 0) {
            double inst = 10 * Math.log10(p / m + 1e-12);
            power = 0.8 * power + 0.2 * inst;
            signalDb = Double.isNaN(signalDb) ? inst : 0.8 * signalDb + 0.2 * inst;
        }
        // Break detector: a C4FM carrier turns at most ~1.2 rad in 3 samples at 48 kHz (1.8 kHz peak deviation).
        // Lost samples make it jump by a random angle, which the channel filter smears over a few samples; an
        // excursion of over 2 rad in 3 samples at full level means samples are missing from the stream (USB).
        // Counted up to 5 s after the last good frame, at the carrier's level, only on constant-envelope (C4FM) signals: LSM/CQPSK turns too fast for this test. Catches only the
        // larger jumps (about a quarter of the breaks), so it is a lower bound.
        Demod act = active;
        if (m > 0 && samples - lastNidSampleAt < 48000) carrierP = carrierP == 0 ? p / m : 0.9 * carrierP + 0.1 * (p / m);
        if (m > 0) {   // envelope spread (coefficient of variation of |z|^2): ~0 for constant-envelope C4FM, large for LSM/CQPSK
            double mp = p / m, v = 0;
            for (int k = 0; k < m; k++) { double e = oi[k] * oi[k] + oq[k] * oq[k] - mp; v += e * e; }
            double cv = Math.sqrt(v / m) / (mp + 1e-12);
            envCv = envCv == 0 ? cv : 0.8 * envCv + 0.2 * cv;
        }
        if (m > 0 && carrierP > 0 && samples - lastNidSampleAt < 5 * 48000 && envCv < ENV_CV_C4FM) {
            float mean = (float) carrierP;
            for (int k = 0; k < m; k++) {
                float a = oi[k], b = oq[k];
                float pa = jpi, pb = jpq;
                float d = (float) Math.atan2(b * pa - a * pb, a * pa + b * pb);
                jpi = a; jpq = b;
                jd[jk % 3] = d; jmag[jk % 4] = a * a + b * b; jk++;
                if (jcool > 0) { jcool--; continue; }
                if (jk < 4) continue;
                float sum = jd[0] + jd[1] + jd[2];
                if (Math.abs(sum) > 2.0f && jmag[jk % 4] > 0.3f * mean && a * a + b * b > 0.3f * mean) { phaseJumps++; jcool = 96; }
            }
            jumpSamples += m;
        } else if (m > 0) { jpi = oi[m - 1]; jpq = oq[m - 1]; }
        for (Demod pa : paths) pa.demod.process(oi, oq, m);
        samples += m;
        fll();
        if (samples - windowAt >= WINDOW) { windowAt = samples; compare(); }
    }

    /** Every 0.5 s: update each demodulator's error rate and switch to the better one (with hysteresis). */
    void compare() {
        long fm = 0;
        for (Demod pa : paths) fm = Math.max(fm, pa.framer.nidOk - pa.f0);
        for (Demod pa : paths) {
            long df = pa.framer.nidOk - pa.f0, db = pa.framer.fecBits - pa.b0, de = pa.framer.fecErrors - pa.e0;
            pa.f0 = pa.framer.nidOk; pa.b0 = pa.framer.fecBits; pa.e0 = pa.framer.fecErrors;
            if (fm == 0) continue;
            long miss = fm - df;                  // frames the other demodulator decoded but this one missed
            double score = (de + 30.0 * miss) / Math.max(1, db + 300.0 * miss);
            pa.ber = Double.isNaN(pa.ber) ? score : 0.6 * pa.ber + 0.4 * score;
            pa.windows++;
        }
        if (paths.length < 2) return;
        Demod a = active, o = a == paths[0] ? paths[1] : paths[0];
        if (o.windows >= 2 && !Double.isNaN(o.ber) && !Double.isNaN(a.ber) && o.ber < a.ber * 0.6 && a.ber - o.ber > 0.002) {
            trace(String.format(java.util.Locale.ROOT, "switch %s->%s ber %.4f/%.4f", a.name(), o.name(), a.ber, o.ber));
            active = o;
            demodSwitches++;
        }
    }

    /** Pre-sync frequency-locked loop on the mean discriminator output (100 ms windows). */
    void fll() {
        SymbolDemod d = paths[0].demod;
        if (d.discN < 4800) return;
        double mean = d.discSum / d.discN, sd = Math.sqrt(Math.max(0, d.discSq / d.discN - mean * mean));
        for (Demod pa : paths) { pa.demod.discSum = 0; pa.demod.discSq = 0; pa.demod.discN = 0; }
        // frame-synced: the AFC is more precise. (Raw CQPSK syncs don't count: a carrier 6-9 kHz off aliases to a
        // plausible rotation per symbol, so only decoded frames or the C4FM sync prove the filter is on the carrier.)
        if (samples - lastNidSampleAt < 24000 || (d instanceof C4fmDemod && d.samplesSinceSync() < 24000)) return;
        // 1.3.0: once this channel has decoded frames its carrier is known (every transmission on a trunked downlink comes
        // from the same site transmitter), so hold it. Between transmissions a silent channel is dominated by whatever weak
        // signal is nearby (adjacent-channel splatter, intermod, a spur); 1.2.0 let the FLL pull the filter up to 3 kHz onto
        // it, and the next transmission started off frequency (lost and damaged frames, see FllHoldTests). AFC on frame
        // syncs keeps trimming the offset while frames arrive.
        if (holdAfterLock && nidOk() > 0) return;
        java.util.function.DoubleSupplier as = anchor;
        double anc = as == null ? Double.NaN : as.getAsDouble();
        if (!Double.isNaN(anc) && Math.abs(afcHz - anc) > ANCHOR_RANGE_HZ) {   // wandered off: back to the dongle's known offset
            trace(String.format(java.util.Locale.ROOT, "ANCHOR afc %+.0f -> %+.0f", afcHz, anc));
            afcHz = anc; ch.setOffset(freqHz - centerHz + afcHz); fllSteps++; anchorJumps++; return;
        }
        if (sd > 2600 || Math.abs(mean) < 300) return;        // noise (wide spread) or already centred
        double step = 0.7 * mean;
        if (Math.abs(afcHz + step) > AFC_LIMIT_HZ) return;
        if (!Double.isNaN(anc) && Math.abs(afcHz + step - anc) > ANCHOR_RANGE_HZ) return;
        afcHz += step;
        ch.setOffset(freqHz - centerHz + afcHz);
        fllSteps++;
        trace(String.format(java.util.Locale.ROOT, "FLL mean %+.0f sd %.0f -> afc %+.0f", mean, sd, afcHz));
    }

    SymbolDemod demod() { return active.demod; }
    P25Framer framer() { return active.framer; }
    long syncs() { long s = 0; for (Demod p : paths) s = Math.max(s, p.demod.syncs); return s; }
    long nidOk() { if (p2 != null) return p2.fragments; long s = 0; for (Demod p : paths) s = Math.max(s, p.framer.nidOk); return s; }
    long nidBad() { return p2 != null ? p2.ischBad : active.framer.nidBad; }
    boolean tdma() { return p2 != null; }
    boolean lsmActive() { return active.demod instanceof CqpskDemod; }
    /** True once Auto has compared both demodulators on real frames. */
    boolean decided() { for (Demod p : paths) if (p.windows < 2) return false; return true; }

    /** "LSM/CQPSK (auto)  bit errors: C4FM 6.1 %, LSM/CQPSK 0.4 %". */
    String demodSummary() {
        if (p2 != null) return "H-DQPSK (Phase 2 TDMA)  " + scramblingSummary();
        StringBuilder sb = new StringBuilder(active.name()).append(modulation == Modulation.AUTO ? " (auto)" : " (fixed in Systems)");
        sb.append("  bit errors: ");
        for (int k = 0; k < paths.length; k++) {
            Demod p = paths[k];
            if (k > 0) sb.append(", ");
            sb.append(p.name()).append(' ').append(Double.isNaN(p.ber) ? "-" : String.format(java.util.Locale.ROOT, "%.1f %%", 100 * p.ber));
        }
        return sb.toString();
    }

    /** "scrambling OK (WACN A4321 / system 2D5 / NAC 2D5): 120 of 120 scrambled MAC PDUs pass CRC". */
    String scramblingSummary() {
        P2Scrambler sc = p2.scrambler;
        if (sc == null) return "scrambling: WACN / system ID not known yet (waiting for the control channel's network status)";
        long ok = p2.scrambledMacOk, bad = p2.scrambledMacBad;
        String st = ok + bad == 0 ? "no scrambled MAC PDUs yet" : ok > 0 && ok >= bad ? "OK" : "FAILING";
        return String.format(java.util.Locale.ROOT, "scrambling %s (WACN %05X / system %03X / NAC %03X): %d of %d scrambled MAC PDUs pass CRC", st, sc.wacn, sc.sysId, sc.nac, ok, ok + bad);
    }

    /** Total measured carrier offset in Hz (AFC + residual). */
    double frequencyError() { return afcHz + active.demod.freqErrorHz; }
    boolean locked(long now) { return now - lastFrameAt < 2500; }

    int syncResets;
    long lastSyncResetAt;

    /**
     * The channel is still at about the level it had while frames were decoding, but nothing has framed for a long
     * time. A channel that has never framed only counts when the level is high, so a quiet channel is left alone.
     */
    boolean carrierWithoutFrame(long now) {
        if (Double.isNaN(signalDb) || samples < 48_000L * 20) return false;
        long quiet = Long.getLong("trunkdeck.syncQuietMs", 90_000L);
        if (lastFrameAt > 0) {
            if (now - lastFrameAt < quiet) return false;
            return !Double.isNaN(syncPower) && signalDb >= syncPower - 8;
        }
        return signalDb > -12 && samples >= 48_000L * 45;
    }

    /** Pulls the channel filter back and drops symbol timing. At most once a minute. */
    boolean resyncCarrier(long now) {
        if (!carrierWithoutFrame(now) || (lastSyncResetAt != 0 && now - lastSyncResetAt < 60_000)) return false;
        lastSyncResetAt = now;
        syncResets++;
        double back = 0;
        java.util.function.DoubleSupplier as = anchor;
        if (as != null) { double a = as.getAsDouble(); if (!Double.isNaN(a)) back = a; }
        afcHz = back;
        ch.setOffset(freqHz - centerHz + afcHz);
        gap();
        return true;
    }

    void startTap(java.nio.file.Path file, double inRate, int maxSeconds) throws IOException { tap = new IqTap(file, inRate, freqHz - centerHz, maxSeconds); }
    boolean tapping() { return tap != null; }
    java.nio.file.Path stopTap() { IqTap t = tap; tap = null; if (t == null) return null; t.close(); return t.file; }

    /** 48 kS/s complex float WAV of the channel neighbourhood (decodable again with TrunkDeck's I/Q file input). */
    static final class IqTap {
        final java.nio.file.Path file; final Channelizer ch; final FileChannel out; final long maxSamples;
        long samples; float[] ti = new float[0], tq = new float[0]; boolean closed;
        IqTap(java.nio.file.Path file, double inRate, double offsetHz, int maxSeconds) throws IOException {
            this.file = file;
            ch = new Channelizer(inRate, offsetHz, 20000);
            Files.createDirectories(file.getParent());
            out = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            out.write(header(0));
            maxSamples = (long) maxSeconds * Channelizer.OUT_RATE;
        }
        synchronized void process(float[] i, float[] q, int n) {
            if (closed || samples >= maxSamples) return;
            int need = ch.maxOut(n);
            if (ti.length < need) { ti = new float[need]; tq = new float[need]; }
            int m = ch.process(i, q, n, ti, tq);
            ByteBuffer b = ByteBuffer.allocate(m * 8).order(ByteOrder.LITTLE_ENDIAN);
            for (int k = 0; k < m; k++) { b.putFloat(ti[k]); b.putFloat(tq[k]); }
            b.flip();
            try { out.write(b); samples += m; } catch (IOException e) { closed = true; }
        }
        synchronized void close() {
            if (closed && !out.isOpen()) return;
            closed = true;
            try { out.write(header(samples * 8), 0); out.close(); } catch (IOException ignored) {}
        }
        static ByteBuffer header(long dataBytes) {
            ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes()).putInt((int) (36 + dataBytes)).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16)
                    .putShort((short) 3).putShort((short) 2).putInt(Channelizer.OUT_RATE).putInt(Channelizer.OUT_RATE * 8).putShort((short) 8).putShort((short) 32)
                    .put("data".getBytes()).putInt((int) dataBytes);
            h.flip();
            return h;
        }
    }
}
