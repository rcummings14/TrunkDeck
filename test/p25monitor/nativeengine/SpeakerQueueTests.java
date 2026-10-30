package p25monitor.nativeengine;

import p25monitor.App;
import p25monitor.CallEvent;
import p25monitor.Talkgroup;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Speaker QUEUE mode (1.4.2): every call is heard, one after another in the order the calls started, never overlapping.
 * Real speaker path (SpeakerRouter + its 20 ms pump + AudioOut) into a real-time virtual sound card. Each test call is
 * a tone with its own level, so every 20 ms block on the card can be traced to the call it came from.
 * 1. ordering / overlap of 3 calls  2. silence gaps skipped  3. max-delay cap skips the oldest waiting audio (logged)
 * 4. memory bound (10 min limit), discarded after playing  5. mute / volume / per-talkgroup mute
 * 6. queue mode writes no files at all.
 */
public final class SpeakerQueueTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("  FAIL: " + what); } System.out.println((ok ? "  ok   " : "  FAIL ") + what); }

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

    /** A test call: starts at startMs, then a list of {tone ms, silence ms, tone ms, ...}; amplitude identifies it. */
    static final class Spec {
        final String name; final int tg; final long startMs; final float amp; final int[] parts;
        ActiveCall c; int frame; int partIdx, inPart; boolean done;
        Spec(String name, int tg, long startMs, float amp, int... parts) { this.name = name; this.tg = tg; this.startMs = startMs; this.amp = amp; this.parts = parts; }
        int totalFrames() { int n = 0; for (int p : parts) n += p / 20; return n; }
    }

    static NativeEngine ne; static Card card;

    /** Plays the specs in real time (20 ms frames), as decoders would deliver them; returns the max queuedSamples seen. */
    static long feed(Spec... specs) throws InterruptedException {
        long t0 = System.nanoTime(), maxQ = 0;
        for (int tick = 0; ; tick++) {
            long due = t0 + tick * 20_000_000L, now = System.nanoTime();
            if (due > now) Thread.sleep((due - now) / 1_000_000, (int) ((due - now) % 1_000_000));
            boolean any = false;
            for (Spec s : specs) {
                if (s.done) continue;
                any = true;
                if (tick * 20L < s.startMs) continue;
                if (s.c == null) { s.c = new ActiveCall(); s.c.tg = s.tg; s.c.freq = 851_000_000L + s.tg; s.c.start = System.currentTimeMillis(); s.c.ev = new CallEvent(); s.c.ev.talkgroup = s.tg; }
                if (s.partIdx >= s.parts.length) { ne.speaker.release(s.c); s.c.ended = true; s.done = true; continue; }
                boolean tone = s.partIdx % 2 == 0;
                float[] a = new float[160];
                if (tone) for (int i = 0; i < 160; i++) a[i] = (float) (s.amp * Math.sin(2 * Math.PI * 500 * (s.frame * 160 + i) / 8000.0));
                ne.speaker.play(s.c, a, 160);
                s.frame++;
                if (++s.inPart >= s.parts[s.partIdx] / 20) { s.partIdx++; s.inPart = 0; }
            }
            maxQ = Math.max(maxQ, ne.speaker.queue.queuedSamples());
            if (!any) return maxQ;
        }
    }

    /** Waits for the queue to play out (speaker idle) and returns what the card got. */
    static short[] drain(long maxMs) throws InterruptedException {
        long end = System.currentTimeMillis() + maxMs;
        while (System.currentTimeMillis() < end && (ne.speaker.queue.queuedSamples() > 0 || ne.audio.queuedMs() > 0)) Thread.sleep(20);
        Thread.sleep(400);
        return card.take();
    }

    /** Per 20 ms block of the card: index of the spec whose level matches (-1 = silence, -2 = no match = overlap/mix). */
    static int[] classify(short[] s, Spec... specs) {
        int[] r = new int[s.length / 160];
        for (int b = 0; b < r.length; b++) {
            int p = 0; for (int i = b * 160; i < b * 160 + 160; i++) p = Math.max(p, Math.abs(s[i]));
            if (p < 500) { r[b] = -1; continue; }
            r[b] = -2;
            for (int k = 0; k < specs.length; k++) if (Math.abs(p - specs[k].amp * 32767) < 0.06 * 32767) r[b] = k;
        }
        return r;
    }

    /** Runs: [spec index, blocks] with silence removed. */
    static List<int[]> runs(int[] cls) {
        List<int[]> r = new ArrayList<>();
        for (int c : cls) { if (c == -1) continue; if (!r.isEmpty() && r.get(r.size() - 1)[0] == c) r.get(r.size() - 1)[1]++; else r.add(new int[]{c, 1}); }
        return r;
    }
    static String show(List<int[]> runs, Spec... sp) { StringBuilder b = new StringBuilder(); for (int[] x : runs) b.append(x[0] >= 0 ? sp[x[0]].name : x[0] == -2 ? "MIX?" : "?").append(':').append(x[1] * 20).append("ms "); return b.toString().trim(); }

    static Map<String, String> snapshot(List<Path> roots) throws Exception {
        Map<String, String> m = new TreeMap<>();
        for (Path r : roots) {
            if (!Files.exists(r)) continue;
            try (var w = Files.walk(r)) { w.forEach(p -> { try { m.put(p.toString(), Files.isDirectory(p) ? "dir" : Files.size(p) + "/" + Files.getLastModifiedTime(p).toMillis()); } catch (Exception ignored) {} }); }
        }
        return m;
    }

    public static void main(String[] args) throws Exception {
        Path home = Files.createTempDirectory("td-queue"), profile = Files.createTempDirectory("td-queue-profile");
        System.setProperty("trunkdeck.profile", profile.toString());   // never the real user profile
        App app = new App(home); app.load();
        ne = new NativeEngine(app, new NativeEngine.Listener() {
            public void callStarted(CallEvent e) {} public void callUpdated(CallEvent e) {} public void messages(int count, String last) {}
        });
        card = new Card();
        ne.audio = new AudioOut(card);
        check(ne.audio.open(), "virtual sound card open");
        ne.setSpeakerVolume(100); ne.setSpeakerMuted(false);
        check(ne.speakerQueueMaxDelay() == 30, "max delay defaults to 30 s");
        ne.setSpeakerMode(NativeEngine.SpeakerMode.QUEUE, -1);
        check("QUEUE".equals(app.settings.getProperty("native.speaker")), "Queue mode stored in settings (native.speaker=QUEUE)");
        ne.speaker.start();
        // files: everything the app could write to, plus the temp dir; snapshotted after setup
        List<Path> roots = List.of(home, profile, Paths.get(System.getProperty("java.io.tmpdir")), Paths.get(System.getProperty("user.home"), ".trunkdeck"));
        Map<String, String> before = snapshot(roots);
        // the display follows the call being PLAYED: sample which TG is on the speaker every 20 ms
        List<Integer> shown = Collections.synchronizedList(new ArrayList<>());
        long[] maxWaiting = {0}, maxDelay = {0};
        Thread sampler = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                ActiveCall s = ne.speaker.speaker; int tg = s == null ? -1 : s.tg;
                synchronized (shown) { if (tg > 0 && (shown.isEmpty() || shown.get(shown.size() - 1) != tg)) shown.add(tg); }
                long[] q = ne.speakerQueueStatus();
                if (q != null) { maxWaiting[0] = Math.max(maxWaiting[0], q[0]); maxDelay[0] = Math.max(maxDelay[0], q[1]); }
                try { Thread.sleep(20); } catch (InterruptedException e) { return; }
            }
        });
        sampler.setDaemon(true); sampler.start();

        // 1. three overlapping calls
        System.out.println("1. three overlapping calls (A 0-1.5 s, B 0.3-1.8 s, C 0.6-1.6 s)");
        Spec a = new Spec("A", 101, 0, 0.15f, 1500), b = new Spec("B", 102, 300, 0.35f, 1500), c = new Spec("C", 103, 600, 0.6f, 1000);
        card.take();
        feed(a, b, c);
        short[] s1 = drain(8000);
        List<int[]> r1 = runs(classify(s1, a, b, c));
        System.out.println("   card: " + show(r1, a, b, c) + "   display: " + shown + "   max waiting " + maxWaiting[0] + ", max delay " + maxDelay[0] + " ms");
        check(r1.size() == 3 && r1.get(0)[0] == 0 && r1.get(1)[0] == 1 && r1.get(2)[0] == 2, "played in start order A, B, C, each in one piece, nothing mixed");
        check(r1.size() == 3 && Math.abs(r1.get(0)[1] - 75) <= 2 && Math.abs(r1.get(1)[1] - 75) <= 2 && Math.abs(r1.get(2)[1] - 50) <= 2, "every call heard complete (1.5 s, 1.5 s, 1.0 s)");
        int gaps = 0; { int[] cl = classify(s1, a, b, c); int first = -1, last = -1; for (int i = 0; i < cl.length; i++) if (cl[i] >= 0) { if (first < 0) first = i; last = i; } for (int i = first; i <= last; i++) if (cl[i] == -1) gaps++; }
        check(gaps <= 3, "next call follows immediately (" + gaps * 20 + " ms of silence between calls)");
        check(shown.equals(List.of(101, 102, 103)), "big display followed the call being played: " + shown);
        check(maxWaiting[0] == 2, "queue depth reported (2 waiting)");
        check(maxDelay[0] > 1500 && maxDelay[0] < 3500, "delay reported for the queued calls (max " + maxDelay[0] + " ms; C started 0.6 s, heard after 3 s)");
        check(ne.speaker.queue.queuedSamples() == 0 && ne.speaker.queue.idle(), "all audio released after it played");
        check(ne.speaker.queued.isEmpty(), "no queued call kept after playing");

        // 2. silence gaps are not queued
        System.out.println("2. gaps: A 0-2 s, D 0.1-2.4 s = 0.4 s voice, 1.5 s silence, 0.4 s voice");
        Spec a2 = new Spec("A", 101, 0, 0.15f, 2000), d = new Spec("D", 104, 100, 0.6f, 400, 1500, 400);
        feed(a2, d);
        short[] s2 = drain(8000);
        int[] cl2 = classify(s2, a2, d);
        int dFirst = -1, dLast = -1, dLoud = 0; for (int i = 0; i < cl2.length; i++) if (cl2[i] == 1) { if (dFirst < 0) dFirst = i; dLast = i; dLoud++; }
        System.out.println("   card: " + show(runs(cl2), a2, d) + "; D from first to last voice " + (dLast - dFirst + 1) * 20 + " ms");
        check(dLoud >= 38 && dLoud <= 42, "D's voice heard complete (" + dLoud * 20 + " ms)");
        check((dLast - dFirst + 1) * 20 <= 1100, "D's 1.5 s silence was skipped (D took " + (dLast - dFirst + 1) * 20 + " ms instead of 2.3 s)");

        // 3. cap: 5 s limit, five 2 s calls at once (10 s of audio)
        System.out.println("3. limit 5 s: five 2 s calls starting within 0.4 s (10 s of audio)");
        ne.setSpeakerQueueMaxDelay(5);
        int logBefore = ne.log.size();
        Spec[] five = new Spec[5]; for (int i = 0; i < 5; i++) five[i] = new Spec("E" + i, 110 + i, i * 100, 0.12f + 0.12f * i, 2000);
        maxDelay[0] = 0;
        long maxQ = feed(five);
        short[] s3 = drain(12000);
        List<int[]> r3 = runs(classify(s3, five));
        int heard = 0; for (int[] x : r3) heard += x[0] >= 0 ? x[1] : 0;
        List<String> logs = new ArrayList<>(ne.log).subList(logBefore, ne.log.size());
        List<String> skips = logs.stream().filter(l -> l.contains("Speaker queue: skipped")).toList();
        System.out.println("   card: " + show(r3, five) + "; max held " + maxQ / 8 + " ms; max delay " + maxDelay[0] + " ms");
        skips.forEach(l -> System.out.println("   log: " + l));
        check(maxQ <= 5 * 8000 + 160, "never more than 5 s of audio held (max " + maxQ / 8 + " ms)");
        // 2.4 s of live traffic + at most 5 s held = ~7.4 s can be heard of the 10 s
        check(heard * 20 <= 7800 && heard * 20 > 6000, "oldest waiting audio skipped: " + heard * 20 + " ms of 10 s heard (2.4 s live + 5 s limit)");
        Set<String> skippedTgs = new HashSet<>(); for (String l : skips) skippedTgs.add(l.replaceAll(".* of TG (\\d+) .*", "$1"));
        double skippedSec = 0; for (String l : skips) skippedSec += Double.parseDouble(l.replaceAll(".*skipped (the first )?([0-9.]+) s.*", "$2"));
        check(!skips.isEmpty() && skippedTgs.size() == skips.size(), "each skipped call logged once (" + skips.size() + " lines)");
        check(Math.abs(skippedSec + heard * 0.02 - 10.0) < 0.3, String.format(Locale.ROOT, "heard + skipped (logged) = all 10 s (%.1f + %.1f)", heard * 0.02, skippedSec));
        check(r3.size() >= 2 && r3.get(0)[0] == 0 && r3.get(0)[1] >= 98, "the call playing is heard complete (E0)");
        boolean ordered = true; for (int i = 1; i < r3.size(); i++) if (r3.get(i)[0] <= r3.get(i - 1)[0]) ordered = false;
        check(ordered, "what is heard is still in start order, no call repeated or mixed");
        check(maxDelay[0] <= 5600, "delay stays within the limit (max " + maxDelay[0] + " ms)");
        ne.setSpeakerQueueMaxDelay(30);

        // 4. memory: the 10 min maximum, 30 min of audio pushed at once; discarded on mode change
        System.out.println("4. memory bound");
        ne.setSpeakerQueueMaxDelay(10_000); check(ne.speakerQueueMaxDelay() == 600 && ne.speaker.queueMaxDelay() == 600_000, "limit capped at 10 min");
        ne.setSpeakerQueueMaxDelay(1); check(ne.speakerQueueMaxDelay() == 5, "limit at least 5 s");
        ne.setSpeakerQueueMaxDelay(600);
        float[] blk = new float[160]; for (int i = 0; i < 160; i++) blk[i] = (float) (0.3 * Math.sin(i * 0.4));
        List<ActiveCall> many = new ArrayList<>();
        long peakQ = 0;
        for (int k = 0; k < 30; k++) { ActiveCall x = new ActiveCall(); x.tg = 200 + k; x.ev = new CallEvent(); many.add(x); for (int f = 0; f < 3000; f++) { ne.speaker.play(x, blk, 160); } peakQ = Math.max(peakQ, ne.speaker.queue.queuedSamples()); }
        System.out.printf("   30 calls x 60 s pushed: held %.1f s (limit 600 s), %d calls tracked%n", peakQ / 8000.0, ne.speaker.queued.size());
        check(peakQ <= 600L * 8000 && peakQ > 590L * 8000, "held audio bounded by the 10 min limit (" + peakQ / 8000 + " s of 1800 s)");
        for (ActiveCall x : many) { ne.speaker.release(x); x.ended = true; }
        ne.setSpeakerMode(NativeEngine.SpeakerMode.PRIORITY, -1);
        check(ne.speaker.queue.queuedSamples() == 0 && ne.speaker.queue.idle() && ne.speaker.queued.isEmpty(), "leaving Queue mode discards everything queued");
        ne.setSpeakerMode(NativeEngine.SpeakerMode.QUEUE, -1);
        ne.setSpeakerQueueMaxDelay(30);
        Thread.sleep(500); card.take();

        // 5. mute / volume / per-talkgroup mute
        System.out.println("5. mute and volume");
        ne.setSpeakerVolume(30);
        Spec v1 = new Spec("V", 120, 0, 0.6f, 600), v2 = new Spec("W", 121, 100, 0.6f, 600);
        feed(v1, v2);
        short[] s5 = drain(5000);
        int p30 = 0, loud30 = 0; for (short v : s5) { p30 = Math.max(p30, Math.abs(v)); if (Math.abs(v) > 300) loud30++; }
        check(Math.abs(p30 - 0.3 * 0.6 * 32767) < 0.03 * 32767, "volume 30 % applies to queued calls (peak " + p30 + ")");
        check(loud30 > 0.85 * 2 * 600 * 8, "both calls heard at 30 %, one after another");
        ne.setSpeakerVolume(100); ne.setSpeakerMuted(true);
        Spec m1 = new Spec("M", 122, 0, 0.6f, 600), m2 = new Spec("N", 123, 100, 0.6f, 600);
        long tm = System.currentTimeMillis();
        feed(m1, m2);
        short[] s5m = drain(5000);
        int pm = 0; for (short v : s5m) pm = Math.max(pm, Math.abs(v));
        check(pm == 0, "speaker muted (M): queued calls are silent");
        check(ne.speaker.queue.idle() && System.currentTimeMillis() - tm < 4000, "muted queue still drains in real time (no backlog builds up behind mute)");
        ne.setSpeakerMuted(false);
        Talkgroup mt = new Talkgroup(); mt.decimal = 130; mt.muted = true;
        ActiveCall mc = new ActiveCall(); mc.tg = 130; mc.t = mt; mc.freq = 851_000_000L; mc.ev = new CallEvent();
        check("muted / locked out".equals(ne.calls.skipReason(mc)), "a muted talkgroup is still not followed in Queue mode, so never queued");
        ActiveCall u2u = new ActiveCall(); u2u.tg = 131; u2u.unitToUnit = true; u2u.ev = new CallEvent();
        check(!ne.speaker.play(u2u, blk, 160) && ne.speaker.queue.queuedSamples() == 0, "unit-to-unit audio is not queued");

        // 6. no files
        sampler.interrupt();
        Map<String, String> after = snapshot(roots);
        Set<String> changed = new TreeSet<>();
        for (var en : after.entrySet()) if (!en.getValue().equals(before.get(en.getKey()))) changed.add(en.getKey());
        for (String k : before.keySet()) if (!after.containsKey(k)) changed.add(k);
        changed.removeIf(p -> !(p.startsWith(home.toString()) || p.startsWith(profile.toString())) && !p.contains("trunkdeck") && !p.contains("td-") && !p.endsWith(".wav") && !p.endsWith(".pcm") && !p.endsWith(".raw"));
        System.out.println("6. files written while queueing " + (5 * 2 + 30 + 9) + "+ calls: " + (changed.isEmpty() ? "none" : changed));
        check(changed.isEmpty(), "Queue mode wrote no files (profile, home, temp dir, ~/.trunkdeck)");
        Path src = Paths.get("src/p25monitor/nativeengine");
        if (Files.isDirectory(src)) {
            String code = Files.readString(src.resolve("SpeakerRouter.java")) + Files.readString(src.resolve("CallMixer.java"));
            boolean io = false; for (String w : new String[]{"Files.", "FileOutputStream", "FileWriter", "RandomAccessFile", "FileChannel", "Path ", "java.io.File"}) if (code.contains(w)) io = true;
            check(!io, "speaker queue code has no file I/O at all (SpeakerRouter, CallMixer)");
        }

        ne.speaker.stop(); ne.audio.close();
        System.out.println("SpeakerQueueTests: " + pass + " passed, " + fail + " failed (virtual sound card)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
