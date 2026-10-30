package p25monitor.dsp;

/**
 * Common interface of the P25 Phase 1 symbol demodulators (C4FM discriminator and CQPSK/LSM coherent). Input is the
 * 48 kHz complex channel (10 samples per symbol); output is frame syncs and dibits for the framer.
 */
public abstract class SymbolDemod {
    public final C4fmDemod.Sink sink;
    public volatile float lastQuality;
    /** Residual carrier offset (Hz) measured from the last frame sync / symbol tracking. */
    public volatile double freqErrorHz;
    public volatile long syncs;
    /** Pre-sync statistics of the symbol-averaged discriminator (Hz), for the FLL in ChannelDecoder. */
    public double discSum, discSq;
    public long discN;
    /** Symbol error vector statistics: squared distance of each symbol from its ideal level (levels +-1, +-3). */
    public double evmSum;
    public long evmN;

    protected SymbolDemod(C4fmDemod.Sink sink) { this.sink = sink; }

    public abstract String name();
    public abstract void process(float[] ci, float[] cq, int count);
    /** The channel filter was moved by AFC: shift the internal frequency estimate so tracking stays continuous. */
    public abstract void adjustDc(double hz);
    /** Samples since the last frame sync (large before the first sync). */
    public abstract long samplesSinceSync();
    /** Stream gap: forget symbol timing until the next frame sync. */
    public abstract void unlock();
}
