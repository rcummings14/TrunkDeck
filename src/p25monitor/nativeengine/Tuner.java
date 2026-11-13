package p25monitor.nativeengine;

import p25monitor.dsp.PolyphaseBank;
import p25monitor.sdr.AirspyR2;
import p25monitor.sdr.IqSource;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One receiver in the tuner pool (RTL-SDR, Airspy, a recording or a simulated dongle). Every Phase 1 voice channel inside
 * its usable window can be decoded at the same time: each active call gets its own {@link ChannelDecoder} (frequency
 * shift + decimating filter to 48 kHz) fed from this tuner's samples.
 * <p>The usable window is the sample rate minus the anti-alias roll-off at the edges, minus a guard band around the
 * centre (RTL dongles have a DC spike there): for an RTL at 2.4 MS/s that is about +/-1.0 MHz, for an Airspy at
 * 10 MS/s about +/-4.4 MHz. An Airspy Mini at 6 MS/s is about +/-2.6 MHz and at 3 MS/s about +/-1.3 MHz.
 * <p>Wideband tuners (Airspy R2 at 10 or 2.5 MS/s, Airspy Mini at 10, 6 or 3 MS/s) first split their samples into
 * sub-bands with a polyphase filter bank ({@link PolyphaseBank}); each decoder then runs on the sub-band containing
 * its channel, so a control channel plus a dozen voice channels cost about as much as on an RTL dongle.
 */
public final class Tuner {
    public enum Role { CONTROL, ROAMING }

    final int id;
    final String label;
    final IqSource src;
    final Role role;
    volatile long center;
    /** Voice decoders attached to this tuner (one per active call). The control tuner also feeds the CC decoders. */
    final CopyOnWriteArrayList<ChannelDecoder> decoders = new CopyOnWriteArrayList<>();
    volatile long retunes, lastRetuneAt;
    /** Learned crystal error of this tuner from decoded voice calls (ppm), NaN until measured. */
    volatile double ppmErr = Double.NaN;

    /** Fraction of the sample rate on each side of the centre that is usable for 12.5 kHz channels. */
    final double usableFraction;
    /** No channel closer than this to the centre (DC spike of zero-IF tuners). */
    final long dcGuard;

    /** Sub-band filter bank of a wideband tuner, else null (decoders take the full-rate stream). */
    final PolyphaseBank bank;
    /** Time spent in this tuner's sample processing (filter bank + decoders) and the samples processed. */
    volatile long dspNanos, dspSamples;
    /** Exceptions contained to one decoder (the tuner kept running), and the last one with its stack. */
    volatile long decoderErrors;
    volatile String lastDecoderError = "";

    Tuner(int id, String label, IqSource src, Role role) {
        this.id = id; this.label = label; this.src = src; this.role = role;
        this.center = src.centerHz();
        double rate = src.sampleRate();
        boolean airspy = src instanceof AirspyR2;
        bank = rate >= 2.5e6 && PolyphaseBank.supports(rate) && !Boolean.getBoolean("trunkdeck.noFilterBank") ? new PolyphaseBank(rate) : null;
        // RTL: the last ~15 % of each half is anti-alias roll-off. Airspy: its half-band converter is flat to 0.44 x the rate.
        this.usableFraction = bank != null || airspy ? 0.44 : rate > 5e6 ? 0.45 : 0.42;
        long g = src.dcGuardHz();
        this.dcGuard = g > 0 ? g : rate > 5e6 ? 15_000 : 25_000;
    }

    boolean wideband() { return bank != null; }

    /** Sample rate the channel decoders of this tuner run at (the sub-band rate on a wideband tuner). */
    double decoderRate() { return bank != null ? bank.outRate : src.sampleRate(); }

    /** Centre frequency of the stream a decoder for channel f sees (the sub-band's centre on a wideband tuner). */
    long decoderCenter(long f) { return bank != null ? center + bank.bandCentre(f - center) : center; }

    /** Share of one CPU core this tuner's sample processing used over the last few seconds (0..1+). */
    synchronized double dspLoad() {
        long now = System.nanoTime(), n = dspNanos;
        if (loadAt == 0) loadAt = created;          // first reading: average since the tuner was created
        if (now - loadAt > 2_000_000_000L || load < 0) {
            load = (n - loadNanos) / (double) Math.max(1, now - loadAt);
            loadNanos = n; loadAt = now;
        }
        return Math.max(0, load);
    }
    private long loadNanos, loadAt; private double load = -1; private final long created = System.nanoTime();

    double halfSpan() { return src.sampleRate() * usableFraction - 6_250; }

    /** True if a 12.5 kHz channel at f is inside the usable window at the current centre. */
    boolean covers(long f) { return covers(center, f); }

    boolean covers(long c, long f) {
        if (c <= 0 || f <= 0) return false;
        long d = Math.abs(f - c);
        return d <= halfSpan() && d >= dcGuard;
    }

    boolean retunable() { return role == Role.ROAMING || role == Role.CONTROL; }

    long lo() { return center - (long) halfSpan(); }
    long hi() { return center + (long) halfSpan(); }

    String window() { return center <= 0 ? "not tuned" : String.format(java.util.Locale.ROOT, "%.4f - %.4f MHz (centre %.4f)", lo() / 1e6, hi() / 1e6, center / 1e6); }
}
