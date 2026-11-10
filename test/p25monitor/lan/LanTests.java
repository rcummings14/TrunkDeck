package p25monitor.lan;

import p25monitor.App;
import p25monitor.CallEvent;
import p25monitor.RadioSystem;
import p25monitor.Talkgroup;
import p25monitor.nativeengine.AudioOut;
import p25monitor.nativeengine.NativeEngine;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Arrays;
import java.util.Properties;

/**
 * Home-network page, without a display and without a radio.
 * The protocol checks use a stand-in for the buttons. The last check drives a real engine:
 * start, stop and mute go through {@link NativeEngine#start}, {@link NativeEngine#stop} and
 * {@link NativeEngine#updateTalkgroups}, which is what the Monitor buttons do.
 */
public final class LanTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    static final class Resp { int code; String body = ""; String setCookie; }

    static Resp call(int port, String method, String path, String json, String cookie) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(8));
        if (cookie != null) b.header("Cookie", cookie);
        if ("GET".equals(method)) b.GET();
        else b.method(method, HttpRequest.BodyPublishers.ofString(json == null ? "" : json));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        Resp out = new Resp();
        out.code = r.statusCode();
        out.body = r.body() == null ? "" : r.body();
        out.setCookie = r.headers().firstValue("set-cookie").orElse(null);
        return out;
    }

    static String cookie(String setCookie) {
        if (setCookie == null) return null;
        int semi = setCookie.indexOf(';');
        return semi < 0 ? setCookie.trim() : setCookie.substring(0, semi).trim();
    }

    /** Buttons the protocol tests count. Not the desktop; the engine check below uses the real ones. */
    static final class Counted implements LanServer.Actions {
        int starts, stops, mutes;
        boolean running, muted;
        public boolean monitoring() { return running; }
        public void start() { if (!running) { starts++; running = true; } }
        public void stop() { if (running) { stops++; running = false; } }
        public boolean setMuted(int id, boolean m) {
            if (id != 7) return false;
            mutes++; muted = m; return true;
        }
        public boolean setFavorite(int id, boolean favorite) { return id == 7; }
        public boolean setSpeaker(String mode, int talkgroup) { return !"HOLD".equals(mode) || talkgroup > 0; }
        public void setVolume(int percent) {}
        public void setSpeakerMuted(boolean muted) {}
        public LanServer.Snapshot now() {
            LanServer.Snapshot s = new LanServer.Snapshot();
            s.monitoring = running;
            s.activity = running ? "scanning" : "stopped";
            s.system = "Example System";
            s.site = "Site 1";
            s.control = "CC 450.0000";
            s.channels.add("450.0000");
            s.signalBars = running ? 4 : 0;
            s.signalText = running ? "-42 dB" : "\u2014";
            if (running) s.signalDb = -42;
            LanServer.Active a = new LanServer.Active();
            a.tg = 7; a.name = "North Dispatch"; a.radio = "Engine 4"; a.state = "VOICE"; a.onSpeaker = running;
            s.active.add(a);
            s.activeTitle = "ACTIVE CALLS  ·  0 DECODING  ·  SPEAKER: PRIORITY";
            LanServer.Group g = new LanServer.Group();
            g.id = 7; g.name = "Say \"hi\" <North>"; g.favorite = true; g.muted = muted;
            s.groups.add(g);
            LanServer.Recent r = new LanServer.Recent();
            r.time = "12:00:00"; r.id = 7; r.name = "North Dispatch"; r.radio = "Engine 4";
            s.recent.add(r);
            return s;
        }
    }

    static final class Silent implements AudioOut.Sink {
        public void open(int rate) {}
        public void write(byte[] b, int off, int len) {}
        public void close() {}
    }

    public static void main(String[] a) {
        LanServer server = null, blocked = null, audioServer = null, engineServer = null;
        NativeEngine engine = null;
        try {
            Path tmp = Files.createTempDirectory("lan-test");
            System.setProperty("trunkdeck.profile", tmp.resolve("profile").toString());
            addresses();
            json();
            Properties props = new Properties();
            secret(tmp);
            Counted ui = new Counted();
            server = new LanServer(ui, props);
            check(server.start(0) != null && !server.running(), "no password: the page does not start");
            check(LanSecret.set(props, "home-pin".toCharArray()), "password chosen");
            check(!props.getProperty(LanSecret.KEY, "").contains("home-pin"), "the password is not stored in plain text");
            String err = server.start(0);
            check(err == null && server.running() && server.port() > 0, "switch on: listening (" + err + ")");
            int port = server.port();
            props.setProperty("recordings.dir", "/tmp/trunkdeck-keep");
            page(port);
            auth(server, ui, port);
            check("/tmp/trunkdeck-keep".equals(props.getProperty("recordings.dir")), "home-network page did not change the recordings folder");
            for (String line : server.notes()) check(!line.contains("home-pin") && !line.contains("nope-99"), "log line does not contain a password");
            server.stop();
            check(!server.running(), "switch off: not listening");
            boolean refused = false;
            try (Socket sk = new Socket()) { sk.connect(new InetSocketAddress("127.0.0.1", port), 500); }
            catch (Exception e) { refused = true; }
            check(refused, "switch off: the port no longer accepts a connection");

            blocked = new LanServer(new Counted(), props, addr -> false);
            check(blocked.start(0) == null, "server with a reject-all check still binds");
            Resp denied = call(blocked.port(), "GET", "/", null, null);
            check(denied.code == 403, "a computer that is not on the home network gets 403, got " + denied.code);
            Resp deniedLogin = call(blocked.port(), "POST", "/api/login", "{\"password\":\"home-pin\"}", null);
            check(deniedLogin.code == 403, "that computer cannot try the password, got " + deniedLogin.code);
            blocked.stop();

            audioServer = new LanServer(new Counted(), props);
            check(audioServer.start(0) == null, "audio server up");
            Resp signed = call(audioServer.port(), "POST", "/api/login", "{\"password\":\"home-pin\"}", null);
            audio(audioServer, cookie(signed.setCookie));
            audioServer.stop();

            RadioSystem[] sys = new RadioSystem[1];
            engine = engine(sys);
            engineServer = new LanServer(new EngineHost(engine, sys[0]), props);
            engine.setSpeakerTap(engineServer.speakerTap());
            check(engineServer.start(0) == null, "engine page up");
            Resp es = call(engineServer.port(), "POST", "/api/login", "{\"password\":\"home-pin\"}", null);
            realEngine(engine, engineServer, cookie(es.setCookie));
        } catch (Throwable t) {
            t.printStackTrace();
            fail++;
        } finally {
            if (server != null) server.stop();
            if (blocked != null) blocked.stop();
            if (audioServer != null) audioServer.stop();
            if (engineServer != null) engineServer.stop();
            if (engine != null) engine.stop();
        }
        System.out.println("LanTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void addresses() throws Exception {
        check(LanNet.allowed(InetAddress.getByName("127.0.0.1")), "localhost");
        check(LanNet.allowed(InetAddress.getByName("127.8.8.8")), "127/8");
        check(LanNet.allowed(InetAddress.getByName("::1")), "ipv6 localhost");
        check(LanNet.allowed(InetAddress.getByName("10.1.2.3")), "10/8");
        check(LanNet.allowed(InetAddress.getByName("172.16.0.1")), "172.16/12 low");
        check(LanNet.allowed(InetAddress.getByName("172.31.255.255")), "172.16/12 high");
        check(LanNet.allowed(InetAddress.getByName("192.168.1.20")), "192.168/16");
        check(!LanNet.allowed(InetAddress.getByName("8.8.8.8")), "public");
        check(!LanNet.allowed(InetAddress.getByName("1.1.1.1")), "public 2");
        check(!LanNet.allowed(InetAddress.getByName("172.15.5.5")), "just below 172.16");
        check(!LanNet.allowed(InetAddress.getByName("172.32.0.1")), "just above 172.31");
        check(!LanNet.allowed(InetAddress.getByName("192.169.0.1")), "not 192.168");
        check(!LanNet.allowed(InetAddress.getByName("11.0.0.1")), "not 10/8");
        check(!LanNet.allowed(InetAddress.getByName("169.254.1.1")), "link-local is not the home network");
        check(!LanNet.allowed(InetAddress.getByName("100.64.0.1")), "shared address space is not the home network");
        check(!LanNet.allowed(InetAddress.getByName("2001:db8::1")), "public ipv6");
        check(!LanNet.allowed(null), "missing address");
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff; mapped[11] = (byte) 0xff;
        mapped[12] = (byte) 192; mapped[13] = (byte) 168; mapped[14] = 0; mapped[15] = 9;
        check(LanNet.allowed(InetAddress.getByAddress(mapped)), "ipv4-mapped 192.168");
        mapped[12] = 8; mapped[13] = 8; mapped[14] = 8; mapped[15] = 8;
        check(!LanNet.allowed(InetAddress.getByAddress(mapped)), "ipv4-mapped public");
    }

    static void json() {
        check("a\"b\\c".equals(LanJson.stringField("{\"password\":\"a\\\"b\\\\c\"}", "password")), "password with quotes and slashes");
        check("A".equals(LanJson.stringField("{\"password\":\"\\u0041\"}", "password")), "password unicode escape");
        check(Integer.valueOf(101).equals(LanJson.intField("{\"id\":101,\"muted\":true}", "id")), "id");
        check(Boolean.TRUE.equals(LanJson.boolField("{\"id\":101,\"muted\":true}", "muted")), "muted true");
        check(Boolean.FALSE.equals(LanJson.boolField("{\"muted\":false}", "muted")), "muted false");
        check(LanJson.stringField("{}", "password") == null, "missing password");
        String q = LanJson.quote("Say \"hi\" <North>\n");
        check(q.contains("\\\"") && q.contains("\\n") && !q.contains("\n"), "names are escaped");
    }

    static void secret(Path tmp) throws Exception {
        Properties props = new Properties();
        check(!LanSecret.isSet(props), "no password until one is chosen");
        check(!LanSecret.set(props, "ab".toCharArray()), "fewer than 4 characters is refused");
        check(!LanSecret.isSet(props), "a refused password is not stored");
        App app = new App(tmp);
        app.load();
        check(LanSecret.set(app.settings, "home-pin".toCharArray()), "password saved in settings");
        app.saveSettings();
        check(LanSecret.verify(app.settings, "home-pin".toCharArray()), "the same password works");
        check(!LanSecret.verify(app.settings, "nope-99".toCharArray()), "a different password does not");
        Path settings = app.profile.resolve("settings.properties");
        String stored = Files.readString(settings);
        check(!stored.contains("home-pin"), "settings file does not contain the password");
        if (Files.getFileStore(settings).supportsFileAttributeView(PosixFileAttributeView.class)) {
            var perms = Files.getPosixFilePermissions(settings);
            check(perms.equals(PosixFilePermissions.fromString("rw-------")), "settings file is owner-only, got " + perms);
        } else System.out.println("  skip  owner-only check (this OS has no POSIX permissions)");
    }

    static void page(int port) throws Exception {
        Resp r = call(port, "GET", "/", null, null);
        check(r.code == 200 && r.body.contains("Listen") && r.body.contains(">Start<") && r.body.contains(">Stop<"), "page has Listen, Start and Stop");
        check(r.body.contains("SCANNING") && r.body.contains("ACTIVE CALLS") && r.body.contains("UNIT / RADIO") && r.body.contains("Hold one talkgroup"),
                "page is laid out like the Monitor");
        check(r.body.contains("col.freq") && r.body.contains("col.radio") && r.body.contains("col.event") && r.body.contains("col.calltime")
                && r.body.contains("col.glow") && r.body.contains("col.scan") && r.body.contains("col.labels") && r.body.contains("col.talkgroup"),
                "the glass uses each custom colour");
        check(r.body.contains("SCAN · CHANNEL") && r.body.contains("SCAN · CONTROL CH"), "conventional systems label the channel, trunked systems the control channel");
        check(r.body.toLowerCase().contains("home network"), "page says it is for the home network");
        check(!r.body.contains("Choose folder") && !r.body.contains("recordings.dir"), "home-network page has no recordings-folder control");
        check(!r.body.contains("home-pin") && !r.body.toLowerCase().contains("password123"), "page does not contain a password");
        check(!r.body.contains("RadioReference"), "page does not mention RadioReference");
    }

    static void auth(LanServer server, Counted ui, int port) throws Exception {
        Resp state = call(port, "GET", "/api/state", null, null);
        check(state.code == 401, "state without the password is refused, got " + state.code);
        Resp bad = call(port, "POST", "/api/login", "{\"password\":\"nope-99\"}", null);
        check(bad.code == 401, "wrong password is refused, got " + bad.code);
        Resp ok = call(port, "POST", "/api/login", "{\"password\":\"home-pin\"}", null);
        String c = cookie(ok.setCookie);
        check(ok.code == 200 && c != null && c.startsWith("tdlan="), "right password signs in");
        Resp again = call(port, "GET", "/api/state", null, c);
        check(again.code == 200 && again.body.contains("Say \\\"hi\\\"") && again.body.contains("North Dispatch") && again.body.contains("Engine 4"),
                "signed-in state has the talkgroup and a recent call");
        check(again.body.contains("\"favorite\":true"), "favorite is marked");
        check(again.body.contains("\"system\":\"Example System\"") && again.body.contains("\"control\":\"CC 450.0000\"")
                && again.body.contains("\"signalBars\"") && again.body.contains("North Dispatch") && again.body.contains("\"active\":["),
                "state carries the monitor: system, control channel, signal, active call");
        check(!again.body.contains("recordings.dir"), "monitor state does not include the recordings folder");
        Resp recFolder = call(port, "POST", "/api/recordings", "{\"dir\":\"/tmp/other\"}", c);
        check(recFolder.code == 404, "home-network page cannot set the recordings folder, got " + recFolder.code);
        Resp start = call(port, "POST", "/api/start", "{}", c);
        check(start.code == 200 && ui.starts == 1 && ui.running, "Start calls start");
        Resp startAgain = call(port, "POST", "/api/start", "{}", c);
        check(startAgain.code == 200 && ui.starts == 1, "Start while already monitoring does not start a second time");
        Resp mute = call(port, "POST", "/api/mute", "{\"id\":7,\"muted\":true}", c);
        check(mute.code == 200 && ui.mutes == 1 && ui.muted && mute.body.contains("\"muted\":true"), "Mute calls mute");
        Resp missing = call(port, "POST", "/api/mute", "{\"id\":999,\"muted\":true}", c);
        check(missing.code == 404 && ui.mutes == 1, "an unknown talkgroup is not muted");
        Resp stop = call(port, "POST", "/api/stop", "{}", c);
        check(stop.code == 200 && ui.stops == 1 && !ui.running, "Stop calls stop");
        Resp out = call(port, "POST", "/api/logout", "{}", c);
        check(out.code == 200, "sign out");
        Resp after = call(port, "GET", "/api/state", null, c);
        check(after.code == 401, "the old sign-in no longer works");
    }

    static void audio(LanServer server, String cookie) throws Exception {
        byte[][] got = new byte[1][];
        Thread reader = new Thread(() -> {
            try { got[0] = readAudio(server.port(), cookie, 4000, 2500); }
            catch (Exception e) { e.printStackTrace(); }
        }, "lan-audio-test");
        reader.start();
        long end = System.currentTimeMillis() + 2000;
        while (server.listeners() == 0 && System.currentTimeMillis() < end) Thread.sleep(20);
        check(server.listeners() == 1, "Listen is connected");
        AudioOut out = new AudioOut(new Silent());
        check(out.open(), "test speaker opens");
        out.setPcmTap(server.speakerTap());
        float[] tone = new float[160];
        Arrays.fill(tone, 0.25f);
        out.setMuted(true);
        out.setVolume(0f);
        out.play(3, tone, tone.length);
        reader.join(3000);
        out.close();
        byte[] b = got[0] == null ? new byte[0] : got[0];
        int hit = 0;
        for (int i = 0; i + 1 < b.length; i += 2) if (b[i] == 0x00 && b[i + 1] == 0x20) hit++;
        check(hit >= 100, "Listen hears the speaker audio even when this computer's speaker is muted (" + hit + " samples, " + b.length + " bytes)");
    }

    static byte[] readAudio(int port, String cookie, int max, long ms) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new URL("http://127.0.0.1:" + port + "/api/audio").openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(400);
        if (cookie != null) c.setRequestProperty("Cookie", cookie);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        long end = System.currentTimeMillis() + ms;
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[1024];
            while (bos.size() < max && System.currentTimeMillis() < end) {
                int k;
                try { k = in.read(buf); }
                catch (SocketTimeoutException e) { continue; }
                if (k < 0) break;
                if (k > 0) bos.write(buf, 0, k);
            }
        } finally { c.disconnect(); }
        return bos.toByteArray();
    }

    static NativeEngine engine(RadioSystem[] out) throws Exception {
        App app = new App(Path.of(System.getProperty("trunkdeck.profile")).getParent());
        app.load();
        RadioSystem sys = new RadioSystem();
        sys.id = "example";
        sys.name = "Example System";
        sys.controlChannelsMHz.add(450.0);
        Talkgroup a = new Talkgroup(); a.decimal = 101; a.alphaTag = "North Dispatch"; a.favorite = true;
        Talkgroup b = new Talkgroup(); b.decimal = 202; b.alphaTag = "Town Works";
        sys.talkgroups.add(a); sys.talkgroups.add(b);
        sys.radios.setManual(555, "Engine 4");
        app.current = sys;
        out[0] = sys;
        NativeEngine eng = new NativeEngine(app, new NativeEngine.Listener() {
            public void callStarted(CallEvent e) {}
            public void callUpdated(CallEvent e) {}
            public void messages(int count, String last) {}
        });
        eng.audioSinkOverride = new Silent();
        return eng;
    }

    /** The page's Start, Stop and Mute, wired to the real engine (not a counter). */
    static final class EngineHost implements LanServer.Actions {
        final NativeEngine engine;
        final RadioSystem system;
        EngineHost(NativeEngine e, RadioSystem s) { engine = e; system = s; }
        public boolean monitoring() { return engine.isRunning(); }
        public void start() {
            if (engine.isRunning()) return;
            try { engine.start(system, NativeEngine.Mode.SIMULATED); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        public void stop() { if (engine.isRunning()) engine.stop(); }
        public boolean setMuted(int id, boolean muted) {
            Talkgroup t = system.find(id);
            if (t == null) return false;
            t.muted = muted;
            engine.updateTalkgroups(system.talkgroups);
            return true;
        }
        public boolean setFavorite(int id, boolean favorite) {
            Talkgroup t = system.find(id);
            if (t == null) return false;
            t.favorite = favorite;
            engine.updateTalkgroups(system.talkgroups);
            return true;
        }
        public boolean setSpeaker(String mode, int talkgroup) {
            NativeEngine.SpeakerMode m;
            try { m = NativeEngine.SpeakerMode.valueOf(mode); } catch (Exception ex) { return false; }
            if (m == NativeEngine.SpeakerMode.HOLD && talkgroup <= 0) return false;
            engine.setSpeakerMode(m, talkgroup);
            return true;
        }
        public void setVolume(int percent) { engine.setSpeakerVolume(percent); }
        public void setSpeakerMuted(boolean muted) { engine.setSpeakerMuted(muted); }
        public LanServer.Snapshot now() {
            LanServer.Snapshot s = new LanServer.Snapshot();
            s.monitoring = engine.isRunning();
            s.activity = s.monitoring ? "scanning" : "stopped";
            s.system = system.name;
            s.site = system.site;
            s.control = system.freqText().isBlank() ? "" : "CC " + system.freqText();
            double db = engine.controlSignalDb();
            s.signalDb = db;
            s.signalBars = p25monitor.SignalMeter.bars(db);
            s.signalText = p25monitor.SignalMeter.text(db);
            s.speaker = engine.speakerMode().name();
            s.holdTg = engine.holdTg();
            s.volume = engine.speakerVolume();
            s.speakerMuted = engine.speakerMuted();
            for (Talkgroup t : system.talkgroups) {
                LanServer.Group g = new LanServer.Group();
                g.id = t.decimal; g.name = t.displayName(); g.favorite = t.favorite; g.muted = t.muted;
                s.groups.add(g);
            }
            if (engine.isRunning()) for (p25monitor.nativeengine.CallView c : engine.activeCalls()) {
                LanServer.Active a = new LanServer.Active();
                a.tg = c.tg; a.name = system.find(c.tg) == null ? "" : system.find(c.tg).displayName();
                a.onSpeaker = c.onSpeaker; a.state = c.state == null ? "" : c.state;
                s.active.add(a);
            }
            return s;
        }
    }

    static void realEngine(NativeEngine engine, LanServer server, String cookie) throws Exception {
        int port = server.port();
        int before = engine.starts;
        Resp start = call(port, "POST", "/api/start", "{}", cookie);
        check(start.code == 200 && engine.isRunning() && engine.starts == before + 1, "Start monitoring runs the real engine, got " + start.code + " " + start.body);
        Resp again = call(port, "POST", "/api/start", "{}", cookie);
        check(again.code == 200 && engine.starts == before + 1, "Start does not restart an engine that is already running");
        byte[][] got = new byte[1][];
        Thread reader = new Thread(() -> {
            try { got[0] = readAudio(port, cookie, 8000, 2500); }
            catch (Exception e) { e.printStackTrace(); }
        }, "lan-engine-audio");
        reader.start();
        long end = System.currentTimeMillis() + 2000;
        while (server.listeners() == 0 && System.currentTimeMillis() < end) Thread.sleep(20);
        java.lang.reflect.Field f = NativeEngine.class.getDeclaredField("audio");
        f.setAccessible(true);
        AudioOut ao = (AudioOut) f.get(engine);
        check(ao != null, "engine has a speaker");
        ao.setMuted(true);
        float[] tone = new float[160];
        Arrays.fill(tone, 0.25f);
        ao.play(9, tone, tone.length);
        reader.join(3000);
        byte[] b = got[0] == null ? new byte[0] : got[0];
        int hit = 0;
        for (int i = 0; i + 1 < b.length; i += 2) if (b[i] == 0x00 && (b[i + 1] & 0xff) == 0x20) hit++;
        check(hit >= 100, "after the engine replaces the speaker, Listen still hears it (" + hit + ")");

        Resp mute = call(port, "POST", "/api/mute", "{\"id\":101,\"muted\":true}", cookie);
        engine.awaitLive();
        Talkgroup copy = engine.engineTalkgroup(101);
        check(mute.code == 200 && copy != null && copy.muted && engine.engineTalkgroup(202) != null && !engine.engineTalkgroup(202).muted,
                "Mute uses the real talkgroup mute");
        check(mute.body.contains("North Dispatch") && mute.body.contains("\"favorite\":true"), "the list shows the name and the favorite");
        check(mute.body.contains("\"system\":\"Example System\"") && mute.body.contains("\"control\":\"CC ") && mute.body.contains("\"signal\""),
                "the live state includes the system, control channel and signal");
        Resp unknown = call(port, "POST", "/api/mute", "{\"id\":404,\"muted\":true}", cookie);
        check(unknown.code == 404, "mute of a talkgroup that is not in the list is refused");

        int starts = engine.starts;
        Resp holdNo = call(port, "POST", "/api/speaker", "{\"mode\":\"HOLD\"}", cookie);
        check(holdNo.code == 400 && engine.speakerMode() != NativeEngine.SpeakerMode.HOLD, "Hold without a talkgroup is refused");
        Resp hold = call(port, "POST", "/api/speaker", "{\"mode\":\"HOLD\",\"id\":101}", cookie);
        check(hold.code == 200 && engine.speakerMode() == NativeEngine.SpeakerMode.HOLD && engine.holdTg() == 101, "Hold uses the real speaker hold");
        Resp vol = call(port, "POST", "/api/volume", "{\"volume\":40}", cookie);
        check(vol.code == 200 && engine.speakerVolume() == 40, "volume uses the real speaker volume");
        Resp sm = call(port, "POST", "/api/speakermute", "{\"muted\":true}", cookie);
        check(sm.code == 200 && engine.speakerMuted(), "speaker mute uses the real speaker mute");
        check(engine.starts == starts, "speaker changes do not restart monitoring");

        Resp stop = call(port, "POST", "/api/stop", "{}", cookie);
        check(stop.code == 200 && !engine.isRunning(), "Stop runs the real engine stop");
    }
}
