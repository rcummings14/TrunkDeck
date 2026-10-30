package p25monitor.sdr;

/** A source of complex samples (RTL-SDR dongle or a recording). */
public interface IqSource extends AutoCloseable {
    interface Listener {
        void samples(float[] i, float[] q, int n);
        /** Samples were lost just before the next block (the decoders should re-sync, not decode shifted bits). */
        default void gap() {}
        /** Processing failed and is being restarted: drop all DSP and decoder state (the next block follows a gap). */
        default void reset() {}
    }
    double sampleRate();
    /** Centre frequency in Hz (0 if unknown, e.g. an IF recording). */
    long centerHz();
    /** Retune (dongles only). Returns false if not supported. */
    default boolean tune(long hz) { return false; }
    String name();
    /** Starts streaming on a background thread. */
    void start(Listener l) throws Exception;
    void stop();
    default void close() { stop(); }
    default boolean running() { return false; }
    /** Error that stopped the stream, if any. */
    default String error() { return null; }
    /** Airspy receivers (and their simulation): the USB sample stream with its drop counters; null for others. */
    default AirspyStream usbStream() { return null; }
    /** Failure / restart bookkeeping of the sample processing (Airspy, RTL-SDR); null for sources without one. */
    default ProcessingGuard guard() { AirspyStream s = usbStream(); return s == null ? null : s.guard; }
    /** Stall watchdog, called by the engine about every 250 ms; true if it restarted the processing. */
    default boolean watchdog(long stallMs) { AirspyStream s = usbStream(); return s != null && s.watchdog(stallMs); }
}
