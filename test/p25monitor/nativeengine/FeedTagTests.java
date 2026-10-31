package p25monitor.nativeengine;

import p25monitor.StreamConfig;
import p25monitor.Talkgroup;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 1.5.1 now-playing tags on a Broadcastify Feed / Icecast feed, against a mock Icecast server: the exact request
 * (Icecast admin metadata as SDRTrunk sends it), auth, encoding, order (matches the audio), rate limit / last wins,
 * failure isolation (a slow, hanging or failing metadata service never touches the audio connection), live settings,
 * idle text and Send test tag. Needs the MP3 encoder (-Dtrunkdeck.lib=lib).
 */
public final class FeedTagTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }
    static final List<String> log = Collections.synchronizedList(new ArrayList<>());

    static Talkgroup tg(int id, String alpha, String cat, String tag) {
        Talkgroup t = new Talkgroup(); t.decimal = id; t.alphaTag = alpha; t.category = cat; t.tag = tag; t.streams.add("Feed"); return t;
    }

    static StreamConfig cfg(MockIcecast m) {
        StreamConfig c = new StreamConfig();
        c.name = "Feed"; c.type = StreamConfig.Type.BROADCASTIFY_FEED; c.host = "127.0.0.1"; c.port = m.port(); c.mount = "testmount";
        c.password = m.password; c.feedId = "1"; c.bitrate = 16;
        return c;
    }

    static Streaming feed(MockIcecast m, StreamConfig c, CallMixer.Mode mode) {
        Streaming st = new Streaming(List.of(c), l -> { log.add(l); System.out.println("     log: " + l); }, mode, 30_000);
        st.feedRadioNames(id -> id == 123456 ? "EXAMPLE UNIT 7" : id == 222333 ? "EXAMPLE UNIT 9" : "");
        st.start();
        return st;
    }

    static boolean waitFor(java.util.function.BooleanSupplier ok, long ms) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) { if (ok.getAsBoolean()) return true; Thread.sleep(10); }
        return ok.getAsBoolean();
    }

    static float[] tone(double secs) { float[] s = new float[(int) (8000 * secs)]; for (int i = 0; i < s.length; i++) s[i] = (float) (0.4 * Math.sin(2 * Math.PI * 1000 * i / 8000.0)); return s; }

    /** One call in real time as P25 delivers it (an LDU of 9 frames every 180 ms); src from the given LDU on (-1 before). */
    static void playCall(Streaming st, long id, Talkgroup t, int src, int srcFromLdu, double secs) throws InterruptedException {
        float[] pcm = tone(secs);
        long t0 = System.nanoTime(); int ldu = 160 * 9;
        for (int o = 0, k = 0; o < pcm.length; o += ldu, k++) {
            long w = t0 + (k + 1) * 180_000_000L - System.nanoTime(); if (w > 0) Thread.sleep(w / 1_000_000, (int) (w % 1_000_000));
            int n = Math.min(ldu, pcm.length - o);
            st.audio(id, t, t.decimal, k >= srcFromLdu ? src : -1, Arrays.copyOfRange(pcm, o, o + n), n);
        }
        st.callEnded(id);
    }
    static Thread playAsync(Streaming st, long id, Talkgroup t, int src, double secs) {
        Thread th = new Thread(() -> { try { playCall(st, id, t, src, 0, secs); } catch (InterruptedException ignored) {} });
        th.start(); return th;
    }

    /** Titles of the requests from index i on (any status). */
    static List<String> songsFrom(MockIcecast m, int i) { synchronized (m.meta) { List<String> r = new ArrayList<>(); for (int k = i; k < m.meta.size(); k++) r.add(m.meta.get(k).song() + (m.meta.get(k).status == 200 ? "" : " [" + m.meta.get(k).status + "]")); return r; } }

    /** Until nothing is queued or playing on the feed, then one rate-limit gap (so a section starts clean). */
    static void settle(Streaming.Feed fd) throws InterruptedException { waitFor(() -> fd.mixer.idle(), 20000); Thread.sleep(FeedTags.MIN_GAP_MS + 200); }

    /** When each talkgroup first went on the air on the feed (from the feed's own block tap). */
    static final Map<Integer, Long> onAir = new ConcurrentHashMap<>();
    static final List<Integer> airOrder = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] a) throws Exception {
        renderTests();
        if (!EngineLibs.mp3Available()) { System.out.println("FeedTagTests: the server part needs the MP3 encoder (-Dtrunkdeck.lib=lib) - skipped"); finish(); return; }
        settingsTests();
        liveTests();
        finish();
    }

    static void finish() {
        System.out.println("FeedTagTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ 1. text and request format (no server)
    static void renderTests() {
        System.out.println("1. Tag text and request format");
        StreamConfig c = new StreamConfig();
        Talkgroup t = tg(2001, "EX CO DISPATCH", "Example County", "Law Dispatch");
        java.util.function.IntFunction<String> rn = id -> id == 123456 ? "EXAMPLE UNIT 7" : "";
        check(c.sendTags && c.tagFormat == StreamConfig.TagFormat.TG_NAME && c.tagIdle.isEmpty(), "defaults: tags on, Talkgroup name, no idle text");
        check(FeedTags.render(c, 2001, t, 123456, rn).equals("EX CO DISPATCH"), "Talkgroup name: " + FeedTags.render(c, 2001, t, 123456, rn));
        check(FeedTags.render(c, 2099, null, -1, rn).equals("TG 2099"), "Talkgroup name, talkgroup not in the list: TG 2099");
        Talkgroup d = tg(2002, "", "", ""); d.description = "Example North Fire";
        check(FeedTags.render(c, 2002, d, -1, rn).equals("Example North Fire"), "no alpha tag: the description");
        c.tagFormat = StreamConfig.TagFormat.TG_NAME_RADIO;
        check(FeedTags.render(c, 2001, t, 123456, rn).equals("EX CO DISPATCH - EXAMPLE UNIT 7"), "Talkgroup + radio name: " + FeedTags.render(c, 2001, t, 123456, rn));
        check(FeedTags.render(c, 2001, t, 555, rn).equals("EX CO DISPATCH - 555"), "radio without a name: its ID");
        check(FeedTags.render(c, 2001, t, -1, rn).equals("EX CO DISPATCH"), "radio not known: talkgroup only");
        c.tagFormat = StreamConfig.TagFormat.TG_ID_NAME;
        check(FeedTags.render(c, 2001, t, 123456, rn).equals("2001 EX CO DISPATCH"), "Talkgroup ID + name: " + FeedTags.render(c, 2001, t, 123456, rn));
        c.tagFormat = StreamConfig.TagFormat.CUSTOM;
        c.tagTemplate = "{group}: {tgname} [{tag}] {tg} / {radio} {radioname}";
        check(FeedTags.render(c, 2001, t, 123456, rn).equals("Example County: EX CO DISPATCH [Law Dispatch] 2001 / 123456 EXAMPLE UNIT 7"), "custom, every token: " + FeedTags.render(c, 2001, t, 123456, rn));
        c.tagTemplate = "{tgname} - {radioname} ({tag})";
        check(FeedTags.render(c, 2002, tg(2002, "EX NORTH FIRE", "", ""), -1, rn).equals("EX NORTH FIRE"), "custom, empty tokens: no dangling ' - ' or '()': " + FeedTags.render(c, 2002, tg(2002, "EX NORTH FIRE", "", ""), -1, rn));
        c.tagTemplate = "{radioname}";
        check(FeedTags.render(c, 2003, null, -1, rn).equals("TG 2003"), "custom that comes out empty: TG <id>");
        c.tagTemplate = DEFAULT_T();
        check(FeedTags.render(c, 2001, t, 123456, rn).equals("2001 EX CO DISPATCH - EXAMPLE UNIT 7"), "default custom template " + DEFAULT_T());
        c.tagFormat = StreamConfig.TagFormat.TG_NAME;
        Talkgroup bad = tg(2004, "EX\tCO\nNIGHT\u0007  OPS", "", "");
        check(FeedTags.render(c, 2004, bad, -1, rn).equals("EX CO NIGHT OPS"), "control characters / newlines removed: " + FeedTags.render(c, 2004, bad, -1, rn));
        Talkgroup lng = tg(2005, "X".repeat(400), "", "");
        check(FeedTags.render(c, 2005, lng, -1, rn).length() == FeedTags.MAX_LEN, "long names cut to " + FeedTags.MAX_LEN + " characters");
        StreamConfig p = new StreamConfig(); p.mount = "testmount";
        String path = FeedTags.path(p, "EX CO \u2013 \u00c4&B+1 \u00e9");
        check(path.equals("/admin/metadata?mode=updinfo&mount=%2Ftestmount&charset=UTF-8&song=EX%20CO%20%E2%80%93%20%C3%84%26B%2B1%20%C3%A9"),
                "request: mode=updinfo, mount (with /), charset=UTF-8, song URL-encoded as UTF-8 (%20, & and + escaped): " + path);
        p.mount = "/already";
        check(FeedTags.path(p, "x").contains("mount=%2Falready&"), "mount that already starts with / is not doubled");
        check(FeedTags.needsRadio(cfgFmt(StreamConfig.TagFormat.TG_NAME_RADIO, "")) && !FeedTags.needsRadio(cfgFmt(StreamConfig.TagFormat.TG_ID_NAME, ""))
                && FeedTags.needsRadio(cfgFmt(StreamConfig.TagFormat.CUSTOM, "{tg} {radio}")) && !FeedTags.needsRadio(cfgFmt(StreamConfig.TagFormat.CUSTOM, "{tg}")), "which formats wait for the radio");
    }
    static String DEFAULT_T() { return StreamConfig.DEFAULT_TAG_TEMPLATE; }
    static StreamConfig cfgFmt(StreamConfig.TagFormat f, String tpl) { StreamConfig c = new StreamConfig(); c.tagFormat = f; c.tagTemplate = tpl; return c; }

    // ------------------------------------------------------------------ 2. saved settings
    static void settingsTests() throws Exception {
        System.out.println("2. Saved settings");
        Path dir = Files.createTempDirectory("tagcfg");
        Path old = dir.resolve("streams.properties");
        Files.writeString(old, "count=1\ns0.name=Feed\ns0.type=BROADCASTIFY_FEED\ns0.host=h\ns0.port=80\ns0.mount=m\ns0.password=p\ns0.feedId=1\n", StandardCharsets.UTF_8);
        StreamConfig o = StreamConfig.load(old).get(0);
        check(o.sendTags && o.tagFormat == StreamConfig.TagFormat.TG_NAME && o.tagIdle.isEmpty() && o.tagTemplate.equals(StreamConfig.DEFAULT_TAG_TEMPLATE), "a feed saved by 1.5.0: tags on, Talkgroup name, no idle text");
        o.sendTags = false; o.tagFormat = StreamConfig.TagFormat.CUSTOM; o.tagTemplate = "{tg}|{tgname}"; o.tagIdle = "Scanning...";
        StreamConfig.save(old, List.of(o));
        StreamConfig r = StreamConfig.load(old).get(0);
        check(!r.sendTags && r.tagFormat == StreamConfig.TagFormat.CUSTOM && r.tagTemplate.equals("{tg}|{tgname}") && r.tagIdle.equals("Scanning..."), "saved and loaded again");
        StreamConfig cp = r.copy();
        check(!cp.sendTags && cp.tagFormat == r.tagFormat && cp.tagTemplate.equals(r.tagTemplate) && cp.tagIdle.equals(r.tagIdle), "copied (the running feed works on a copy)");
        StreamConfig k = r.copy(); k.sendTags = true; k.tagFormat = StreamConfig.TagFormat.TG_ID_NAME; k.tagIdle = ""; k.tagTemplate = "x";
        check(k.feedKey().equals(r.feedKey()), "tag settings are not part of the connection key (they change without a reconnect)");
        Files.writeString(dir.resolve("c.properties"), "count=1\ns0.name=C\ns0.type=BROADCASTIFY_CALLS\ns0.apiKey=k\ns0.systemId=1\n");
        StreamConfig.save(dir.resolve("c2.properties"), StreamConfig.load(dir.resolve("c.properties")));
        check(!Files.readString(dir.resolve("c2.properties")).contains("sendTags"), "per-call streams (Calls / OpenMHz / Rdio) get no tag keys");
    }

    // ------------------------------------------------------------------ 3. against the mock server
    static void liveTests() throws Exception {
        System.out.println("3. Tags on a running feed (mock Icecast)");
        MockIcecast m = new MockIcecast("hackme");
        StreamConfig c = cfg(m);
        Streaming st = feed(m, c, CallMixer.Mode.QUEUE);
        Streaming.Feed fd = st.feeds().get(0);
        st.feedTap = (name, tgs) -> { for (int g : tgs) if (onAir.putIfAbsent(g, System.currentTimeMillis()) == null) airOrder.add(g); };
        check(waitFor(() -> m.accepted().size() == 1 && fd.connectedSince > 0, 5000), "feed connected");
        MockIcecast.Conn src = m.accepted().get(0);
        Thread.sleep(FeedTags.IDLE_AFTER_MS + 1000);
        check(m.meta.isEmpty(), "no call, no idle text set: nothing sent");

        Talkgroup a = tg(2001, "EX CO DISPATCH", "Example County", "Law Dispatch");
        playCall(st, 1, a, 123456, 0, 2.0);
        check(waitFor(() -> m.meta.size() >= 1, 3000), "a call: one metadata request");
        MockIcecast.Meta q = m.meta.get(0);
        check(q.status == 200 && "EX CO DISPATCH".equals(q.song()), "song = talkgroup name (" + q.song() + "), Icecast answered " + q.status);
        check(q.requestLine.startsWith("GET /admin/metadata?mode=updinfo&mount=%2Ftestmount&charset=UTF-8&song=EX%20CO%20DISPATCH HTTP/1."), "request line: " + q.requestLine);
        check(("Basic " + Base64.getEncoder().encodeToString("source:hackme".getBytes(StandardCharsets.UTF_8))).equals(q.headers.get("authorization")), "Basic auth with the feed's source user and password");
        check("TrunkDeck".equals(q.headers.get("user-agent")) && ("127.0.0.1:" + m.port()).equals(q.headers.get("host")), "User-Agent TrunkDeck (as the audio connection), Host header");
        long lag = q.atMs - onAir.getOrDefault(2001, 0L);
        check(Math.abs(lag) < 600, "sent as the call started playing on the feed (" + lag + " ms after its first audio block)");
        check(m.accepted().size() == 1 && m.conns.size() == 1, "a separate connection: the audio connection is the only SOURCE one (" + m.conns.size() + ")");

        System.out.println("   order: three overlapping calls queue on the feed; the tags follow the audio");
        Talkgroup b = tg(2002, "EX NORTH FIRE", "", ""), cc = tg(2003, "EX EAST EMS", "", ""), d = tg(2004, "EX WEST PW", "", "");
        int before = m.meta.size();
        Thread t1 = playAsync(st, 2, b, -1, 2.5); Thread.sleep(300);
        Thread t2 = playAsync(st, 3, cc, -1, 2.0); Thread.sleep(300);
        Thread t3 = playAsync(st, 4, d, -1, 2.0);
        t1.join(); t2.join(); t3.join();
        check(waitFor(() -> m.meta.size() >= before + 3, 10000), "three calls, three tags");
        List<String> songs = songsFrom(m, before); songs = songs.subList(0, Math.min(3, songs.size()));
        check(songs.equals(List.of("EX NORTH FIRE", "EX EAST EMS", "EX WEST PW")), "in queue order: " + songs + " (on air: " + airOrder + ")");
        for (int i = 0; i < 3; i++) {
            int g = 2002 + i; MockIcecast.Meta x = m.meta.get(before + i);
            long l = x.atMs - onAir.getOrDefault(g, 0L);
            check(l > -100 && l < 1700, "TG " + g + ": tag " + l + " ms after its audio went on the air (queued calls play 2+ s apart)");
        }

        System.out.println("   rate limit: eight 0.3 s calls back to back");
        settle(fd);
        int b2 = m.meta.size();
        long t0 = System.currentTimeMillis();
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < 8; i++) { ts.add(playAsync(st, 10 + i, tg(2010 + i, "EX SHORT " + i, "", ""), -1, 0.3)); Thread.sleep(40); }
        for (Thread x : ts) x.join();
        Thread.sleep(FeedTags.MIN_GAP_MS * 2 + 500);
        List<MockIcecast.Meta> burst = new ArrayList<>(m.meta.subList(b2, m.meta.size()));
        long minGap = Long.MAX_VALUE; for (int i = 1; i < burst.size(); i++) minGap = Math.min(minGap, burst.get(i).atMs - burst.get(i - 1).atMs);
        check(burst.size() >= 2 && burst.size() <= 4, burst.size() + " requests for 8 calls in " + (System.currentTimeMillis() - t0) / 1000.0 + " s (coalesced: " + fd.tags.coalesced + ")");
        check(burst.size() < 2 || minGap >= FeedTags.MIN_GAP_MS - 60, "at least " + FeedTags.MIN_GAP_MS + " ms between requests (min " + minGap + " ms)");
        check(burst.get(burst.size() - 1).song().equals("EX SHORT 7"), "last one wins: the final title is the last call played (" + burst.get(burst.size() - 1).song() + ")");
        check(m.metaMaxBusy == 1, "one request at a time");

        System.out.println("   the same talkgroup twice in a row: one request");
        settle(fd);
        int b3 = m.meta.size();
        Talkgroup e = tg(2020, "EX TAC 1", "", "");
        playCall(st, 30, e, -1, 0, 1.0); Thread.sleep(500); playCall(st, 31, e, -1, 0, 1.0);
        Thread.sleep(2500);
        check(m.meta.size() == b3 + 1, "same title not sent again (" + (m.meta.size() - b3) + " request(s))");

        System.out.println("   Talkgroup + radio name, the radio known only after the call started");
        StreamConfig c2 = c.copy(); c2.tagFormat = StreamConfig.TagFormat.TG_NAME_RADIO;
        List<String> lines = st.reconfigure(List.of(c2), CallMixer.Mode.QUEUE, 30_000);
        check(m.accepted().size() == 1 && fd == st.feeds().get(0) && lines.isEmpty(), "format changed live: same feed, same connection " + lines);
        Thread.sleep(1600);
        int b4 = m.meta.size();
        playCall(st, 40, tg(2030, "EX CO OPS", "", ""), 222333, 8, 3.5);
        check(waitFor(() -> m.meta.size() >= b4 + 2, 4000), "two tags: first without the radio, then with it");
        List<String> s4 = songsFrom(m, b4);
        check(s4.size() >= 2 && s4.get(0).equals("EX CO OPS") && s4.get(1).equals("EX CO OPS - EXAMPLE UNIT 9"), "" + s4);

        System.out.println("   idle text");
        StreamConfig c3 = c2.copy(); c3.tagIdle = "Scanning..."; c3.tagFormat = StreamConfig.TagFormat.TG_ID_NAME;
        st.reconfigure(List.of(c3), CallMixer.Mode.QUEUE, 30_000);
        Thread.sleep(1600);
        int b5 = m.meta.size();
        playCall(st, 50, tg(2040, "EX CO ADMIN", "", ""), -1, 0, 1.0);
        long end = System.currentTimeMillis();
        check(waitFor(() -> m.meta.size() >= b5 + 2, 6000), "a call, then the idle text");
        List<MockIcecast.Meta> i5 = new ArrayList<>(m.meta.subList(b5, m.meta.size()));
        check(i5.size() >= 2 && i5.get(0).song().equals("2040 EX CO ADMIN") && i5.get(1).song().equals("Scanning..."), "titles: " + songsFrom(m, b5));
        long idleAfter = i5.size() >= 2 ? i5.get(1).atMs - end : -1;
        check(idleAfter >= FeedTags.IDLE_AFTER_MS - 700 && idleAfter <= FeedTags.IDLE_AFTER_MS + 1500, "idle text " + idleAfter + " ms after the call's audio ended");
        Thread.sleep(4000);
        check(m.meta.size() == b5 + 2, "idle text sent once, not repeated");

        System.out.println("   only calls sent to this feed; test tone untagged; tags off");
        int b6 = m.meta.size();
        Talkgroup other = tg(2050, "EX OTHER", "", ""); other.streams.clear();
        playCall(st, 60, other, -1, 0, 1.0);
        st.testTone("Feed");
        Thread.sleep(9000);
        check(m.meta.size() == b6, "a talkgroup not assigned to the feed and the test tone: no tag (" + (m.meta.size() - b6) + ")");
        StreamConfig c4 = c3.copy(); c4.sendTags = false;
        st.reconfigure(List.of(c4), CallMixer.Mode.QUEUE, 30_000);
        playCall(st, 61, tg(2051, "EX OFF", "", ""), -1, 0, 1.0);
        Thread.sleep(5000);
        check(m.meta.size() == b6, "tags off (live): nothing sent, idle text included");

        System.out.println("   Send test tag");
        var ft = st.testTag("Feed", "TrunkDeck test tag 12:00:00");
        String res = ft.get(10, TimeUnit.SECONDS);
        check(res.startsWith("accepted") && "TrunkDeck test tag 12:00:00".equals(m.meta.get(m.meta.size() - 1).song()), "test tag sent even with tags off: " + res);
        check(st.testTag("No such feed", "x") == null, "test tag on a feed that is not running: null");
        m.metaStatus = 401;
        Thread.sleep(FeedTags.MIN_GAP_MS);
        String res2 = st.testTag("Feed", "TrunkDeck test tag 2").get(10, TimeUnit.SECONDS);
        check(res2.startsWith("not accepted: HTTP 401") && res2.contains("password") && !res2.contains("hackme"), "refused test tag: " + res2);
        m.metaStatus = 200;

        System.out.println("   failure isolation: hanging, failing and cut metadata requests while calls play");
        StreamConfig c5 = c3.copy(); c5.tagIdle = "";
        st.reconfigure(List.of(c5), CallMixer.Mode.QUEUE, 30_000);
        long blocks0 = fd.blocks, conn0 = fd.connections, drops0 = fd.drops; long w0 = System.currentTimeMillis();
        int logs0 = log.size();
        m.metaHang = true;
        FeedTags.readTimeoutMs = 2000;
        for (int i = 0; i < 4; i++) playCall(st, 70 + i, tg(2060 + i, "EX HANG " + i, "", ""), -1, 0, 1.0);
        m.metaHang = false; m.metaStatus = 500;
        for (int i = 0; i < 3; i++) playCall(st, 80 + i, tg(2070 + i, "EX ERR " + i, "", ""), -1, 0, 1.0);
        m.metaStatus = 200; m.metaClose = true;
        for (int i = 0; i < 3; i++) playCall(st, 90 + i, tg(2080 + i, "EX CUT " + i, "", ""), -1, 0, 1.0);
        m.metaClose = false;
        long w1 = System.currentTimeMillis();
        double secs = (w1 - w0) / 1000.0, sentSecs = (fd.blocks - blocks0) / 10.0;
        check(Math.abs(sentSecs - secs) < 0.6, String.format(Locale.ROOT, "audio kept real time: %.1f s sent in %.1f s", sentSecs, secs));
        check(src.maxGapMs() < 800, "no gap in the audio arriving at the server (longest " + src.maxGapMs() + " ms)");
        check(fd.connections == conn0 && fd.drops == drops0 && m.accepted().size() == 1 && src.closedMs == 0, "the audio connection was never dropped or reopened (" + m.accepted().size() + " SOURCE connection)");
        long failLogs = log.subList(logs0, log.size()).stream().filter(l -> l.contains("now-playing tag not accepted")).count();
        check(failLogs == 1, "failures logged once (" + failLogs + ")");
        check(fd.tags.failed > 0 && fd.tags.describe().contains("FAILING"), "status shows it: " + fd.tags.describe());
        Thread.sleep(FeedTags.MIN_GAP_MS + 2500);
        int b7 = m.meta.size();
        playCall(st, 99, tg(2099, "EX BACK", "", ""), -1, 0, 1.0);
        check(waitFor(() -> m.meta.size() > b7 && "2099 EX BACK".equals(m.meta.get(m.meta.size() - 1).song()) && m.meta.get(m.meta.size() - 1).status == 200, 6000), "works again by itself afterwards");
        check(waitFor(() -> log.stream().anyMatch(l -> l.contains("now-playing tags accepted again")), 2000), "recovery logged");
        check(log.stream().noneMatch(l -> l.contains("hackme")), "the password is never logged");
        FeedTags.readTimeoutMs = 4000;

        System.out.println("   the audio stream itself is untouched");
        byte[] bytes = src.bytes();
        Map<String, Integer> fr = FeedTests.frames(bytes);
        check(!fr.containsKey("bad") && fr.size() == 1, "only MP3 frames on the audio connection, never metadata (" + fr + ")");
        check(!new String(bytes, StandardCharsets.ISO_8859_1).contains("StreamTitle") && !new String(bytes, StandardCharsets.ISO_8859_1).contains("EX CO"), "no inline title bytes in the audio");

        System.out.println("   reconnect: the playing call is tagged again (the server starts the mount without a title)");
        int b8 = m.meta.size();
        Thread caller = playAsync(st, 100, tg(2100, "EX LONG", "", ""), -1, 6.0);
        check(waitFor(() -> m.meta.size() > b8, 3000), "tagged");
        Thread.sleep(400);
        m.drop();
        check(waitFor(() -> m.accepted().size() == 2 && fd.connectedSince > 0, 6000), "reconnected after the server dropped it");
        check(waitFor(() -> m.meta.size() >= b8 + 2 && "2100 EX LONG".equals(m.meta.get(m.meta.size() - 1).song()), 5000), "the same call tagged again after the reconnect (" + songsFrom(m, b8) + ")");
        caller.join();
        st.stop();

        System.out.println("4. Mix mode: overlapping calls, the latest to start wins");
        MockIcecast m2 = new MockIcecast("hackme");
        Streaming sm = feed(m2, cfg(m2), CallMixer.Mode.MIX);
        check(waitFor(() -> m2.accepted().size() == 1 && sm.feeds().get(0).connectedSince > 0, 5000), "mix feed connected");
        Thread x1 = playAsync(sm, 1, tg(2201, "EX MIX A", "", ""), -1, 3.0); Thread.sleep(2000);
        Thread x2 = playAsync(sm, 2, tg(2202, "EX MIX B", "", ""), -1, 2.0);
        x1.join(); x2.join();
        check(waitFor(() -> m2.meta.size() >= 2, 3000) && m2.songs().equals(List.of("EX MIX A", "EX MIX B")), "both tagged in start order: " + m2.songs());
        sm.stop();
        m.stop(); m2.stop();
    }
}
