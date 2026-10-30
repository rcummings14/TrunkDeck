package p25monitor.nativeengine;

import p25monitor.CallEvent;
import p25monitor.Talkgroup;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

/** One call the engine knows about (granted, waiting for a tuner, decoding, or in hang time). */
final class ActiveCall {
    static final AtomicLong IDS = new AtomicLong();

    final long id = IDS.incrementAndGet();
    int spkSilent;        // speaker QUEUE: silent samples in a row (long gaps are not queued)
    int loggedSrc = -1;   // radio last written to the engine log ("TG x: radio NAME (ID)")
    int tg, src = -1, channel, priority = 100, alg = -1;
    long freq, start, lastSeen, lastVoice, followStart, hangSince, waitingSince;
    boolean encrypted, emergency, phase2, followed, unitToUnit, conventional, patch, eligible, hang, waiting;
    volatile boolean ended;
    // voice diagnostics
    long ldus, lostLdus, imbeFrames, imbeBad, imbeRepeat, imbeErrSum, lcMismatch, tdus, ptts = 1, syncBase, nidBadBase, nidOkBase;
    int mismatchRun, lastLcTg = -1;
    /** Phase 2: TDMA time slot (0 = TS1, 1 = TS2) and AMBE+2 frames received (20 ms each). */
    int slot = -1;
    long p2Frames;
    /** Per-frame IMBE errors: total corrected bits, and errors in Golay word c0. */
    final int[] eHist = new int[17], e0Hist = new int[5];
    /** Voice frames received before the channel's link control confirmed the talkgroup. */
    final ArrayDeque<byte[][]> unconfirmed = new ArrayDeque<>();
    double maxAbsOffset;
    Path iqFile;
    volatile Talkgroup t;   // replaced by the engine's new copy when the user edits the talkgroup (1.4.4)
    CallEvent ev;
    float[] pcm = new float[0];
    int pcmLen;
    /** {sample index into pcm, unit ID} each time the talking radio changed (OpenMHz source_list). */
    final java.util.List<long[]> sources = new java.util.ArrayList<>();
    /** The transmission (PTT) being received, for Broadcastify Calls; null between transmissions. Guarded by this call. */
    Transmission tx;
    /** Whether a transmission of this call was already closed (later ones get their own start time). */
    boolean txDone;
    /** Under the call's lock: the transmission on the air began while no Broadcastify Calls stream wanted it, so none is opened part-way through (a talkgroup assigned mid-transmission uploads from its next one, 1.4.4). */
    boolean txSkip;
    /** Muted (or no longer listened to) while being followed: off the speaker now, ended once its transmission closes (1.4.4). */
    volatile boolean endAfterTx;
    /** Per-call voice codec: IMBE for Phase 1, AMBE+2 (half rate) for Phase 2. */
    EngineLibs.VoiceCodec imbe;
    int voiceFrames;
    /** The tuner whose samples feed this call's decoder (null while waiting). */
    Tuner tuner;
    volatile ChannelDecoder vd;

    String key() { return (unitToUnit ? "U" : "G") + tg; }
    String alias() { return t != null && t.alphaTag != null && !t.alphaTag.isBlank() ? t.alphaTag : ""; }
}
