package p25monitor.nativeengine;

import p25monitor.App;
import p25monitor.CallEvent;
import p25monitor.ListenMode;
import p25monitor.RadioSystem;
import p25monitor.StreamConfig;
import p25monitor.Talkgroup;
import p25monitor.TalkgroupCsv;
import p25monitor.p25.P25Framer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * 1.4.4: talkgroup edits take effect at once, without Apply / an engine restart, and never drop a feed's connection.
 * Through the engine's real voice path (VoiceCallDecoder.emit -> speaker + feeds + Broadcastify Calls transmission +
 * recording) and the real call lifecycle (CallManager):
 *  1. mute mid-call (one call at a time): off the speaker at once, the rest of the transmission still recorded and
 *     uploaded (MP3 byte-identical to the same transmission with no edit), the call ends with its transmission;
 *  2. mute mid-call in Queue mode: the muted call's queued audio is dropped (never played), the other call plays whole;
 *     muting the call being played silences it at once;
 *  3. un-mute / favorites / listening mode mid-call: the call is followed from then on; a waiting call that gets muted
 *     is dropped (not counted as missed); new calls see the edit; a name change shows at once;
 *  4. Broadcastify Calls assignment removed / added mid-transmission: applies from the NEXT transmission (no cut, no
 *     duplicate, no partial upload), uploads byte-identical to the unedited reference;
 *  5. feeds: a talkgroup added / removed goes in / out of the feed mix at once; record on mid-call applies to the call;
 *  6. talkgroups.csv: atomic writes (a reader never sees a partial file, no temp files left), edits round-trip;
 *  7. concurrency: edits from the UI thread while decoder threads read the settings and the streams are reconfigured;
 *  8. the feed's Icecast connection is the SAME socket (no new SOURCE) across talkgroup edits, stream renames and
 *     re-assignments, a restart for an unrelated system change (Apply) and a tuner restart; only a change of that
 *     stream's own server settings reconnects it (and only it); STOP disconnects.
 */
public final class LiveTalkgroupTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    /** Real-time paced virtual sound card that keeps every sample (and when it was written). */
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
        int size() { return out.size(); }
        short[] take() { synchronized (out) { short[] s = new short[out.size()]; for (int i = 0; i < s.length; i++) s[i] = out.get(i); out.clear(); return s; } }
    }

    static int loud(short[] s, int from, int to) { int k = 0; for (int i = Math.max(0, from); i < Math.min(s.length, to); i++) if (Math.abs(s[i]) > 300) k++; return k; }

    static boolean waitFor(java.util.function.BooleanSupplier b, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { if (b.getAsBoolean()) return true; Thread.sleep(5); } return b.getAsBoolean();
    }

    static float[] tone(double hz, double secs, long seed) {
        float[] v = new float[(int) (8000 * secs)]; Random r = new Random(seed);
        for (int i = 0; i < v.length; i++) v[i] = (float) (0.5 * Math.sin(2 * Math.PI * hz * i / 8000.0) + 0.02 * r.nextGaussian());
        return v;
    }
    static final float[] VOICE = tone(700, 1.6, 7);

    static NativeEngine ne;
    static App app;
    static RadioSystem sys;
    static Card card;
    static final List<String> log = Collections.synchronizedList(new ArrayList<>());

    static Talkgroup tg(int n, String name, String... streams) {
        Talkgroup t = new Talkgroup(); t.decimal = n; t.alphaTag = name; t.streams.addAll(List.of(streams)); sys.talkgroups.add(t); return t;
    }
    static Talkgroup find(int n) { return sys.find(n); }

    /** What the UI does after an edit (MainFrame.talkgroupsEdited), then waits until calls in progress have it. */
    static void edited() throws Exception { ne.updateTalkgroups(sys.talkgroups); ne.awaitLive(); }

    /** A call being followed (as VoiceCallDecoder.start leaves it), with a decoder that is never fed. */
    static ActiveCall followed(int tgNo) {
        ActiveCall c = new ActiveCall();
        c.tg = tgNo; c.freq = 851_012_500L + tgNo * 12_500L; c.start = c.lastSeen = c.lastVoice = System.currentTimeMillis(); c.src = 1234; c.channel = (1 << 12) | tgNo;
        c.ev = new CallEvent(); c.ev.talkgroup = tgNo;
        c.t = ne.tg(tgNo); c.priority = c.t != null ? c.t.priority : 100;
        c.eligible = true; c.followed = true; c.voiceFrames = 100;
        c.vd = new ChannelDecoder(48000, 0, 0, new P25Framer.Listener() {}, 0);
        ne.callMap.put("T" + c.id, c);
        return c;
    }

    /** A grant as the control channel makes it (CallManager.newCall). */
    static ActiveCall granted(int tgNo) {
        ActiveCall c = new ActiveCall();
        c.tg = tgNo; c.freq = 851_512_500L + tgNo * 12_500L; c.start = c.lastSeen = System.currentTimeMillis(); c.src = 55; c.channel = (1 << 12) | (tgNo & 0xfff);
        c.t = ne.tg(tgNo); c.priority = c.t != null ? c.t.priority : 100;
        ne.callMap.put("G" + c.id, c);
        synchronized (ne) { ne.calls.newCall(c); }
        return c;
    }

    /** Emits audio in real time (one 180 ms LDU of 9 frames at a time, as the decoder delivers it). at: runs once at that frame. */
    static void voice(ActiveCall c, float[] v, boolean realTime, int atFrame, ThrowingRunnable at) throws Exception {
        long t0 = System.nanoTime();
        for (int k = 0, fr = 0; k + 160 <= v.length; k += 160, fr++) {
            if (fr % 9 == 0 && realTime) {
                long due = t0 + fr * 20_000_000L, now = System.nanoTime();
                if (due > now) Thread.sleep((due - now) / 1_000_000);
            }
            if (at != null && fr == atFrame) at.run();
            float[] a = Arrays.copyOfRange(v, k, k + 160);
            c.lastVoice = System.currentTimeMillis();
            ne.voice.emit(c, a, a.length, new int[]{c.tg, c.tg, 0, 0});
        }
    }
    interface ThrowingRunnable { void run() throws Exception; }

    static float[] concat(float[]... a) { int n = 0; for (float[] x : a) n += x.length; float[] r = new float[n]; int o = 0; for (float[] x : a) { System.arraycopy(x, 0, r, o, x.length); o += x.length; } return r; }

    public static void main(String[] args) throws Exception {
        if (!EngineLibs.mp3Available()) { System.out.println("LiveTalkgroupTests: MP3 encoder not found - run with -Dtrunkdeck.lib=lib"); System.exit(1); }
        Path home = Files.createTempDirectory("td-live"), profile = Files.createTempDirectory("td-live-profile"), recs = Files.createTempDirectory("td-live-rec");
        System.setProperty("trunkdeck.profile", profile.toString());   // never the real user profile
        System.setProperty("trunkdeck.recordings", recs.toString());
        app = new App(home); app.load();
        app.settings.setProperty("listenMode", "ALL");
        ne = new NativeEngine(app, new NativeEngine.Listener() {
            public void callStarted(CallEvent e) {} public void callUpdated(CallEvent e) {} public void messages(int count, String last) {}
        });
        card = new Card();
        ne.audio = new AudioOut(card);
        check(ne.audio.open(), "virtual sound card open");
        ne.setSpeakerVolume(100); ne.setSpeakerMuted(false);
        MockBroadcastify m = new MockBroadcastify("live-key");
        System.setProperty("trunkdeck.callsEndpoint", m.url());
        sys = new RadioSystem(); sys.name = "Live test";
        tg(100, "FIRE DISP", "Calls"); tg(101, "REFERENCE", "Calls"); tg(300, "QUEUE A"); tg(301, "QUEUE B"); tg(302, "QUEUE C");
        tg(400, "UNMUTE ME").muted = true; tg(401, "FAV ME"); tg(402, "WAITING"); tg(403, "NEW CALL");
        tg(500, "CALLS EDIT", "Calls"); tg(501, "CALLS REF", "Calls"); tg(600, "FEED EDIT", "Feed"); tg(700, "RECORD EDIT");
        ne.sys = sys;
        StreamConfig calls = new StreamConfig(); calls.name = "Calls"; calls.type = StreamConfig.Type.BROADCASTIFY_CALLS;
        calls.apiKey = m.key; calls.systemId = "77"; calls.minCallSec = 0.5;
        StreamConfig feed = new StreamConfig(); feed.name = "Feed"; feed.type = StreamConfig.Type.BROADCASTIFY_FEED;
        feed.host = "127.0.0.1"; feed.port = 9; feed.mount = "/x"; feed.password = "x";
        Streaming st = new Streaming(List.of(calls, feed), s -> log.add(s));   // feed thread not started: the test reads the feed's input itself
        ne.streaming = st;
        ne.updateTalkgroups(sys.talkgroups);
        ne.running = true;   // the engine "runs" (no tuners): edits reach the calls in progress

        // ---------------------------------------------------------------- 1. mute mid-call, one call at a time
        System.out.println("1. mute mid-call (speaker: one call at a time)");
        ne.setSpeakerMode(NativeEngine.SpeakerMode.PRIORITY, -1);
        ActiveCall ref = followed(101);
        voice(ref, VOICE, false, -1, null);
        ne.calls.closeTx(ref, "terminator"); ne.calls.endCall(ref, "call over");
        check(waitFor(() -> m.audio.containsKey(101), 5000), "reference upload (no edit) arrived");
        Thread.sleep(800); card.take();
        ActiveCall c = followed(100);
        int[] atMute = {-1};
        voice(c, VOICE, true, 40, () -> {   // 0.8 s into the transmission
            check(ne.speaker.speaker == c && loud(card.take(), 0, Integer.MAX_VALUE) > 2000, "TG 100 heard on the speaker before the mute");
            find(100).muted = true;
            edited();
            atMute[0] = card.size();
        });
        check(c.endAfterTx && !c.ended, "muted mid-call: off the speaker, the call ends with its transmission (not before)");
        check(ne.speaker.speaker != c, "the speaker let go of the muted call at once");
        Thread.sleep(500);
        short[] after = card.take();
        int late = loud(after, atMute[0] + 480, Integer.MAX_VALUE);   // 60 ms: the frame being written fades out
        check(late == 0, "nothing of TG 100 played after the mute (" + late + " loud samples later than 60 ms)");
        ne.calls.closeTx(c, "terminator");
        check(c.ended, "the call ended when its transmission closed");
        check(waitFor(() -> m.audio.containsKey(100), 5000), "the transmission in progress was still uploaded (not cut)");
        check(Arrays.equals(m.audio.get(100), m.audio.get(101)), "its MP3 is byte-identical to the same transmission with no edit");
        check(c.pcmLen == VOICE.length && Arrays.equals(Arrays.copyOf(c.pcm, c.pcmLen), VOICE), "its recording / OpenMHz audio is the whole transmission");
        check(ne.recentLog().stream().anyMatch(l -> l.contains("TG 100 (FIRE DISP): muted") && l.contains("off the speaker now")), "logged with the talkgroup's name");
        ActiveCall again = granted(100);
        check(!again.eligible && "MUTED / LOCKED OUT".equals(again.ev.details), "a new call on the muted talkgroup is not followed");
        ne.calls.endCall(again, "grant ended");
        find(100).muted = false; edited();

        // ---------------------------------------------------------------- 2. Queue mode
        System.out.println("2. mute in Queue mode");
        ne.setSpeakerMode(NativeEngine.SpeakerMode.QUEUE, -1);
        ne.speaker.start();
        Thread.sleep(300); card.take();
        ActiveCall a = followed(300), b = followed(301);
        float[] va = tone(700, 1.0, 1), vb = tone(1300, 1.0, 2);
        Thread tb = new Thread(() -> { try { voice(b, vb, true, -1, null); } catch (Exception x) { throw new RuntimeException(x); } });
        tb.start();
        voice(a, va, true, 25, () -> {   // A playing, B queued behind it
            check(ne.speaker.queue.has(b.id), "TG 301 queued behind TG 300");
            find(301).muted = true; edited();
            check(!ne.speaker.queue.has(b.id), "muting TG 301 dropped its queued audio");
        });
        tb.join();
        ne.calls.closeTx(a, "terminator"); ne.calls.endCall(a, "call over");
        ne.calls.closeTx(b, "terminator");
        check(b.ended && b.pcmLen == vb.length, "TG 301 still recorded whole, ended with its transmission");
        check(waitFor(() -> !ne.speaker.queue.has(a.id) && ne.speaker.displayState() == 0, 5000), "queue played out");
        Thread.sleep(600);
        short[] q = card.take();
        int heard = loud(q, 0, q.length);
        check(heard > va.length * 0.85 && heard < va.length * 1.15, "the speaker played TG 300 only (" + heard + " loud samples, TG 300 has " + va.length + ")");
        ActiveCall cq = followed(302);
        int[] at2 = {-1};
        voice(cq, tone(900, 1.0, 3), true, 25, () -> {
            check(ne.speaker.speaker == cq, "TG 302 playing from the queue");
            find(302).muted = true; edited(); at2[0] = card.size();
        });
        Thread.sleep(500);
        short[] q2 = card.take();
        check(loud(q2, at2[0] + 480, Integer.MAX_VALUE) == 0, "muting the call being played (Queue mode) silences it at once");
        ne.calls.closeTx(cq, "terminator");
        ne.setSpeakerMode(NativeEngine.SpeakerMode.PRIORITY, -1);
        find(301).muted = false; find(302).muted = false; edited();

        // ---------------------------------------------------------------- 3. un-mute, favorites, waiting calls, names
        System.out.println("3. un-mute / favorites / waiting / names");
        ActiveCall u = granted(400);
        check(!u.eligible, "TG 400 muted: its call is not followed");
        find(400).muted = false; edited();
        check(u.eligible && u.waiting && u.txDone, "un-muted mid-call: followed from now on (asks for a tuner; none in this test)");
        check(ne.recentLog().stream().anyMatch(l -> l.contains("TG 400 (UNMUTE ME): now followed")), "logged");
        long missedBefore = ne.missed.values().stream().mapToLong(x -> x).sum();
        find(400).muted = true; edited();
        check(u.ended, "a waiting call whose talkgroup gets muted is dropped");
        check(ne.missed.values().stream().mapToLong(x -> x).sum() == missedBefore, "... and not counted as a missed call");
        app.settings.setProperty("listenMode", ListenMode.FAVORITES.name());
        ActiveCall fv = granted(401);
        check(!fv.eligible && "NOT A FAVORITE".equals(fv.ev.details), "favorites only: TG 401 not followed");
        find(401).favorite = true; edited();
        check(fv.eligible, "made a favorite mid-call: followed");
        app.settings.setProperty("listenMode", ListenMode.ALL.name());
        ne.calls.endCall(fv, "grant ended");
        ActiveCall named = followed(403);
        find(403).alphaTag = "RENAMED"; find(403).priority = 5; edited();
        check("RENAMED".equals(named.alias()) && named.priority == 5, "name / priority change reaches the call in progress");
        check("RENAMED".equals(ne.tg(403).alphaTag), "... and new calls");
        ne.calls.endCall(named, "call over");

        // ---------------------------------------------------------------- 4. Broadcastify Calls assignment mid-transmission
        System.out.println("4. Broadcastify Calls assignment edited mid-transmission");
        ActiveCall r5 = followed(501);
        voice(r5, VOICE, false, -1, null); ne.calls.closeTx(r5, "terminator"); ne.calls.endCall(r5, "call over");
        check(waitFor(() -> m.audio.containsKey(501), 5000), "reference upload");
        ActiveCall s5 = followed(500);
        voice(s5, VOICE, false, 45, () -> { find(500).streams.remove("Calls"); edited(); });
        ne.calls.closeTx(s5, "terminator");
        check(waitFor(() -> m.putsByTg.getOrDefault(500, 0) == 1, 5000), "assignment removed mid-transmission: that transmission is still uploaded (once)");
        check(Arrays.equals(m.audio.get(500), m.audio.get(501)), "... byte-identical to the unedited reference");
        voice(s5, VOICE, false, -1, null); ne.calls.closeTx(s5, "terminator");
        voice(s5, VOICE, false, 45, () -> { find(500).streams.add("Calls"); edited(); });
        ne.calls.closeTx(s5, "terminator");
        Thread.sleep(1500);
        check(m.putsByTg.getOrDefault(500, 0) == 1, "next transmission not uploaded; one assigned mid-way not uploaded part-way (no partial upload)");
        Thread.sleep(1100);   // a new start second: Broadcastify (and the mock) tell transmissions apart by tg + start time
        voice(s5, VOICE, false, -1, null); ne.calls.closeTx(s5, "terminator");
        check(waitFor(() -> m.putsByTg.getOrDefault(500, 0) == 2, 5000), "the transmission after the assignment is uploaded (puts " + m.putsByTg.get(500) + ", registers " + m.registersByTg.get(500) + ")");
        check(Arrays.equals(m.audio.get(500), m.audio.get(501)), "... byte-identical to the reference");
        check(m.registersByTg.getOrDefault(500, 0) == 2, "exactly 2 uploads registered for TG 500 (no duplicate)");
        ne.calls.endCall(s5, "call over");

        // ---------------------------------------------------------------- 5. feed mix + record
        System.out.println("5. feed mix and record, live");
        Streaming.Feed fd = st.feeds.get(0);
        long q0 = fd.mixer.queuedSamples();
        ActiveCall f6 = followed(600);
        float[] half = tone(500, 0.5, 4);
        voice(f6, half, false, -1, null);
        long q1 = fd.mixer.queuedSamples();
        check(q1 - q0 == half.length, "TG 600 in the feed mix (" + (q1 - q0) + " samples)");
        find(600).streams.remove("Feed"); edited();
        voice(f6, half, false, -1, null);
        check(fd.mixer.queuedSamples() == q1, "removed from the feed: out of the mix at once");
        find(600).streams.add("Feed"); edited();
        voice(f6, half, false, -1, null);
        check(fd.mixer.queuedSamples() - q1 == half.length, "added back: in the mix at once");
        ne.calls.endCall(f6, "call over");
        ActiveCall rc = followed(700);
        voice(rc, half, false, -1, null);
        find(700).record = true; edited();
        voice(rc, half, false, -1, null);
        ne.calls.endCall(rc, "call over");
        check(waitFor(() -> { try (var w = Files.list(recs)) { return w.anyMatch(p -> p.getFileName().toString().contains("TO_700")); } catch (Exception x) { return false; } }, 5000),
                "record ticked mid-call: the call is recorded");

        // ---------------------------------------------------------------- 6. talkgroups.csv
        System.out.println("6. talkgroups.csv auto-save");
        Path dir = Files.createTempDirectory("td-live-csv"), csv = dir.resolve("talkgroups.csv");
        List<Talkgroup> va1 = new ArrayList<>(), vb1 = new ArrayList<>();
        for (int i = 1; i <= 300; i++) { Talkgroup x = new Talkgroup(); x.decimal = i; x.alphaTag = "TG " + i; x.muted = true; x.streams.add("Feed"); va1.add(x);
            Talkgroup y = x.copy(); y.muted = false; y.streams.clear(); y.priority = 7; vb1.add(y); }
        String ta = TalkgroupCsv.render(va1), tb2 = TalkgroupCsv.render(vb1);
        TalkgroupCsv.writeAtomic(csv, ta);
        AtomicBoolean stop = new AtomicBoolean(); AtomicInteger reads = new AtomicInteger(), bad = new AtomicInteger();
        Thread rd = new Thread(() -> {
            while (!stop.get()) try {
                List<Talkgroup> l = TalkgroupCsv.read(csv).talkgroups; reads.incrementAndGet();
                boolean allA = l.size() == 300 && l.stream().allMatch(t -> t.muted && t.streams.contains("Feed"));
                boolean allB = l.size() == 300 && l.stream().allMatch(t -> !t.muted && t.streams.isEmpty() && t.priority == 7);
                if (!allA && !allB) { if (bad.incrementAndGet() == 1) System.out.println("     bad read: " + l.size() + " rows, first " + (l.isEmpty() ? "-" : l.get(0).muted + "/" + l.get(0).streams + "/" + l.get(0).priority)); }
            } catch (Exception x) { if (bad.incrementAndGet() == 1) System.out.println("     bad read: " + x); }
        });
        rd.start();
        ExecutorService ws = Executors.newFixedThreadPool(3);
        List<Future<?>> fs = new ArrayList<>();
        int writes = 0;
        while ((reads.get() < 50 || writes < 300) && writes < 20000) {
            for (int i = 0; i < 30; i++, writes++) { String t = writes % 2 == 0 ? ta : tb2; fs.add(ws.submit(() -> { TalkgroupCsv.writeAtomic(csv, t); return null; })); }
            for (Future<?> x : fs) x.get();
            fs.clear();
        }
        stop.set(true); rd.join(); ws.shutdown();
        check(bad.get() == 0 && reads.get() >= 50, "a reader never saw a partial / mixed file (" + reads.get() + " reads during " + writes + " concurrent saves)");
        try (var w = Files.list(dir)) { check(w.filter(p -> !p.equals(csv)).count() == 0, "no temporary files left behind"); }
        RadioSystem saved = new RadioSystem(); saved.name = "Saved";
        Talkgroup e1 = new Talkgroup(); e1.decimal = 1234; e1.alphaTag = "EDITED"; e1.muted = true; e1.favorite = true; e1.record = true; e1.priority = 3; e1.category = "Fire"; e1.streams.addAll(List.of("Calls", "Feed"));
        saved.talkgroups.add(e1);
        Path sd = Files.createTempDirectory("td-live-sys");
        saved.save(sd);
        Talkgroup back = TalkgroupCsv.read(sd.resolve("talkgroups.csv")).talkgroups.get(0);
        check(back.muted && back.favorite && back.record && back.priority == 3 && "EDITED".equals(back.alphaTag) && "Fire".equals(back.category)
                && back.streams.equals(new TreeSet<>(List.of("Calls", "Feed"))), "every talkgroup setting survives save + reload");

        // ---------------------------------------------------------------- 7. concurrency
        System.out.println("7. concurrency: UI edits while decoders read and streams are reconfigured");
        AtomicReference<Throwable> err = new AtomicReference<>();
        AtomicBoolean go = new AtomicBoolean(true);
        List<Thread> ts = new ArrayList<>();
        for (int d = 0; d < 3; d++) {
            final int seed = d;
            ts.add(new Thread(() -> {
                Random r = new Random(seed); float[] blk = tone(600, 0.02, seed);
                ActiveCall dc = followed(800 + seed);
                try {
                    while (go.get()) {
                        Talkgroup t = ne.tg(800 + r.nextInt(20));
                        Streaming s2 = ne.streaming;
                        s2.wantsTransmissions(t); s2.audio(dc.id, t, 800 + seed, blk, blk.length);
                        if (t != null) { t.streams.size(); for (String x : t.streams) x.length(); }
                        dc.lastVoice = System.currentTimeMillis();
                        if (!dc.ended) ne.voice.emit(dc, blk, blk.length, new int[]{dc.tg, dc.tg, 0, 0});
                        if (r.nextInt(50) == 0) ne.calls.closeTx(dc, "terminator");
                    }
                } catch (Throwable x) { err.compareAndSet(null, x); }
            }, "decoder-" + d));
        }
        for (int i = 0; i < 20; i++) tg(800 + i, "C" + i, "Calls");
        ne.updateTalkgroups(sys.talkgroups);
        Thread ui = new Thread(() -> {   // owns sys.talkgroups, as the Swing thread does
            Random r = new Random(1);
            try {
                for (int i = 0; i < 3000; i++) {
                    Talkgroup t = find(800 + r.nextInt(20));
                    switch (r.nextInt(5)) {
                        case 0 -> t.muted = !t.muted;
                        case 1 -> { if (!t.streams.remove("Feed")) t.streams.add("Feed"); }
                        case 2 -> { if (!t.streams.remove("Calls")) t.streams.add("Calls"); }
                        case 3 -> t.priority = 1 + r.nextInt(100);
                        default -> t.alphaTag = "C" + i;
                    }
                    if (i % 100 == 0) { Talkgroup x = new Talkgroup(); x.decimal = 5000 + i; sys.talkgroups.add(x); sys.talkgroups.sort(Comparator.comparingInt(y -> y.decimal)); }
                    ne.updateTalkgroups(sys.talkgroups);
                }
            } catch (Throwable x) { err.compareAndSet(null, x); }
        }, "ui");
        Thread rec = new Thread(() -> {
            try {
                for (int i = 0; i < 200; i++) {
                    StreamConfig f2 = feed.copy(); f2.name = i % 2 == 0 ? "Feed" : "Feed renamed";
                    st.reconfigure(List.of(calls, f2), CallMixer.Mode.QUEUE, 30_000);
                    Thread.sleep(2);
                }
            } catch (Throwable x) { err.compareAndSet(null, x); }
        }, "streams");
        for (Thread t : ts) t.start();
        ui.start(); rec.start(); ui.join(); rec.join();
        go.set(false); for (Thread t : ts) t.join();
        ne.awaitLive();
        if (err.get() != null) err.get().printStackTrace();
        check(err.get() == null, "no exception in 3000 edits x 3 decoder threads x 200 stream reconfigurations");
        boolean consistent = true;
        for (int i = 0; i < 20; i++) { Talkgroup l = find(800 + i), e2 = ne.tg(800 + i); if (e2 == l || e2.muted != l.muted || !e2.streams.equals(l.streams) || e2.priority != l.priority) consistent = false; }
        check(consistent, "the engine's copies match the final edits (and are copies, not the UI's objects)");
        check(st.feeds.size() == 1 && st.feeds.get(0) == fd, "the feed object survived 200 renames (same connection)");
        ne.running = false;
        st.stop();
        m.http.stop(0);

        // ---------------------------------------------------------------- 8. feed connection kept
        feedConnection(home);

        System.out.println("LiveTalkgroupTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** The real engine on the built-in test signal, streaming to two mock Icecast servers. */
    static void feedConnection(Path home) throws Exception {
        System.out.println("8. feed connection across edits and restarts (real engine, built-in test signal)");
        MockIcecast m1 = new MockIcecast("pw1"), m2 = new MockIcecast("pw2"), m3 = new MockIcecast("pw3");
        App a2 = new App(home); a2.load();
        StreamConfig f1 = new StreamConfig(); f1.name = "Feed One"; f1.type = StreamConfig.Type.BROADCASTIFY_FEED; f1.host = "127.0.0.1"; f1.port = m1.port(); f1.mount = "/one"; f1.password = "pw1"; f1.feedId = "111";
        StreamConfig f2 = new StreamConfig(); f2.name = "Feed Two"; f2.type = StreamConfig.Type.ICECAST; f2.host = "127.0.0.1"; f2.port = m2.port(); f2.mount = "/two"; f2.password = "pw2";
        StreamConfig cl = new StreamConfig(); cl.name = "Calls"; cl.type = StreamConfig.Type.BROADCASTIFY_CALLS; cl.apiKey = "k"; cl.systemId = "1";
        a2.streams.clear(); a2.streams.addAll(List.of(f1, f2, cl));
        RadioSystem s = new RadioSystem(); s.name = "Sim"; s.controlChannelsMHz.add(851.0125);
        for (int n : new int[]{2001, 2002, 2003}) { Talkgroup t = new Talkgroup(); t.decimal = n; t.streams.addAll(List.of("Feed One", "Feed Two")); s.talkgroups.add(t); }
        NativeEngine e = new NativeEngine(a2, new NativeEngine.Listener() {
            public void callStarted(CallEvent ev) {} public void callUpdated(CallEvent ev) {} public void messages(int count, String last) {}
        });
        e.audioSinkOverride = new Card();
        e.start(s, NativeEngine.Mode.SIMULATED);
        check(waitFor(() -> m1.accepted().size() == 1 && m2.accepted().size() == 1, 8000), "both feeds connected (one SOURCE each)");
        MockIcecast.Conn c1 = m1.accepted().get(0), c2 = m2.accepted().get(0);
        BroadcastifyCalls up = e.streaming.broadcastifyCalls().get(0);
        Thread.sleep(2500);
        // talkgroup edits: mute, un-assign, re-assign, rename
        s.talkgroups.get(0).muted = true; e.updateTalkgroups(s.talkgroups); e.awaitLive();
        s.talkgroups.get(1).streams.remove("Feed One"); e.updateTalkgroups(s.talkgroups); e.awaitLive();
        s.talkgroups.get(1).streams.add("Feed One"); s.talkgroups.get(2).alphaTag = "RENAMED"; e.updateTalkgroups(s.talkgroups); e.awaitLive();
        Thread.sleep(2000);
        check(m1.conns.size() == 1 && m2.conns.size() == 1 && c1.closedMs == 0 && c2.closedMs == 0, "talkgroup edits: same connections, no new SOURCE");
        // stream edits that are not the feed's connection: rename, include unlisted, (re)assignment
        f1.name = "Feed One renamed"; f1.includeUnlisted = true;
        for (Talkgroup t : s.talkgroups) if (t.streams.remove("Feed One")) t.streams.add("Feed One renamed");
        e.updateTalkgroups(s.talkgroups);
        List<String> ch = e.reconfigureStreams();
        Thread.sleep(1500);
        check(m1.conns.size() == 1 && c1.closedMs == 0 && m2.conns.size() == 1 && c2.closedMs == 0, "stream renamed / include-unlisted ticked: same connection (" + ch + ")");
        // an unrelated Apply: a system setting changed, the radio engine restarts
        s.trafficChannels = 7; s.nativeModulation = "c4fm";
        long before = c1.bytes().length;
        e.stop(true);
        Thread.sleep(1500);
        long during = c1.bytes().length;
        e.start(s, NativeEngine.Mode.SIMULATED);
        Thread.sleep(3000);
        check(m1.conns.size() == 1 && c1.closedMs == 0 && m2.conns.size() == 1 && c2.closedMs == 0, "Apply (engine restart for a system setting): same connections, no new SOURCE");
        check(during > before && c1.bytes().length > during, "the feed kept sending (silence) while the engine restarted");
        check(c1.maxGapMs() < 1500, "no gap in the feed's data (" + c1.maxGapMs() + " ms max)");
        check(e.streaming.broadcastifyCalls().get(0) == up, "the Broadcastify Calls uploader was kept too");
        // a tuner restart (Engine page: apply suggested PPM/gain) is the same restart
        e.stop(true); e.start(s, NativeEngine.Mode.SIMULATED); Thread.sleep(2000);
        check(m1.conns.size() == 1 && c1.closedMs == 0, "tuner restart: same connection");
        // the feed's own server settings: only that feed reconnects
        f2.port = m3.port(); f2.password = "pw3";
        ch = e.reconfigureStreams();
        check(waitFor(() -> m3.accepted().size() == 1, 8000), "Feed Two's server changed: it connects to the new server (" + ch + ")");
        check(waitFor(() -> c2.closedMs != 0, 3000), "... and leaves the old one");
        check(m1.conns.size() == 1 && c1.closedMs == 0, "Feed One (unchanged) kept its connection");
        f1.mount = "/one-b";   // (the mock lists a connection before it has read its request: wait for both)
        ch = e.reconfigureStreams();
        check(waitFor(() -> m1.accepted().size() == 2 && "SOURCE /one-b HTTP/1.0".equals(m1.accepted().get(1).requestLine), 8000), "Feed One's mount changed: it reconnects, to the new mount (" + ch + "; " + m1.conns.stream().map(x -> x.requestLine + (x.refused ? " refused" : "") + (x.closedMs != 0 ? " closed" : "")).toList() + ")");
        check(waitFor(() -> c1.closedMs != 0, 3000), "... the old connection is closed");
        Thread.sleep(1500);
        check(m1.conns.size() == 2, "... exactly one new SOURCE");
        check(m3.conns.size() == 1, "Feed Two not touched by Feed One's change");
        f2.enabled = false;
        e.reconfigureStreams();
        check(waitFor(() -> m3.accepted().get(0).closedMs != 0, 3000), "a stream disabled: only it disconnects");
        e.stop();
        check(waitFor(() -> m1.accepted().get(1).closedMs != 0, 3000), "STOP disconnects the feeds");
        m1.stop(); m2.stop(); m3.stop();
    }
}
