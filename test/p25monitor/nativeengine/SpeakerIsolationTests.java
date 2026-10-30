package p25monitor.nativeengine;

import p25monitor.App;
import p25monitor.CallEvent;
import p25monitor.StreamConfig;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Speaker volume / mute (Monitor page) affect ONLY the local speaker. The same voice is pushed through the engine's real
 * voice path (VoiceCallDecoder.emit -> speaker + feeds + Broadcastify Calls transmission + call recording, which is also
 * what OpenMHz uploads) with the speaker at 100 %, 30 %, 0 % and muted: the feed input, the Broadcastify Calls MP3 and
 * the recording must be bit-identical in all four, while the virtual sound card gets the expected level. Also: a change
 * applies live during playback, and both settings are remembered across restarts.
 * 1.4.2: the same with the speaker in QUEUE mode (and a second, overlapping call queued behind it): feed input,
 * Broadcastify Calls MP3 and recording stay byte-identical; the speaker hears both calls one after another.
 */
public final class SpeakerIsolationTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("  FAIL: " + what); } }

    /** Real-time paced virtual sound card that keeps every sample. */
    static final class Card implements AudioOut.Sink {
        final List<Short> out = Collections.synchronizedList(new ArrayList<>()); long t0, n;
        public void open(int rate) { t0 = System.nanoTime(); }
        public void write(byte[] b, int off, int len) {
            for (int i = off; i < off + len; i += 2) out.add((short) ((b[i] & 0xff) | (b[i + 1] << 8)));
            n += len / 2;
            long due = t0 + n * 125_000L, now = System.nanoTime();
            if (due > now) try { Thread.sleep((due - now) / 1_000_000, (int) ((due - now) % 1_000_000)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        public void close() {}
        short[] take() { synchronized (out) { short[] s = new short[out.size()]; for (int i = 0; i < s.length; i++) s[i] = out.get(i); out.clear(); return s; } }
    }

    static int peak(short[] s, int from, int to) { int p = 0; for (int i = Math.max(0, from); i < Math.min(s.length, to); i++) p = Math.max(p, Math.abs(s[i])); return p; }

    static boolean waitFor(java.util.function.BooleanSupplier b, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { if (b.getAsBoolean()) return true; Thread.sleep(5); } return b.getAsBoolean();
    }

    /** The voice: 1.6 s of a 700 Hz tone with a little noise, in 20 ms frames (as the decoder delivers it). */
    static final float[] VOICE = new float[8000 * 16 / 10];
    static { Random r = new Random(7); for (int i = 0; i < VOICE.length; i++) VOICE[i] = (float) (0.5 * Math.sin(2 * Math.PI * 700 * i / 8000.0) + 0.02 * r.nextGaussian()); }

    record Out(float[] feed, float[] recording, byte[] mp3, short[] speaker) {}

    static Out run(NativeEngine ne, Card card, MockBroadcastify m, int tg, Runnable midway) throws Exception {
        StreamConfig calls = new StreamConfig(); calls.name = "Calls"; calls.type = StreamConfig.Type.BROADCASTIFY_CALLS;
        calls.apiKey = m.key; calls.systemId = "77"; calls.minCallSec = 0.5; calls.includeUnlisted = true;
        StreamConfig feed = new StreamConfig(); feed.name = "Feed"; feed.type = StreamConfig.Type.BROADCASTIFY_FEED; feed.includeUnlisted = true;
        feed.host = "127.0.0.1"; feed.port = 9; feed.mount = "/x"; feed.password = "x";
        Streaming st = new Streaming(List.of(calls, feed), s -> {});   // feed thread not started: the test reads the feed's input itself
        ne.streaming = st;
        ActiveCall c = new ActiveCall();
        c.tg = tg; c.freq = 851_012_500L; c.start = System.currentTimeMillis(); c.src = 1234; c.ev = new CallEvent();
        card.take();
        long t0 = System.nanoTime();
        for (int k = 0, fr = 0; k < VOICE.length; k += 160, fr++) {
            if (fr % 9 == 0) {   // real time, one 180 ms LDU (9 voice frames) at a time, as the decoder delivers it
                long due = t0 + fr * 20_000_000L, now = System.nanoTime();
                if (due > now) Thread.sleep((due - now) / 1_000_000);
                if (midway != null && fr == 45) midway.run();   // 0.9 s into the call
            }
            float[] a = Arrays.copyOfRange(VOICE, k, k + 160);   // a fresh frame each time, as the decoder makes it
            ne.voice.emit(c, a, a.length, new int[]{tg, tg, 0, 0});
        }
        ne.calls.closeTx(c, "terminator");
        ne.speaker.release(c); c.ended = true; st.callEnded(c.id);
        Thread.sleep(900);   // the last ~300 ms pre-buffer plays out
        short[] spk = card.take();
        Streaming.Feed f = st.feeds.get(0);
        float[] fo = new float[0];
        for (int i = 0; i < 400; i++) { float[] b = f.mixer.pull(160); if (b == null) { if (fo.length >= VOICE.length) break; Thread.sleep(5); continue; } fo = concat(fo, b); }
        check(waitFor(() -> m.audio.containsKey(tg), 5000), "Broadcastify Calls upload arrived (TG " + tg + ")");
        st.stop();
        return new Out(fo, Arrays.copyOf(c.pcm, c.pcmLen), m.audio.get(tg), spk);
    }

    static short[] concatS(short[] a, short[] b) { short[] r = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, r, a.length, b.length); return r; }
    static float[] concat(float[] a, float[] b) { float[] r = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, r, a.length, b.length); return r; }

    public static void main(String[] args) throws Exception {
        if (!EngineLibs.mp3Available()) { System.out.println("SpeakerIsolationTests: MP3 encoder not found - run with -Dtrunkdeck.lib=lib"); System.exit(1); }
        Path home = Files.createTempDirectory("td-speaker"), profile = Files.createTempDirectory("td-speaker-profile");
        System.setProperty("trunkdeck.profile", profile.toString());   // never the real user profile
        App app = new App(home); app.load();
        NativeEngine ne = new NativeEngine(app, new NativeEngine.Listener() {
            public void callStarted(CallEvent e) {} public void callUpdated(CallEvent e) {} public void messages(int count, String last) {}
        });
        Card card = new Card();
        ne.audio = new AudioOut(card);
        check(ne.audio.open(), "virtual sound card open");
        MockBroadcastify m = new MockBroadcastify("spk-key");
        System.setProperty("trunkdeck.callsEndpoint", m.url());

        // 1. same voice with the speaker at 100 %, 30 %, 0 % and muted
        String[] names = {"100 %", "30 %", "0 %", "muted (slider 100 %)"};
        int[] vol = {100, 30, 0, 100}; boolean[] mute = {false, false, false, true};
        Out[] o = new Out[4];
        for (int i = 0; i < 4; i++) {
            ne.setSpeakerVolume(vol[i]); ne.setSpeakerMuted(mute[i]);
            check(Math.abs(ne.audio.volume() - vol[i] / 100f) < 1e-6 && ne.audio.muted() == mute[i], "speaker setting applied to the sound output: " + names[i]);
            o[i] = run(ne, card, m, 900 + i, null);
            System.out.printf("   speaker %-21s: speaker peak %5d, feed %d samples, recording %d samples, Calls MP3 %d bytes%n",
                    names[i], peak(o[i].speaker(), 0, o[i].speaker().length), o[i].feed().length, o[i].recording().length, o[i].mp3() == null ? -1 : o[i].mp3().length);
        }
        check(Arrays.equals(o[0].recording(), VOICE), "recording (also what OpenMHz uploads) = the decoded voice, unscaled");
        check(o[0].feed().length >= VOICE.length && Arrays.equals(Arrays.copyOf(o[0].feed(), VOICE.length), VOICE), "feed input = the decoded voice, unscaled");
        for (int i = 1; i < 4; i++) {
            check(Arrays.equals(o[i].feed(), o[0].feed()), "feed input identical with the speaker " + names[i]);
            check(Arrays.equals(o[i].recording(), o[0].recording()), "recording / OpenMHz audio identical with the speaker " + names[i]);
            check(o[i].mp3() != null && Arrays.equals(o[i].mp3(), o[0].mp3()), "Broadcastify Calls MP3 byte-identical with the speaker " + names[i]);
        }
        int p100 = peak(o[0].speaker(), 0, o[0].speaker().length), p30 = peak(o[1].speaker(), 0, o[1].speaker().length);
        check(p100 > 15000 && p100 < 20500, "speaker at 100 % plays the voice at full level (peak " + p100 + ")");
        int heard = 0; for (short v : o[0].speaker()) if (Math.abs(v) > 300) heard++;
        check(heard > VOICE.length * 0.9, "the whole call was heard on the speaker (" + heard + " loud samples of " + VOICE.length + ")");
        check(Math.abs(p30 - 0.3 * p100) < 0.03 * p100, "speaker at 30 % plays at 30 % (peak " + p30 + " vs " + p100 + ")");
        check(peak(o[2].speaker(), 0, o[2].speaker().length) == 0, "speaker at 0 % is silent");
        check(peak(o[3].speaker(), 0, o[3].speaker().length) == 0, "muted speaker is silent");

        // 1b. 1.4.2 speaker QUEUE mode: same voice, plus an overlapping call queued on the speaker only
        ne.speaker.start();
        String[] qn = {"QUEUE 100 %", "QUEUE muted"};
        for (int i = 0; i < 2; i++) {
            ne.setSpeakerVolume(100); ne.setSpeakerMuted(i == 1);
            ne.setSpeakerMode(NativeEngine.SpeakerMode.QUEUE, -1);
            ActiveCall other = new ActiveCall(); other.tg = 990; other.ev = new CallEvent();
            float[] ot = new float[160]; for (int k = 0; k < 160; k++) ot[k] = (float) (0.25 * Math.sin(2 * Math.PI * 300 * k / 8000.0));
            boolean[] first = {true};
            Out q = run(ne, card, m, 970 + i, () -> { if (first[0]) { first[0] = false; for (int k = 0; k < 50; k++) ne.speaker.play(other, ot, 160); ne.speaker.release(other); other.ended = true; } });
            Thread.sleep(1500);
            short[] spk = concatS(q.speaker(), card.take());
            check(Arrays.equals(q.feed(), o[0].feed()), "feed input identical with the speaker in " + qn[i]);
            check(Arrays.equals(q.recording(), o[0].recording()), "recording / OpenMHz audio identical with the speaker in " + qn[i]);
            check(q.mp3() != null && Arrays.equals(q.mp3(), o[0].mp3()), "Broadcastify Calls MP3 byte-identical with the speaker in " + qn[i]);
            int loud = 0, quiet = 0; for (int k = 0; k + 160 <= spk.length; k += 160) { int p = peak(spk, k, k + 160); if (p > 15000) loud++; else if (p > 6000 && p < 10000) quiet++; }
            System.out.printf("   speaker %-21s: call heard %d ms, queued overlapping call heard %d ms%n", qn[i], loud * 20, quiet * 20);
            if (i == 0) check(loud * 20 >= 1560 && quiet * 20 >= 960, "QUEUE: the whole call and then the overlapping call were heard (" + loud * 20 + " + " + quiet * 20 + " ms)");
            else check(peak(spk, 0, spk.length) == 0, "QUEUE muted: silent");
        }
        ne.setSpeakerMode(NativeEngine.SpeakerMode.PRIORITY, -1); ne.setSpeakerMuted(false);

        // 2. a change applies live, during playback
        ne.setSpeakerMuted(false); ne.setSpeakerVolume(100);
        Out live = run(ne, card, m, 950, () -> ne.setSpeakerVolume(20));
        short[] s = live.speaker();
        StringBuilder env = new StringBuilder(); for (int i = 0; i < s.length; i += 800) env.append(peak(s, i, i + 800)).append(' ');
        System.out.println("   envelope (100 ms): " + env);
        int first = -1, last = -1; for (int i = 0; i < s.length; i++) if (Math.abs(s[i]) > 300) { if (first < 0) first = i; last = i; }
        int early = peak(s, first + 400, first + 2000), late = peak(s, last - 2000, last - 400);
        System.out.println("   volume 100 % -> 20 % during playback: peak before " + early + ", after " + late);
        check(first >= 0 && early > 15000 && late > 0.15 * early && late < 0.25 * early, "volume change heard immediately (" + early + " -> " + late + ")");
        check(Arrays.equals(live.recording(), VOICE) && live.mp3() != null && Arrays.equals(live.mp3(), o[0].mp3()), "streams unchanged by the live change");

        // 3. remembered across restarts (and 1.3.4's "volume" setting carried over)
        ne.setSpeakerVolume(35); ne.setSpeakerMuted(true); app.saveSettings();
        App restarted = new App(home); restarted.load();
        NativeEngine again = new NativeEngine(restarted, null);
        check(again.speakerVolume() == 35 && again.speakerMuted(), "volume 35 % and mute remembered after a restart (" + again.speakerVolume() + ", " + again.speakerMuted() + ")");
        System.setProperty("trunkdeck.profile", Files.createTempDirectory("td-speaker-old").toString());
        App old = new App(home); old.load(); old.settings.setProperty("volume", "60");
        check(new NativeEngine(old, null).speakerVolume() == 60 && !new NativeEngine(old, null).speakerMuted(), "1.3.4's volume setting used until the slider is moved");
        ne.setSpeakerVolume(150); check(ne.speakerVolume() == 100 && ne.audio.volume() == 1f, "volume capped at 100 %");

        ne.audio.close(); m.stop();
        System.out.println("SpeakerIsolationTests: " + pass + " passed, " + fail + " failed (virtual sound card, mock Broadcastify server)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
