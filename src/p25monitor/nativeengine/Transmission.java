package p25monitor.nativeengine;

import java.io.ByteArrayOutputStream;

/**
 * One transmission (one PTT) of a call, the unit uploaded to Broadcastify Calls. It opens with the first voice frame and
 * closes at the transmission's terminator (Phase 1 TDU/TDULC, Phase 2 MAC_END_PTT / idle), or after a short timeout
 * when no terminator was heard, or when the call ends. Its MP3 (22050 Hz mono 16 kb/s CBR, Broadcastify's format) is
 * encoded while the audio arrives, so the file is complete the moment the transmission ends.
 */
public final class Transmission {
    public final long id;
    public final int tg;
    public final long freqHz;
    /** The talkgroup settings when the transmission began: decide where it is uploaded, so a stream assignment edited mid-transmission applies from the next one (1.4.4). */
    public volatile p25monitor.Talkgroup pinned;
    /** Unix ms of the transmission's start (call grant for the first one, else derived from its audio). */
    public volatile long startMs;
    public volatile int src;
    /** Wall clock / nanoTime of the last voice audio added, and of the close. */
    public volatile long lastAudioMs, lastAudioNanos, endedMs, endedNanos;
    public volatile String endReason = "";
    /** The call's first transmission (its start is the grant); later ones start at (last audio - duration). */
    public volatile boolean first = true;
    private int samples;
    private final EngineLibs.Mp3 mp3;
    private final Resampler rs;
    private final ByteArrayOutputStream out;
    private byte[] finished;
    private String encodeError;

    public Transmission(long id, int tg, long freqHz, long startMs, int src, boolean encode) {
        this.id = id; this.tg = tg; this.freqHz = freqHz; this.startMs = startMs; this.src = src;
        EngineLibs.Mp3 m = null;
        if (encode) try { m = new EngineLibs.Mp3(16, 22050); } catch (Exception e) { encodeError = e.getMessage(); }
        mp3 = m; rs = m == null ? null : new Resampler(); out = m == null ? null : new ByteArrayOutputStream(16 * 1024);
        if (encode && m == null && encodeError == null) encodeError = "MP3 encoder not available";
    }

    /** 8 kHz audio of this transmission (encoded straight away). */
    public synchronized void add(float[] a, int n, boolean voice) {
        samples += n;
        if (voice) { lastAudioMs = System.currentTimeMillis(); lastAudioNanos = System.nanoTime(); }
        if (mp3 != null && finished == null) {
            float[] r = rs.process(a, n);
            out.writeBytes(mp3.encode(EngineLibs.pcm16(r, r.length), r.length * 2));
        }
    }

    public synchronized int samples() { return samples; }
    public double seconds() { return samples() / 8000.0; }

    /** Marks the end (idempotent). */
    public synchronized void end(String reason) {
        if (endedNanos != 0) return;
        endedMs = System.currentTimeMillis(); endedNanos = System.nanoTime(); endReason = reason;
        if (lastAudioNanos == 0) { lastAudioNanos = endedNanos; lastAudioMs = endedMs; }
    }

    /** The finished MP3 (flushes the encoder the first time; a few ms). Null if there is no encoder. */
    public synchronized byte[] mp3() {
        if (mp3 == null) return null;
        if (finished == null) {
            float[] r = rs.flush();
            out.writeBytes(mp3.encode(EngineLibs.pcm16(r, r.length), r.length * 2));
            out.writeBytes(mp3.finish());
            finished = out.toByteArray();
        }
        return finished;
    }

    public String encodeError() { return encodeError; }
}
