package p25monitor.nativeengine;

/** What the Monitor view shows for one current call. */
public final class CallView {
    public long id;
    public int tg;
    /** The radio talking (or the last one), 0 if not known yet. */
    public int src;
    public String alias = "", state = "", tuner = "";
    public double mhz, levelDb = Double.NaN, bitsPerFrame = Double.NaN;
    public long durationMs;
    /** Phase 2 TDMA time slot (1 or 2), 0 for Phase 1. */
    public int slot;
    /** onSpeaker: the call the speaker follows. audible: its audio is in the speaker buffers right now. */
    public boolean onSpeaker, audible, encrypted, emergency, recording;
}
