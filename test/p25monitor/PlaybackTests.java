package p25monitor;

import p25monitor.nativeengine.EngineLibs;

import javax.sound.sampled.*;
import javax.swing.*;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-app playback of saved calls: MP3 (the files TrunkDeck actually writes) and WAV decode and play on their own
 * line, one at a time, with pause and a hold while a live call is on the speaker. A missing or unreadable file
 * gets a plain message. Encrypted calls are not opened. The window checks need a display and are skipped when headless.
 */
public final class PlaybackTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) { pass++; System.out.println("  ok   " + what); } else { fail++; System.out.println("  FAIL " + what); } }
    static void edt(Runnable r) throws Exception { SwingUtilities.invokeAndWait(r); }
    static boolean waitFor(java.util.function.BooleanSupplier b, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { if (b.getAsBoolean()) return true; Thread.sleep(15); }
        return b.getAsBoolean();
    }

    public static void main(String[] a) throws Exception {
        if (System.getProperty("trunkdeck.lib") == null) System.setProperty("trunkdeck.lib", "lib");
        try {
            AudioPlayer.stop();
            AudioPlayer.setLiveCall(false);
            livePolicy();
            messages();
            findFiles();
            decodeMp3();
            playWav();
            pauseAndLive();
            oneAtATime();
            if (GraphicsEnvironmentHeadless()) System.out.println("PlaybackTests window: skipped (needs a display)");
            else window();
        } finally {
            AudioPlayer.beforeChunk = null;
            AudioPlayer.sinkOverride = null;
            AudioPlayer.stop();
        }
        System.out.println("PlaybackTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static boolean GraphicsEnvironmentHeadless() { return java.awt.GraphicsEnvironment.isHeadless(); }

    static void livePolicy() {
        System.out.println("1. a live call on the speaker pauses a recording; a muted or idle speaker does not");
        check(AudioPlayer.liveShouldHold(true, false, 80, 2), "hearing a call");
        check(AudioPlayer.liveShouldHold(true, false, 80, 1), "hang time still holds the recording");
        check(!AudioPlayer.liveShouldHold(true, false, 80, 0), "scanning does not hold it");
        check(!AudioPlayer.liveShouldHold(false, false, 80, 2), "engine stopped does not hold it");
        check(!AudioPlayer.liveShouldHold(true, true, 80, 2), "muted speaker does not hold it");
        check(!AudioPlayer.liveShouldHold(true, false, 0, 2), "speaker volume zero does not hold it");
    }

    static void messages() {
        System.out.println("2. plain messages, and encrypted calls are not opened");
        Path dir = Path.of("no-such-recordings-dir");
        check("That recording isn't there.".equals(AudioPlayer.play(dir.resolve("missing.mp3").toFile(), "North Dispatch", "01-02 03:04:05")), "missing file");
        check(!AudioPlayer.active(), "missing file does not start playback");
        CallEvent enc = call("2026-01-02 03:04:05", 4242, 7, "Encrypted Group Call", "ENCRYPTED (LC) - AUDIO MUTED, NOT DECRYPTED", 0);
        check(RecordingFiles.unavailable(enc).contains("Encrypted"), "encrypted call is refused");
        CallEvent data = call("2026-01-02 03:04:05", 4242, 7, "Data Call", "", 0);
        check(RecordingFiles.unavailable(data) != null, "a non-voice event has no recording");
        CallEvent pending = call("2026-01-02 03:04:05", 4242, 7, "Group Call", "", 0);
        check(RecordingFiles.missing(pending).contains("hasn't been saved"), "a call still on the air");
        CallEvent missed = call("2026-01-02 03:04:05", 4242, 7, "Group Call", "MISSED - OUT OF TUNER RANGE", 4000);
        check("There's no recording for that call.".equals(RecordingFiles.missing(missed)), "a missed call");
        check(RecordingFiles.unavailable(null).contains("Choose"), "no row selected");
    }

    static void findFiles() throws Exception {
        System.out.println("3. file name matches the call, and a different talkgroup does not");
        Path dir = Files.createTempDirectory("pb-find");
        String name = "20260102_030405_TrunkDeck_TO_4242_FROM_7.mp3";
        Files.write(dir.resolve(name), new byte[]{1, 2, 3, 4});
        Files.write(dir.resolve("20260102_030405_TrunkDeck_TO_42420_FROM_7.mp3"), new byte[]{1});
        Path hit = RecordingFiles.find(dir, "2026-01-02 03:04:05", 4242, 7);
        check(hit != null && hit.getFileName().toString().equals(name), "exact talkgroup and radio");
        Path otherRadio = RecordingFiles.find(dir, "2026-01-02 03:04:05", 4242, 99);
        check(otherRadio != null && otherRadio.getFileName().toString().equals(name), "same second and talkgroup, radio learned later");
        check(RecordingFiles.find(dir, "2026-01-02 03:04:05", 42420, 7) != null, "the longer talkgroup id is its own file");
        check(RecordingFiles.find(dir, "2026-01-02 03:04:06", 4242, 7) == null, "a different second is not this call");
        check("01-02 03:04:05".equals(RecordingFiles.whenLabel("2026-01-02 03:04:05")), "time label");
        check("01-02 03:04:05".equals(RecordingFiles.whenFromFile(name)), "time from the file name");
        check(RecordingFiles.matchesPlaying(call("2026-01-02 03:04:05", 4242, 7, "Group Call", "", 1000), hit.toFile()), "playing row matches");
        check(!RecordingFiles.matchesPlaying(call("2026-01-02 03:04:05", 42420, 7, "Group Call", "", 1000), hit.toFile()), "a different talkgroup row does not match");
    }

    static void decodeMp3() throws Exception {
        System.out.println("4. an MP3 TrunkDeck would write decodes back to the tone");
        check(EngineLibs.mp3Available(), "java-lame is available");
        int n = 8000;
        float[] tone = new float[n];
        for (int i = 0; i < n; i++) tone[i] = 0.6f * (float) Math.sin(2 * Math.PI * 440 * i / 8000.0);
        byte[] pcm = EngineLibs.pcm16(tone, n);
        EngineLibs.Mp3 enc = new EngineLibs.Mp3(16);
        byte[] mp3 = concat(enc.encode(pcm, pcm.length), enc.finish());
        EngineLibs.Pcm dec = EngineLibs.decodeMp3(mp3);
        check(dec.sampleRate == 8000, "8 kHz");
        check(dec.channels == 1, "mono");
        check(dec.data.length > n, "decoded audio is present (" + dec.data.length + " bytes)");
        int[] s = shorts(dec.data);
        double best = -1;
        for (int lag = 0; lag < 2000; lag += 1) {
            double c = 0;
            for (int i = 0; i < 2000 && lag + i < s.length; i++) c += (s[lag + i] / 32768.0) * Math.sin(2 * Math.PI * 440 * i / 8000.0);
            if (c > best) best = c;
        }
        check(best > 200, "decoded audio is the 440 Hz tone (correlation " + Math.round(best) + ")");
        Path junk = Files.createTempFile("pb-junk", ".mp3");
        Files.write(junk, new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9});
        String bad = AudioPlayer.play(junk.toFile(), "North Dispatch", "01-02 03:04:05");
        check(bad != null && !bad.isBlank() && !AudioPlayer.active(), "unreadable recording: " + bad);
        Path mp3File = Files.createTempFile("pb-tone", ".mp3");
        Files.write(mp3File, mp3);
        List<Fake> sinks = new ArrayList<>();
        AudioPlayer.setVolume(1);
        AudioPlayer.setLiveCall(false);
        AudioPlayer.beforeChunk = null;
        AudioPlayer.sinkOverride = () -> { Fake f = new Fake(); sinks.add(f); return f; };
        String played = AudioPlayer.play(mp3File.toFile(), "North Dispatch", "01-02 03:04:05");
        check(played == null && waitFor(() -> !AudioPlayer.active() && sinks.size() == 1 && sinks.get(0).rate == 8000 && sinks.get(0).bytes().length > 4000, 3000),
                "the MP3 plays inside TrunkDeck");
    }

    static void playWav() throws Exception {
        System.out.println("5. WAV plays on the recording line, not by failing silently");
        byte[] pcm = tonePcm(8000, 440, 0.5);
        Path wav = Files.createTempFile("pb", ".wav");
        writeWav(wav, pcm);
        List<Fake> sinks = new ArrayList<>();
        AudioPlayer.setVolume(1);
        AudioPlayer.sinkOverride = () -> { Fake f = new Fake(); sinks.add(f); return f; };
        AudioPlayer.beforeChunk = null;
        String err = AudioPlayer.play(wav.toFile(), "North Dispatch", "01-02 03:04:05");
        check(err == null, "play starts (" + err + ")");
        check(waitFor(() -> !AudioPlayer.active() && !sinks.isEmpty() && sinks.get(0).closed, 3000), "playback finishes");
        check(sinks.size() == 1 && sinks.get(0).rate == 8000 && sinks.get(0).channels == 1, "8 kHz mono on its own line");
        byte[] got = sinks.get(0).bytes();
        check(Arrays.equals(pcm, got), "the speaker line got the recording (" + got.length + " bytes)");
        check(AudioPlayer.statusText().equals("Stopped"), "stopped when it ends");
    }

    static void pauseAndLive() throws Exception {
        System.out.println("6. pause, and a live call pauses then continues");
        AudioPlayer.setVolume(1);
        byte[] pcm = tonePcm(8000, 440, 2.0);
        Path wav = Files.createTempFile("pb-long", ".wav");
        writeWav(wav, pcm);
        List<Fake> sinks = Collections.synchronizedList(new ArrayList<>());
        AudioPlayer.sinkOverride = () -> { Fake f = new Fake(); sinks.add(f); return f; };
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger hooks = new AtomicInteger();
        AudioPlayer.beforeChunk = () -> {
            if (hooks.incrementAndGet() == 1) { entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
        };
        check(AudioPlayer.play(wav.toFile(), "North Dispatch", "01-02 03:04:05") == null, "long recording starts");
        check(entered.await(2, TimeUnit.SECONDS), "playback reached the speaker");
        check(AudioPlayer.statusText().startsWith("Playing: North Dispatch"), "shows the talkgroup: " + AudioPlayer.statusText());
        check(AudioPlayer.statusText().contains("01-02 03:04:05"), "shows the time");
        AudioPlayer.setLiveCall(true);
        check(AudioPlayer.pausedForLive() && AudioPlayer.statusText().startsWith("Paused for a live call"), "live call pauses it: " + AudioPlayer.statusText());
        AudioPlayer.setLiveCall(false);
        check(AudioPlayer.active() && !AudioPlayer.paused() && AudioPlayer.statusText().startsWith("Playing:"), "it continues after the live call");
        AudioPlayer.togglePause();
        check(AudioPlayer.paused() && !AudioPlayer.pausedForLive() && AudioPlayer.statusText().startsWith("Paused:"), "Play pauses");
        AudioPlayer.setLiveCall(true);
        check(AudioPlayer.statusText().startsWith("Paused:") && !AudioPlayer.pausedForLive(), "a live call does not resume over a pause the user asked for");
        AudioPlayer.setLiveCall(false);
        AudioPlayer.togglePause();
        check(AudioPlayer.statusText().startsWith("Playing:"), "Play again");
        AudioPlayer.setLiveCall(true);
        check(AudioPlayer.pausedForLive(), "the next live call pauses it again");
        AudioPlayer.togglePause();
        check(AudioPlayer.statusText().startsWith("Playing:") && !AudioPlayer.paused(), "pressing Play during a live call is heard");
        release.countDown();
        AudioPlayer.setLiveCall(false);
        AudioPlayer.stop();
        check(waitFor(() -> sinks.stream().allMatch(s -> s.closed), 2000), "stop closes the recording line");
        check(!AudioPlayer.active(), "stopped");
    }

    static void oneAtATime() throws Exception {
        System.out.println("7. starting another recording stops the one that is playing");
        AudioPlayer.setVolume(1);
        AudioPlayer.setLiveCall(false);
        byte[] a = tonePcm(8000, 440, 2.0);
        byte[] b = tonePcm(8000, 880, 0.4);
        Path fa = Files.createTempFile("pb-a", ".wav");
        Path fb = Files.createTempFile("pb-b", ".wav");
        writeWav(fa, a); writeWav(fb, b);
        List<Fake> sinks = Collections.synchronizedList(new ArrayList<>());
        AudioPlayer.sinkOverride = () -> { Fake f = new Fake(); sinks.add(f); return f; };
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger hooks = new AtomicInteger();
        AudioPlayer.beforeChunk = () -> {
            int n = hooks.incrementAndGet();
            if (n == 1) entered.countDown();
            if (n <= 2) try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        check(AudioPlayer.play(fa.toFile(), "North Dispatch", "01-02 03:04:05") == null, "first recording");
        check(entered.await(2, TimeUnit.SECONDS), "first recording is playing");
        check(AudioPlayer.play(fb.toFile(), "South Dispatch", "01-02 03:05:06") == null, "second recording");
        check(AudioPlayer.same(fb.toFile()) && AudioPlayer.statusText().contains("South Dispatch"), "only the second is current");
        check(sinks.size() >= 2 && sinks.get(0).closed, "the first line was closed");
        release.countDown();
        check(waitFor(() -> !AudioPlayer.active(), 3000), "second recording finishes");
        byte[] got = sinks.get(sinks.size() - 1).bytes();
        check(Arrays.equals(b, got), "only the second recording was written (" + got.length + " vs " + b.length + ")");
        int firstBytes = sinks.get(0).bytes().length;
        check(firstBytes == 0, "the first recording wrote nothing after it was stopped (" + firstBytes + ")");
    }

    static void window() throws Exception {
        System.out.println("8. call log and Recordings page controls");
        Path home = Files.createTempDirectory("pb-home");
        Path rec = Files.createTempDirectory("pb-rec");
        System.setProperty("trunkdeck.profile", home.resolve("p").toString());
        System.setProperty("trunkdeck.recordings", rec.toString());
        AudioPlayer.setLiveCall(false);
        AudioPlayer.stop();
        byte[] pcm = tonePcm(8000, 440, 3.0);
        Path file = rec.resolve("20260102_030405_TrunkDeck_TO_4242_FROM_7.wav");
        writeWav(file, pcm);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger hooks = new AtomicInteger();
        AudioPlayer.beforeChunk = () -> {
            entered.countDown();
            while (hooks.get() == 0) {
                try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                if (!AudioPlayer.active()) return;
            }
        };
        List<Fake> sinks = Collections.synchronizedList(new ArrayList<>());
        AudioPlayer.sinkOverride = () -> { Fake f = new Fake(); sinks.add(f); return f; };
        App app = new App(home);
        app.load();
        Talkgroup tg = new Talkgroup();
        tg.decimal = 4242; tg.alphaTag = "North Dispatch";
        if (app.current != null) app.current.talkgroups.add(tg);
        MainFrame[] fr = new MainFrame[1];
        edt(() -> { Theme.install(); fr[0] = new MainFrame(app); fr[0].setSize(1366, 740); fr[0].setVisible(true); });
        MainFrame f = fr[0];
        try {
            edt(() -> f.setSimple(false));
            CallEvent e = call("2026-01-02 03:04:05", 4242, 7, "Group Call", "", 2000);
            edt(() -> f.monitor.add(e, tg, tg));
            check(f.monitor.logPlay.isShowing(), "Advanced view shows Play on the call log");
            edt(() -> { f.monitor.table.setRowSelectionInterval(0, 0); f.monitor.logPlay.doClick(); });
            check(entered.await(3, TimeUnit.SECONDS), "call-log Play started the recording");
            check(waitFor(() -> text(f.monitor.playingLbl).contains("Playing") && text(f.monitor.playingLbl).contains("North Dispatch"), 2000),
                    "the monitor shows the talkgroup: " + text(f.monitor.playingLbl));
            check(text(f.monitor.playingLbl).contains("03:04:05"), "the monitor shows the time");
            edt(() -> f.show("Recordings"));
            check(waitFor(() -> f.recordings.nowPlaying.getText().startsWith("Playing:"), 2000), "Recordings page shows the same call");
            check("Pause".equals(textOfButton(f.recordings.playBtn)), "the button reads Pause while it plays");
            edt(() -> f.recordings.playBtn.doClick());
            check(waitFor(() -> f.recordings.nowPlaying.getText().startsWith("Paused:"), 2000), "Pause on the Recordings page");
            edt(() -> f.recordings.playBtn.doClick());
            check(waitFor(() -> f.recordings.nowPlaying.getText().startsWith("Playing:"), 2000), "Play again on the Recordings page");
            edt(() -> { f.show("Monitor"); f.setSimple(true); });
            check(waitFor(() -> f.monitor.playingLbl.isShowing(), 2000), "Simple view still shows what is playing");
            check(waitFor(() -> !f.monitor.logPlay.isShowing(), 2000), "Simple view keeps the call log tucked away");
            check(waitFor(() -> f.monitor.replayBtn.isShowing(), 2000), "Replay last stays available in Simple view");
            hooks.set(1);   // let the held recording finish
            edt(() -> { f.show("Recordings"); f.recordings.refresh(); });
            check(waitFor(() -> f.recordings.table.getRowCount() > 0, 2000), "the recording is listed");
            edt(() -> f.recordings.table.setRowSelectionInterval(0, 0));
            Files.deleteIfExists(file);
            edt(() -> f.recordings.playBtn.doClick());
            check(waitFor(() -> f.footer.getText().contains("isn't there"), 2000), "missing file is explained: " + f.footer.getText());
            edt(() -> { f.setSimple(false); f.show("Monitor"); f.monitor.table.setRowSelectionInterval(0, 0); f.monitor.logPlay.doClick(); });
            check(waitFor(() -> f.footer.getText().toLowerCase().contains("no recording"), 2000), "call log says when a call was not saved: " + f.footer.getText());
            CallEvent enc = call("2026-01-02 03:04:06", 4242, 7, "Encrypted Group Call", "ENCRYPTED", 1000);
            edt(() -> { f.monitor.add(enc, tg, tg); f.monitor.table.setRowSelectionInterval(0, 0); f.monitor.logPlay.doClick(); });
            check(waitFor(() -> f.footer.getText().toLowerCase().contains("encrypted"), 2000), "encrypted call is explained: " + f.footer.getText());
        } finally {
            hooks.set(1);
            edt(() -> { AudioPlayer.stop(); f.dispose(); });
        }
    }

    static String text(JLabel l) {
        try { String[] t = {""}; edt(() -> t[0] = l.getText()); return t[0]; }
        catch (Exception e) { return ""; }
    }
    static String textOfButton(AbstractButton b) {
        try { String[] t = {""}; edt(() -> t[0] = b.getText()); return t[0]; }
        catch (Exception e) { return ""; }
    }

    static CallEvent call(String ts, int tg, int radio, String event, String details, long dur) {
        CallEvent e = new CallEvent();
        e.timestamp = ts; e.talkgroup = tg; e.radioId = radio; e.event = event; e.details = details; e.durationMs = dur; e.to = String.valueOf(tg);
        return e;
    }

    static byte[] tonePcm(int rate, double hz, double seconds) {
        int n = (int) (rate * seconds);
        byte[] b = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            int v = (int) Math.round(Math.sin(2 * Math.PI * hz * i / rate) * 16000);
            b[2 * i] = (byte) v; b[2 * i + 1] = (byte) (v >> 8);
        }
        return b;
    }

    static void writeWav(Path p, byte[] pcm) throws Exception {
        AudioFormat fmt = new AudioFormat(8000, 16, 1, true, false);
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(pcm), fmt, pcm.length / 2)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, p.toFile());
        }
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] o = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    static int[] shorts(byte[] le) {
        int[] s = new int[le.length / 2];
        for (int i = 0; i < s.length; i++) s[i] = (short) ((le[2 * i] & 0xff) | (le[2 * i + 1] << 8));
        return s;
    }

    static final class Fake implements AudioPlayer.Sink {
        final List<byte[]> writes = Collections.synchronizedList(new ArrayList<>());
        volatile int rate, channels;
        volatile boolean closed;
        public void open(int rate, int channels) { this.rate = rate; this.channels = channels; }
        public void write(byte[] b, int off, int len) { writes.add(Arrays.copyOfRange(b, off, off + len)); }
        public void pause() {}
        public void resume() {}
        public void close() { closed = true; }
        byte[] bytes() {
            int n = 0; for (byte[] w : writes) n += w.length;
            byte[] o = new byte[n]; int p = 0;
            for (byte[] w : writes) { System.arraycopy(w, 0, o, p, w.length); p += w.length; }
            return o;
        }
    }
}
