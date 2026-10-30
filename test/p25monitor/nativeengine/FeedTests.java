package p25monitor.nativeengine;

import p25monitor.StreamConfig;
import p25monitor.p25.P25Framer;
import p25monitor.sdr.IqFileSource;
import java.nio.file.*;
import java.util.*;

/**
 * Broadcastify Feeds / Icecast stream against a mock Icecast server, checked with ffprobe / ffmpeg:
 * declared format = what SDRTrunk sends to Broadcastify (MP3, 16000 Hz, 16 kb/s CBR, mono; same SOURCE request and headers), a 1 kHz tone comes out at 1 kHz,
 * N seconds of calls make N seconds of audio, real recordings keep their pitch, the connection stays up through a long
 * idle period with no gaps, and it reconnects by itself (1, 2, 5, 10 s backoff) after a drop. Also checks the pitch and
 * length of Broadcastify Calls and OpenMHz MP3s. -DfeedIdleSecs=N sets the idle period (default 90 s).
 */
public final class FeedTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    // ------------------------------------------------------------------ ffmpeg helpers
    static boolean ffmpeg() { try { return new ProcessBuilder("ffprobe", "-version").redirectErrorStream(true).start().waitFor() == 0; } catch (Exception e) { return false; } }

    static String run(byte[] stdin, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        Thread w = new Thread(() -> { try (var o = p.getOutputStream()) { if (stdin != null) o.write(stdin); } catch (Exception ignored) {} });
        w.start();
        byte[] out = p.getInputStream().readAllBytes();
        p.getErrorStream().readAllBytes();
        p.waitFor(); w.join();
        return new String(out, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /** ffprobe's view of the stream: codec, sample rate, channels. */
    static Map<String, String> probe(Path f) throws Exception {
        Map<String, String> m = new HashMap<>();
        for (String l : run(null, "ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_name,sample_rate,channels", "-of", "default=nw=1", f.toString()).split("\n")) {
            int k = l.indexOf('='); if (k > 0) m.put(l.substring(0, k).trim(), l.substring(k + 1).trim());
        }
        return m;
    }

    /** Decoded samples at the stream's own rate (no resampling by ffmpeg). */
    static short[] decode(Path f) throws Exception {
        Process p = new ProcessBuilder("ffmpeg", "-v", "error", "-i", f.toString(), "-f", "s16le", "-ac", "1", "-").start();
        byte[] b = p.getInputStream().readAllBytes(); p.getErrorStream().readAllBytes(); p.waitFor();
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++) s[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
        return s;
    }

    static Path write(byte[] b, String name) throws Exception { Path p = Files.createTempDirectory("feedtest").resolve(name); Files.write(p, b); return p; }

    /** Every MPEG audio frame header in the stream: "MPEG-2 L3 16000 Hz 16 kb/s mono" -> count; "bad" = lost sync. */
    static Map<String, Integer> frames(byte[] b) {
        Map<String, Integer> m = new TreeMap<>();
        int[][] rates = {{11025, 12000, 8000}, {0, 0, 0}, {22050, 24000, 16000}, {44100, 48000, 32000}};
        int[] br2 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160}, br1 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
        int i = 0;
        while (i + 4 <= b.length) {
            if ((b[i] & 0xff) != 0xff || (b[i + 1] & 0xe0) != 0xe0) { m.merge("bad", 1, Integer::sum); int n = i + 1; while (n + 1 < b.length && !((b[n] & 0xff) == 0xff && (b[n + 1] & 0xe0) == 0xe0)) n++; i = n; continue; }
            int v = (b[i + 1] >> 3) & 3, layer = (b[i + 1] >> 1) & 3, bi = (b[i + 2] >> 4) & 15, ri = (b[i + 2] >> 2) & 3, pad = (b[i + 2] >> 1) & 1, mode = (b[i + 3] >> 6) & 3;
            if (v == 1 || layer != 1 || bi == 0 || bi == 15 || ri == 3) { m.merge("bad", 1, Integer::sum); i++; continue; }
            int rate = rates[v][ri], kbps = v == 3 ? br1[bi] : br2[bi];
            int len = (v == 3 ? 144 : 72) * kbps * 1000 / rate + pad;
            if (i + len > b.length) break;   // last partial frame (stream cut off)
            m.merge((v == 3 ? "MPEG-1" : v == 2 ? "MPEG-2" : "MPEG-2.5") + " L3 " + rate + " Hz " + kbps + " kb/s " + (mode == 3 ? "mono" : "stereo"), 1, Integer::sum);
            i += len;
        }
        return m;
    }

    /** Strongest frequency (Hz) in s[from, from+len) at sample rate sr: coarse Goertzel scan, then a fine one. */
    static double peakHz(short[] s, int from, int len, int sr) {
        double best = 0, bf = 0;
        for (double f = 100; f < sr / 2.0 - 50; f += 10) { double p = goertzel(s, from, len, sr, f); if (p > best) { best = p; bf = f; } }
        double c = bf; best = 0;
        for (double f = c - 10; f <= c + 10; f += 0.5) { double p = goertzel(s, from, len, sr, f); if (p > best) { best = p; bf = f; } }
        return bf;
    }
    static double goertzel(short[] s, int from, int len, int sr, double f) {
        double w = 2 * Math.PI * f / sr, k = 2 * Math.cos(w), a = 0, b = 0;
        for (int i = from; i < from + len && i < s.length; i++) { double h = 0.5 - 0.5 * Math.cos(2 * Math.PI * (i - from) / len); double x = s[i] * h + k * a - b; b = a; a = x; }
        return a * a + b * b - k * a * b;
    }

    /** Non-silent stretches [start, end) in samples (10 ms blocks above the level, gaps under 150 ms bridged). */
    static List<int[]> sounds(short[] s, int sr, int level) {
        List<int[]> out = new ArrayList<>();
        int blk = sr / 100, start = -1, lastLoud = -1;
        for (int i = 0; i + blk <= s.length; i += blk) {
            double e = 0; for (int k = i; k < i + blk; k++) e += (double) s[k] * s[k];
            boolean loud = Math.sqrt(e / blk) > level;
            if (loud) { if (start < 0) start = i; lastLoud = i + blk; }
            else if (start >= 0 && i - lastLoud > sr * 0.15) { out.add(new int[]{start, lastLoud}); start = -1; }
        }
        if (start >= 0) out.add(new int[]{start, lastLoud});
        return out;
    }

    /** Zero crossings per second over the non-silent stretches (a pitch/speed measure: twice as fast = about twice as many). */
    static double zcr(short[] s, int sr, List<int[]> parts) {
        long z = 0, n = 0;
        for (int[] p : parts) { for (int i = p[0] + 1; i < p[1]; i++) if ((s[i - 1] < 0) != (s[i] < 0)) z++; n += p[1] - p[0]; }
        return n == 0 ? 0 : z * (double) sr / n;
    }
    static double zcr(float[] s, int sr) { long z = 0, n = 0; for (int i = 1; i < s.length; i++) { if (Math.abs(s[i]) < 1e-4f && Math.abs(s[i - 1]) < 1e-4f) continue; n++; if ((s[i - 1] < 0) != (s[i] < 0)) z++; } return n == 0 ? 0 : z * (double) sr / n; }

    // ------------------------------------------------------------------ process heartbeat
    /**
     * Times the whole process stopped running (a heartbeat that sleeps 20 ms saw more than 1 s pass): the host paused the
     * machine or the PC slept. During such a stall no thread runs, so a gap in the feed is not the feed's fault.
     */
    static final List<long[]> stalls = Collections.synchronizedList(new ArrayList<>()), nanoStalls = Collections.synchronizedList(new ArrayList<>());
    static {
        Thread hb = new Thread(() -> {
            long prev = System.currentTimeMillis(), prevN = System.nanoTime();
            while (true) {
                try { Thread.sleep(20); } catch (InterruptedException e) { return; }
                long now = System.currentTimeMillis(), nowN = System.nanoTime();
                if (now - prev > 1000) { stalls.add(new long[]{prev, now}); System.out.println("     (the whole test process was paused: wall clock +" + (now - prev) + " ms, monotonic clock +" + (nowN - prevN) / 1_000_000 + " ms - host / sleep, not the feed)"); }
                if (nowN - prevN > 1_000_000_000L) nanoStalls.add(new long[]{prevN, nowN});
                prev = now; prevN = nowN;
            }
        }, "heartbeat");
        hb.setDaemon(true); hb.start();
    }
    /** Audio the feed skips after pauses: it paces on the monotonic clock and skips ahead when more than 3 s behind. */
    static double skippedSecs(long fromN, long toN) { double t = 0; synchronized (nanoStalls) { for (long[] x : nanoStalls) if (x[0] >= fromN && x[1] <= toN && x[1] - x[0] > 3_000_000_000L) t += (x[1] - x[0]) / 1e9 - 0.1; } return t; }
    static long stalledMs(long from, long to) { long t = 0; synchronized (stalls) { for (long[] s : stalls) t += Math.max(0, Math.min(to, s[1]) - Math.max(from, s[0])); } return t; }
    static long longestStall(long from, long to) { long t = 0; synchronized (stalls) { for (long[] s : stalls) t = Math.max(t, Math.min(to, s[1]) - Math.max(from, s[0])); } return t; }

    /** The feed's default MP3 rate (SDRTrunk's). */
    static final int R = Streaming.FEED_RATE;
    static String frameName(int rate) { return (rate >= 32000 ? "MPEG-1" : rate >= 16000 ? "MPEG-2" : "MPEG-2.5") + " L3 " + rate + " Hz 16 kb/s mono"; }

    // ------------------------------------------------------------------ feed set-up
    static final List<String> log = Collections.synchronizedList(new ArrayList<>());

    static Streaming feed(MockIcecast m) { return feed(m, R); }
    static Streaming feed(MockIcecast m, int rate) {
        StreamConfig c = new StreamConfig();
        c.name = "Feed"; c.type = StreamConfig.Type.BROADCASTIFY_FEED; c.host = "127.0.0.1"; c.port = m.port(); c.mount = "/testmount";
        c.password = m.password; c.includeUnlisted = true; c.bitrate = 16; c.feedRate = rate;
        Streaming st = new Streaming(List.of(c), l -> { log.add(l); System.out.println("     log: " + l); });
        st.start();
        return st;
    }

    static boolean waitFor(java.util.function.BooleanSupplier ok, long ms) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) { if (ok.getAsBoolean()) return true; Thread.sleep(10); }
        return ok.getAsBoolean();
    }

    /** Pushes one call's 8 kHz audio in real time the way P25 delivers it (an LDU = 9 voice frames every 180 ms), then ends it. */
    static void playCall(Streaming st, long id, int tg, float[] pcm) throws InterruptedException {
        long t0 = System.nanoTime();
        int ldu = 160 * 9;
        for (int o = 0, k = 0; o < pcm.length; o += ldu, k++) {
            long due = t0 + (k + 1) * 180_000_000L;
            long w = due - System.nanoTime(); if (w > 0) Thread.sleep(w / 1_000_000, (int) (w % 1_000_000));
            int n = Math.min(ldu, pcm.length - o);
            st.audio(id, null, tg, Arrays.copyOfRange(pcm, o, o + n), n);
        }
        st.callEnded(id);
    }

    static float[] tone(double hz, double secs, double amp) { float[] s = new float[(int) (8000 * secs)]; for (int i = 0; i < s.length; i++) s[i] = (float) (amp * Math.sin(2 * Math.PI * hz * i / 8000.0)); return s; }

    /** Format + declared-vs-actual checks of one connection's stream; returns the decoded samples. */
    static short[] checkStream(String what, MockIcecast.Conn c, double expectSecs, double tolSecs) throws Exception { return checkStream(what, c, expectSecs, tolSecs, R); }
    static short[] checkStream(String what, MockIcecast.Conn c, double expectSecs, double tolSecs, int rate) throws Exception {
        byte[] b = c.bytes();
        Map<String, Integer> fr = frames(b);
        String want = frameName(rate);
        int good = fr.getOrDefault(want, 0), total = 0; for (int v : fr.values()) total += v;
        check(good > 0 && good == total, what + ": every frame is " + want + " (" + fr + ")");
        Path f = write(b, "feed.mp3");
        Map<String, String> p = probe(f);
        check("mp3".equals(p.get("codec_name")) && String.valueOf(rate).equals(p.get("sample_rate")) && "1".equals(p.get("channels")),
                what + ": ffprobe says " + p.get("codec_name") + ", " + p.get("sample_rate") + " Hz, " + p.get("channels") + " channel(s)");
        short[] s = decode(f);
        double secs = s.length / (double) rate;
        check(Math.abs(secs - expectSecs) <= tolSecs, String.format(Locale.ROOT, "%s: decoded %.2f s of audio for %.2f s of real time", what, secs, expectSecs));
        double kbps = b.length * 8 / 1000.0 / secs;
        check(kbps > 15.5 && kbps < 16.5, String.format(Locale.ROOT, "%s: %.2f kb/s (16 kb/s CBR feed)", what, kbps));
        return s;
    }

    public static void main(String[] a) throws Exception {
        if (!EngineLibs.mp3Available()) { System.out.println("FeedTests: needs the MP3 encoder (-Dtrunkdeck.lib=lib) - skipped"); System.exit(0); }
        if (!ffmpeg()) { System.out.println("FeedTests: needs ffmpeg/ffprobe on the PATH - skipped"); System.exit(0); }
        int idle = Integer.getInteger("feedIdleSecs", 90);

        // ---------------------------------------------------------------- 1. tone: declared rate = content, duration = real time
        System.out.println("1. 1 kHz test tone through the feed (3 calls of 3 s, 2 s apart) - mock Icecast");
        MockIcecast m = new MockIcecast("hackme");
        Streaming st = feed(m);
        Streaming.Feed fd = st.feeds().get(0);
        check(waitFor(() -> m.accepted().size() == 1 && fd.connectedSince > 0, 5000), "connected once the engine started, before any call");
        MockIcecast.Conn c = m.accepted().get(0);
        check(c.requestLine.equals("SOURCE /testmount HTTP/1.0") && "audio/mpeg".equals(c.headers.get("content-type")), "SOURCE /testmount HTTP/1.0, Content-Type audio/mpeg (as SDRTrunk)");
        check(String.valueOf(c.headers.get("ice-audio-info")).equals("samplerate=8000;quality=7;channels=1"), "ice-audio-info: " + c.headers.get("ice-audio-info") + " (SDRTrunk's exact value)");
        Thread.sleep(1000);
        for (int k = 0; k < 3; k++) { playCall(st, 100 + k, 2000 + k, tone(1000, 3.0, 0.5)); Thread.sleep(2000); }
        Thread.sleep(1500);
        long blocks = fd.blocks;
        st.stop();
        waitFor(() -> c.closedMs > 0, 3000);
        long wall = c.closedMs - c.chunks.get(0)[0];
        short[] s = checkStream("tone feed", c, blocks / 10.0, 0.3);   // LAME still holds up to ~0.2 s when the stream is cut
        check(Math.abs(s.length / (double) R - wall / 1000.0) < 0.6, String.format(Locale.ROOT, "tone feed: %.2f s of audio in %.2f s of wall-clock streaming (real time, no catch-up)", s.length / (double) R, wall / 1000.0));
        List<int[]> parts = sounds(s, R, 2000);
        check(parts.size() == 3, "tone feed: 3 calls heard (" + parts.size() + ")");
        for (int[] p : parts) {
            double len = (p[1] - p[0]) / (double) R, hz = peakHz(s, p[0] + R / 2, R, R);
            check(Math.abs(len - 3.0) <= 0.15, String.format(Locale.ROOT, "tone feed: call lasts %.2f s (3.00 s pushed)", len));
            check(Math.abs(hz - 1000) <= 3, String.format(Locale.ROOT, "tone feed: tone comes out at %.1f Hz (1000 Hz in)", hz));
        }
        for (int k = 1; k < parts.size(); k++) { double gap = (parts.get(k)[0] - parts.get(k - 1)[1]) / (double) R; check(Math.abs(gap - 2.0) <= 0.25, String.format(Locale.ROOT, "tone feed: %.2f s of silence between calls (2 s)", gap)); }
        m.stop();

        // ---------------------------------------------------------------- 2. the user's real recordings
        List<Path> recs = LiveTests.recordings();
        if (LiveTests.jmbe() && !recs.isEmpty()) {
            System.out.println("2. the user's real recordings (decoded voice) through the feed");
            List<float[]> calls = new ArrayList<>();
            for (Path f : recs) {
                EngineLibs.Imbe imbe = EngineLibs.newImbe();
                List<float[]> blocksOut = new ArrayList<>();
                ChannelDecoder d = new ChannelDecoder(48000, 0, 0, new P25Framer.Listener() {
                    public void ldu(int nac, int duid, byte[][] fr, P25Framer.LinkControl lc, P25Framer.EncryptionSync e) { for (byte[] x : fr) { float[] y = imbe.decode(x); if (y != null) blocksOut.add(y); } }
                    public void terminator(int nac, int duid) {}
                }, 0);
                new IqFileSource(f, 0, 0).readAll((i, q, n) -> { for (int o = 0; o < n; o += 480) { int k = Math.min(480, n - o); d.process(Arrays.copyOfRange(i, o, o + k), Arrays.copyOfRange(q, o, o + k), k); } });
                int n = 0; for (float[] y : blocksOut) n += y.length;
                float[] pcm = new float[n]; int o = 0; for (float[] y : blocksOut) { System.arraycopy(y, 0, pcm, o, y.length); o += y.length; }
                // normalise so the level check works on quiet captures
                float pk = 1e-6f; for (float v : pcm) pk = Math.max(pk, Math.abs(v)); for (int i = 0; i < pcm.length; i++) pcm[i] *= 0.7f / pk;
                if (pcm.length > 8000) calls.add(pcm);
                System.out.printf(Locale.ROOT, "     %s: %.2f s of decoded voice%n", f.getFileName(), pcm.length / 8000.0);
            }
            MockIcecast m2 = new MockIcecast("hackme");
            Streaming st2 = feed(m2);
            waitFor(() -> m2.accepted().size() == 1 && st2.feeds().get(0).connectedSince > 0, 5000);
            Thread.sleep(800);
            double in = 0, zIn = 0; long id = 500;
            for (float[] pcm : calls) { playCall(st2, id++, 3000, pcm); in += pcm.length / 8000.0; zIn += zcr(pcm, 8000) * pcm.length / 8000.0; Thread.sleep(1500); }
            zIn /= in;
            long b2 = st2.feeds().get(0).blocks;
            st2.stop();
            MockIcecast.Conn c2 = m2.accepted().get(0);
            waitFor(() -> c2.closedMs > 0, 3000);
            short[] s2 = checkStream("real-recording feed", c2, b2 / 10.0, 0.3);
            // what came out between the calls' first and last loud moments vs what went in
            List<int[]> p2 = sounds(s2, R, 150);
            double heard = 0; for (int[] p : p2) heard += (p[1] - p[0]) / (double) R;
            check(heard <= in + 0.3 && heard >= in * 0.8, String.format(Locale.ROOT, "real-recording feed: %.2f s of voice heard for %.2f s pushed (a 2x speed-up would give about %.2f s)", heard, in, in / 2));
            double zOut = zcr(s2, R, p2);
            check(Math.abs(zOut / zIn - 1) < 0.15, String.format(Locale.ROOT, "real-recording feed: pitch kept - %.0f zero crossings/s out vs %.0f in (ratio %.2f; chipmunk 2x = 2.00)", zOut, zIn, zOut / zIn));
            m2.stop();
        } else System.out.println("2. real recordings: skipped (needs -Djmbe=... and the private recordings; " + LiveTests.SKIP + ")");

        // ---------------------------------------------------------------- 3. long idle: one connection, continuous silence
        System.out.println("3. long idle period (" + idle + " s, no calls): connection held, silence sent in real time");
        MockIcecast m3 = new MockIcecast("hackme");
        Streaming st3 = feed(m3);
        Streaming.Feed f3 = st3.feeds().get(0);
        waitFor(() -> m3.accepted().size() == 1 && f3.connectedSince > 0, 5000);
        long idleN0 = System.nanoTime();
        Thread.sleep(idle * 1000L);
        MockIcecast.Conn c3 = m3.accepted().get(0);
        check(m3.conns.size() == 1 && c3.closedMs == 0, "one connection the whole time, still open (" + m3.conns.size() + " connection(s))");
        long idleFrom = c3.chunks.get(0)[0], idleTo = System.currentTimeMillis(), paused = stalledMs(idleFrom, idleTo);
        long gap = c3.maxGapMs();
        check(gap < 600 || gap <= longestStall(idleFrom, idleTo) + 600, "longest gap between audio arrivals " + gap + " ms (< 600 ms"
                + (paused > 0 ? "; the whole process was paused " + paused + " ms by the host, during which nothing runs" : "") + ")");
        check(f3.drops == 0, "no drops / reconnects (" + f3.drops + ")");
        String desc = f3.describe();
        System.out.println("     Streams page status: " + desc);
        check(desc.matches("connected (\\d+ s|\\d+m \\d+s|\\d+h \\d+m) - sending silence between calls .*") && System.currentTimeMillis() - f3.connectedSince >= idle * 1000L, "status shows the connection and its uptime");
        long idleN1 = System.nanoTime();
        // a copy of what arrived so far, decoded (the last frame may be cut off)
        MockIcecast.Conn snap = c3;
        // after a host pause the feed does not burst the missed time (it skips ahead), so that time is not in the stream
        double skipped = skippedSecs(idleN0, idleN1);
        if (skipped > 0) System.out.printf(Locale.ROOT, "     (the feed skipped %.1f s of silence after host pauses, as designed)%n", skipped);
        short[] s3 = checkStream("idle feed", snap, (idleN1 - idleN0) / 1e9 - skipped, 0.8);
        int loud = 0; for (short v : s3) if (Math.abs(v) > 50) loud++;
        check(loud == 0, "idle feed: pure silence (" + loud + " samples above the noise floor)");

        // ---------------------------------------------------------------- 4. forced drops: automatic reconnect with backoff
        System.out.println("4. forced drops: reconnect by itself, 1 / 2 / 5 / 10 s backoff");
        long t0 = System.currentTimeMillis();
        m3.drop();
        check(waitFor(() -> m3.accepted().size() == 2 && f3.connectedSince > 0, 4000), "reconnected after the drop");
        long re = m3.accepted().get(1).openedMs - t0;
        check(re >= 900 && re <= 1600, "first reconnect " + re + " ms after the drop (1 s backoff)");
        check(f3.drops == 1, "drop counted (" + f3.drops + ")");
        check(log.stream().anyMatch(l -> l.contains("connection lost after")) && log.stream().anyMatch(l -> l.contains("reconnected to")), "disconnect and reconnect logged");
        Thread.sleep(3000);
        check(m3.accepted().get(1).bytes().length > 5000 && m3.accepted().get(1).maxGapMs() < 600, "streaming again on the new connection, no gaps (" + m3.accepted().get(1).maxGapMs() + " ms)");
        // a server that keeps refusing: the waits grow 2 s, 5 s, 10 s (the connection before did not hold 30 s)
        int before = m3.conns.size();
        m3.refuseNext(2);
        long t1 = System.currentTimeMillis();
        m3.drop();
        check(waitFor(() -> m3.accepted().size() == 3, 25_000), "reconnected after two refusals");
        List<MockIcecast.Conn> all; synchronized (m3.conns) { all = new ArrayList<>(m3.conns.subList(before, m3.conns.size())); }
        long[] at = new long[all.size()]; for (int i = 0; i < at.length; i++) at[i] = all.get(i).openedMs;
        String waits = at.length >= 3 ? (at[0] - t1) + " / " + (at[1] - at[0]) + " / " + (at[2] - at[1]) + " ms" : Arrays.toString(at);
        check(at.length == 3 && Math.abs(at[0] - t1 - 2000) < 500 && Math.abs(at[1] - at[0] - 5000) < 500 && Math.abs(at[2] - at[1] - 10000) < 500,
                "retry waits " + waits + " (2 s, 5 s, 10 s after a short-lived connection)");
        Thread.sleep(1500);
        System.out.println("     Streams page status: " + f3.describe());
        check(f3.describe().contains("2 reconnects - last drop: server closed the connection"), "status shows reconnects and the last drop");
        Map<String, Integer> fr3 = frames(m3.accepted().get(2).bytes());
        check(fr3.size() == 1 && fr3.containsKey(frameName(R)), "new connection starts with clean frames (" + fr3 + ")");
        st3.stop(); m3.stop();

        // ---------------------------------------------------------------- 5. Broadcastify Calls and OpenMHz MP3s
        System.out.println("5. Broadcastify Calls and OpenMHz MP3s: pitch and length");
        Transmission tx = new Transmission(1, 2001, 851_000_000L, System.currentTimeMillis(), 0, true);
        float[] t3 = tone(1000, 3.0, 0.5);
        for (int o = 0; o < t3.length; o += 160) tx.add(Arrays.copyOfRange(t3, o, o + 160), 160, true);
        tx.end("test");
        Path cf = write(tx.mp3(), "calls.mp3");
        Map<String, String> cp = probe(cf); short[] cs = decode(cf); int csr = Integer.parseInt(cp.get("sample_rate"));
        List<int[]> cpart = sounds(cs, csr, 2000);
        double clen = cpart.isEmpty() ? 0 : (cpart.get(0)[1] - cpart.get(0)[0]) / (double) csr;
        check("22050".equals(cp.get("sample_rate")) && "1".equals(cp.get("channels")), "Broadcastify Calls MP3: " + cp.get("sample_rate") + " Hz, " + cp.get("channels") + " channel");
        check(Math.abs(clen - 3.0) <= 0.1, String.format(Locale.ROOT, "Broadcastify Calls MP3: 3.00 s of tone lasts %.2f s", clen));
        check(Math.abs(peakHz(cs, cpart.get(0)[0] + csr / 2, csr, csr) - 1000) <= 3, String.format(Locale.ROOT, "Broadcastify Calls MP3: tone at %.1f Hz", peakHz(cs, cpart.get(0)[0] + csr / 2, csr, csr)));
        Path of = write(OpenMhzUploader.encode(t3, t3.length), "openmhz.mp3");
        Map<String, String> op = probe(of); short[] os = decode(of); int osr = Integer.parseInt(op.get("sample_rate"));
        List<int[]> opart = sounds(os, osr, 2000);
        double olen = opart.isEmpty() ? 0 : (opart.get(0)[1] - opart.get(0)[0]) / (double) osr;
        check(Math.abs(olen - 3.0) <= 0.1, String.format(Locale.ROOT, "OpenMHz MP3 (%s Hz): 3.00 s of tone lasts %.2f s", op.get("sample_rate"), olen));
        check(Math.abs(peakHz(os, opart.get(0)[0] + osr / 2, osr, osr) - 1000) <= 3, String.format(Locale.ROOT, "OpenMHz MP3: tone at %.1f Hz", peakHz(os, opart.get(0)[0] + osr / 2, osr, osr)));

        // ---------------------------------------------------------------- 6. calibration signal ("Send test tone")
        System.out.println("6. calibration test tone through the feed");
        MockIcecast m6 = new MockIcecast("hackme");
        Streaming st6 = feed(m6);
        waitFor(() -> m6.accepted().size() == 1 && st6.feeds().get(0).connectedSince > 0, 5000);
        Thread.sleep(500);
        check(st6.testTone("Feed") == 1 && st6.testTone("no such feed") == 0, "test tone queued on the named feed only");
        Thread.sleep(10_500);
        long b6 = st6.feeds().get(0).blocks;
        st6.stop();
        MockIcecast.Conn c6 = m6.accepted().get(0);
        waitFor(() -> c6.closedMs > 0, 3000);
        short[] s6 = checkStream("test-tone feed", c6, b6 / 10.0, 0.3);
        List<int[]> p6 = sounds(s6, R, 2000);
        check(p6.size() == 6, "test tone: 1 tone + 5 beeps heard (" + p6.size() + " sounds)");
        if (p6.size() >= 6) {
            double toneLen = (p6.get(0)[1] - p6.get(0)[0]) / (double) R;
            check(Math.abs(toneLen - 2.0) <= 0.08, String.format(Locale.ROOT, "test tone: steady tone lasts %.2f s (2.00 s)", toneLen));
            check(Math.abs(peakHz(s6, p6.get(0)[0] + R / 2, R, R) - 1000) <= 3, String.format(Locale.ROOT, "test tone: %.1f Hz (1000 Hz)", peakHz(s6, p6.get(0)[0] + R / 2, R, R)));
            double span = (p6.get(5)[0] - p6.get(1)[0]) / (double) R;
            check(Math.abs(span - 4.0) <= 0.03, String.format(Locale.ROOT, "test tone: first to last beep %.3f s (4.000 s)", span));
            for (int k = 1; k <= 5; k++) { double bl = (p6.get(k)[1] - p6.get(k)[0]) / (double) R; check(Math.abs(bl - 0.2) <= 0.04, String.format(Locale.ROOT, "test tone: beep %d lasts %.2f s (0.20 s)", k, bl)); }
        }
        m6.stop();

        // ---------------------------------------------------------------- 7. every MP3 rate option keeps pitch and length
        System.out.println("7. every 'MP3 sample rate' option: pitch and length");
        for (int rate : p25monitor.StreamConfig.FEED_RATES) {
            MockIcecast m7 = new MockIcecast("hackme");
            Streaming st7 = feed(m7, rate);
            waitFor(() -> m7.accepted().size() == 1 && st7.feeds().get(0).connectedSince > 0, 5000);
            Thread.sleep(300);
            playCall(st7, 900 + rate, 2100, tone(1000, 2.0, 0.5));
            Thread.sleep(1200);
            long b7 = st7.feeds().get(0).blocks;
            st7.stop();
            MockIcecast.Conn c7 = m7.accepted().get(0);
            waitFor(() -> c7.closedMs > 0, 3000);
            short[] s7 = checkStream(rate + " Hz feed", c7, b7 / 10.0, 0.3, rate);
            List<int[]> p7 = sounds(s7, rate, 2000);
            double len = p7.isEmpty() ? 0 : (p7.get(0)[1] - p7.get(0)[0]) / (double) rate, hz = p7.isEmpty() ? 0 : peakHz(s7, p7.get(0)[0] + rate / 2, rate / 2, rate);
            check(p7.size() == 1 && Math.abs(len - 2.0) <= 0.1 && Math.abs(hz - 1000) <= 4, String.format(Locale.ROOT, "%d Hz feed: 2.00 s tone lasts %.2f s at %.1f Hz", rate, len, hz));
            m7.stop();
        }

        // ---------------------------------------------------------------- 8. FeedCheck (the listener-side speed meter)
        System.out.println("8. FeedCheck speed measurement on the calibration signal (right speed, 1.3.2 symptom, 1.3.3 symptom)");
        float[] sig = Streaming.testSignal();
        for (int[] v : new int[][]{{16000, 16000}, {8000, 16000}, {22050, 16000}}) {
            // content made at v[0] samples/s, played by the listener at v[1]
            float[] c8 = v[0] == 8000 ? sig : new Resampler(v[0]).process(sig, sig.length);
            short[] pcm = new short[c8.length]; for (int i = 0; i < c8.length; i++) pcm[i] = (short) Math.round(c8[i] * 30000);
            String r = p25monitor.FeedCheck.analyse(pcm, v[1]);
            double want = v[1] / (double) v[0];
            java.util.regex.Matcher mt = java.util.regex.Pattern.compile("Speed: ([0-9.]+)x").matcher(r);
            double got = mt.find() ? Double.parseDouble(mt.group(1)) : -1;
            check(Math.abs(got - want) < 0.02, String.format(Locale.ROOT, "FeedCheck: %d Hz audio played at %d Hz measures %.2fx (expected %.2fx)", v[0], v[1], got, want));
        }

        System.out.println("FeedTests: " + pass + " passed, " + fail + " failed (mock Icecast server - not tested against Broadcastify's servers)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
