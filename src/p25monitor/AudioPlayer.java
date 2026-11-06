package p25monitor;

import p25monitor.nativeengine.EngineLibs;

import javax.sound.sampled.*;
import javax.swing.*;
import java.io.*;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Plays one saved recording at a time on its own speaker line. Live calls, uploads and feeds use a different path
 * and are not touched. WAV and MP3 both play here; nothing is handed to another program.
 * <p>A live call on the speaker pauses playback (and it continues when that call is over) unless the user has paused
 * or pressed play themselves. The speaker mute and a volume of zero do not pause a recording: the live call is silent
 * then, so there is nothing to miss.
 */
public final class AudioPlayer {
    /** Where the recording is written. Tests supply their own; the real one is a sound-card line separate from live audio. */
    public interface Sink {
        void open(int sampleRate, int channels) throws Exception;
        void write(byte[] b, int off, int len);
        void pause();
        void resume();
        void close();
    }

    public interface Listener { void changed(); }

    /** Test hook: a new device for each play. Null uses the computer's speaker. */
    public static volatile java.util.function.Supplier<Sink> sinkOverride;
    /** Test hook: playback thread, before each chunk is written. */
    public static volatile Runnable beforeChunk;

    private static final Object LOCK = new Object();
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static volatile double volume = 0.8;
    private static int generation;
    private static boolean active, userPaused, heldForLive, liveOn;
    private static File file;
    private static String title = "", when = "", problem;
    private static Sink sink;

    private AudioPlayer() {}

    public static void addListener(Listener l) { if (l != null) listeners.add(l); }

    public static void setVolume(double v) { volume = Math.max(0, Math.min(1, v)); }

    /**
     * True when a live call should pause a recording: the engine is running, the speaker is not muted or at zero,
     * and it is on a call (including the short wait for a reply). Idle scanning does not pause playback.
     */
    public static boolean liveShouldHold(boolean engineRunning, boolean speakerMuted, int speakerVolume, int displayState) {
        return engineRunning && !speakerMuted && speakerVolume > 0 && displayState != 0;
    }

    /** Starts this recording from the beginning and stops whatever was playing. Null if it started; otherwise a plain message. */
    public static String play(File f, String callTitle, String callWhen) {
        if (f == null || !f.isFile()) return "That recording isn't there.";
        if (!f.canRead()) return "That recording can't be read.";
        if (f.length() > 40_000_000L) return "That recording is too large to play here.";
        String name = f.getName().toLowerCase();
        EngineLibs.Pcm pcm;
        try {
            byte[] raw = Files.readAllBytes(f.toPath());
            if (name.endsWith(".mp3")) pcm = EngineLibs.decodeMp3(raw);
            else if (name.endsWith(".wav")) pcm = decodeWav(raw);
            else return "That recording can't be played.";
        } catch (IllegalStateException ex) {
            return "Playing saved calls needs a piece from the installer. Run the installer again.";
        } catch (Exception ex) {
            return "That recording can't be played.";
        }
        if (pcm == null || pcm.data == null || pcm.data.length < 4 || pcm.sampleRate <= 0 || pcm.channels < 1 || pcm.channels > 2)
            return "That recording can't be played.";
        String failed = null;
        synchronized (LOCK) {
            stopLocked();
            int gen = generation;
            Sink mine = sinkOverride != null ? sinkOverride.get() : new LineSink();
            try { mine.open(pcm.sampleRate, pcm.channels); }
            catch (Exception ex) {
                try { mine.close(); } catch (Exception ignored) {}
                failed = "No speaker was found to play this recording.";
            }
            if (failed == null) {
                active = true;
                userPaused = false;
                heldForLive = liveOn;   // already hearing a live call: hold until it ends, or until Play is pressed
                file = f;
                title = callTitle == null ? "" : callTitle;
                when = callWhen == null ? "" : callWhen;
                problem = null;
                sink = mine;
                if (heldForLive) { try { mine.pause(); } catch (Exception ignored) {} }
                Thread th = new Thread(() -> loop(gen, mine, pcm.data, pcm.channels), "recording-play");
                th.setDaemon(true);
                th.start();
            }
        }
        fire();
        return failed;
    }

    public static void togglePause() {
        synchronized (LOCK) {
            if (!active) return;
            if (userPaused || heldForLive) {
                userPaused = false;
                heldForLive = false;   // an explicit Play is heard even during this live call; the next one pauses again
                deviceResume();
            } else {
                userPaused = true;
                devicePause();
            }
        }
        fire();
    }

    /** Rising edge of a live call pauses playback. When the call is over, playback continues unless the user paused. */
    public static void setLiveCall(boolean live) {
        boolean changed = false;
        synchronized (LOCK) {
            if (live == liveOn) return;
            liveOn = live;
            if (!active) return;
            if (live && !userPaused) { heldForLive = true; devicePause(); changed = true; }
            else if (!live && heldForLive) {
                heldForLive = false;
                if (!userPaused) deviceResume();
                changed = true;
            }
        }
        if (changed) fire();
    }

    public static void stop() {
        synchronized (LOCK) { stopLocked(); }
        fire();
    }

    public static boolean active() { synchronized (LOCK) { return active; } }
    public static boolean paused() { synchronized (LOCK) { return active && (userPaused || heldForLive); } }
    public static boolean pausedForLive() { synchronized (LOCK) { return active && heldForLive && !userPaused; } }

    public static boolean same(File f) {
        synchronized (LOCK) { return active && file != null && f != null && file.getAbsolutePath().equals(f.getAbsolutePath()); }
    }

    public static File currentFile() { synchronized (LOCK) { return active ? file : null; } }

    public static String statusText() {
        synchronized (LOCK) {
            if (!active) return "Stopped";
            String what = title == null || title.isBlank() ? "Recording" : title;
            String clock = when == null || when.isBlank() ? "" : " \u00b7 " + when;
            if (heldForLive && !userPaused) return "Paused for a live call: " + what + clock;
            if (userPaused || heldForLive) return "Paused: " + what + clock;
            return "Playing: " + what + clock;
        }
    }

    /** A device failure while playing, once. Null if there is nothing new. */
    public static String takeProblem() {
        synchronized (LOCK) { String p = problem; problem = null; return p; }
    }

    private static void stopLocked() {
        generation++;
        active = false;
        userPaused = false;
        heldForLive = false;
        file = null;
        title = "";
        when = "";
        Sink s = sink;
        sink = null;
        LOCK.notifyAll();
        if (s != null) { try { s.close(); } catch (Exception ignored) {} }
    }

    private static void devicePause() { Sink s = sink; if (s != null) try { s.pause(); } catch (Exception ignored) {} }
    private static void deviceResume() { Sink s = sink; if (s != null) try { s.resume(); } catch (Exception ignored) {} }

    private static void loop(int gen, Sink mine, byte[] pcm, int channels) {
        int frame = channels * 2;
        int chunk = Math.max(frame, 320 * frame);   // about 20 ms at 8 kHz, still fine at higher rates
        byte[] buf = new byte[chunk];
        int pos = 0;
        try {
            while (pos + frame <= pcm.length) {
                synchronized (LOCK) {
                    while (gen == generation && (userPaused || heldForLive)) {
                        try { mine.pause(); } catch (Exception ignored) {}
                        LOCK.wait(400);
                    }
                    if (gen != generation) return;
                    try { mine.resume(); } catch (Exception ignored) {}
                }
                Runnable hook = beforeChunk;
                if (hook != null) hook.run();
                int n;
                synchronized (LOCK) {
                    if (gen != generation) return;
                    if (userPaused || heldForLive) continue;
                    n = Math.min(chunk, pcm.length - pos);
                    n -= n % frame;
                }
                if (n <= 0) break;
                applyVolume(pcm, pos, buf, n);
                mine.write(buf, 0, n);
                pos += n;
            }
        } catch (Exception ex) {
            synchronized (LOCK) { if (gen == generation) problem = "That recording stopped playing."; }
        } finally {
            try { mine.close(); } catch (Exception ignored) {}
            boolean ended = false;
            synchronized (LOCK) {
                if (gen == generation) {
                    active = false;
                    userPaused = false;
                    heldForLive = false;
                    file = null;
                    title = "";
                    when = "";
                    if (sink == mine) sink = null;
                    ended = true;
                }
            }
            if (ended) fire();
        }
    }

    static void applyVolume(byte[] src, int off, byte[] dst, int n) {
        float g = (float) volume;
        if (g > 0.999f) { System.arraycopy(src, off, dst, 0, n); return; }
        for (int i = 0; i < n; i += 2) {
            int s = (src[off + i] & 0xff) | (src[off + i + 1] << 8);
            int v = Math.round(s * g);
            if (v > 32767) v = 32767;
            else if (v < -32768) v = -32768;
            dst[i] = (byte) v;
            dst[i + 1] = (byte) (v >> 8);
        }
    }

    static EngineLibs.Pcm decodeWav(byte[] raw) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(raw))) {
            AudioFormat base = in.getFormat();
            int ch = Math.max(1, base.getChannels());
            float rate = base.getSampleRate();
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, ch, ch * 2, rate, false);
            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(target, in)) {
                return new EngineLibs.Pcm(Math.round(rate), ch, pcm.readAllBytes());
            }
        }
    }

    private static void fire() {
        for (Listener l : listeners) {
            if (SwingUtilities.isEventDispatchThread()) l.changed();
            else SwingUtilities.invokeLater(l::changed);
        }
    }

    static final class LineSink implements Sink {
        SourceDataLine line;
        public void open(int rate, int channels) throws Exception {
            AudioFormat f = new AudioFormat(rate, 16, channels, true, false);
            int bytes = Math.max(1024, rate * channels * 2 / 25);   // about 40 ms, so pause is quick
            line = AudioSystem.getSourceDataLine(f);
            line.open(f, bytes);
            line.start();
        }
        public void write(byte[] b, int off, int len) { line.write(b, off, len); }
        public void pause() { try { if (line != null) line.stop(); } catch (Exception ignored) {} }
        public void resume() { try { if (line != null) line.start(); } catch (Exception ignored) {} }
        public void close() {
            SourceDataLine l = line;
            line = null;
            if (l != null) try { l.stop(); l.flush(); l.close(); } catch (Exception ignored) {}
        }
    }
}
