package p25monitor.lan;

import p25monitor.App;
import p25monitor.RadioSystem;
import p25monitor.Talkgroup;
import p25monitor.TouchControls;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

/**
 * Touch buttons: mute-all gain, hold, and a muted list that only contains talkgroups muted from that button.
 */
public final class TouchTests {
    static int pass, fail;
    static void check(boolean ok, String what) {
        if (ok) pass++;
        else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] a) throws Exception {
        gainAndHold();
        listAndPersist();
        pageAndServer();
        System.out.println("TouchTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void gainAndHold() {
        System.out.println("1. mute all silences output; hold does nothing without a talkgroup and is not saved");
        check(TouchControls.outputGain(false, false, 0.8f) == 0.8f, "normal speaker gain");
        check(TouchControls.outputGain(true, false, 0.8f) == 0f, "speaker mute still silences the speaker");
        check(TouchControls.outputGain(false, true, 0.8f) == 0f, "mute all silences the speaker without the speaker-mute flag");
        TouchControls t = new TouchControls();
        String none = t.toggleHold(-1);
        check(none != null && none.contains("No talkgroup") && !t.hold(), "hold with nothing on the air: " + none);
        check(t.toggleHold(100) == null && t.hold() && t.holdTg() == 100, "hold turns on");
        check(t.toggleHold(100) == null && !t.hold() && t.holdTg() == -1, "hold turns off");
        t.toggleHold(100);
        t.releaseHoldIfChanged(false, 100);
        check(!t.hold(), "hold drops when the speaker is no longer holding");
        t.setMuteAll(true);
        check(t.muteAll() && TouchControls.silenceAll, "mute all is on");
        t.setMuteAll(false);
        check(!TouchControls.silenceAll, "mute all is off");
    }

    static void listAndPersist() throws Exception {
        System.out.println("2. the muted list is only talkgroups muted from the touch button, and that mark survives a restart");
        Path home = Files.createTempDirectory("touch-home");
        System.setProperty("user.home", home.toString());
        System.setProperty("java.util.prefs.userRoot", home.resolve(".java-prefs").toString());
        System.setProperty("trunkdeck.profile", home.resolve(".trunkdeck").toString());
        System.clearProperty("trunkdeck.recordings");
        App app = new App(home);
        app.load();
        RadioSystem sys = app.current;
        check(sys != null && sys.id != null && !sys.id.isBlank(), "a system is loaded");
        Talkgroup touch = tg(101, "Sample One");
        Talkgroup other = tg(202, "Sample Two");
        other.muted = true;
        sys.talkgroups.add(touch);
        sys.talkgroups.add(other);
        TouchControls.markMuted(touch);
        TouchControls.reconcile(app);
        var list = TouchControls.listed(sys);
        check(list.size() == 1 && list.get(0).decimal == 101, "only the touch-muted talkgroup is listed");
        check(other.muted && !other.touchMuted, "a talkgroup muted another way stays muted and stays off the list");
        String why = TouchControls.unmute(sys, 202);
        check(why != null && other.muted, "the list cannot unmute a talkgroup it does not show: " + why);
        app.save(sys);
        String stored = app.settings.getProperty(TouchControls.KEY, "");
        check(stored.contains(sys.id + "\t101") && !stored.contains("\t202"), "the mark is saved next to that talkgroup only: " + stored.replace('\n', '|'));

        touch.muted = false;
        TouchControls.reconcile(app);
        check(!touch.touchMuted && TouchControls.listed(sys).isEmpty(), "unmuting it anywhere else removes it from the list");
        check(!app.settings.getProperty(TouchControls.KEY, "").contains("\t101"), "the saved mark is removed too");

        TouchControls.markMuted(touch);
        TouchControls.reconcile(app);
        app.save(sys);
        App again = new App(home);
        again.load();
        Talkgroup back = again.current.find(101);
        Talkgroup still = again.current.find(202);
        check(back != null && back.muted && back.touchMuted, "after a restart the touch mute is still muted and still on the list");
        check(still != null && still.muted && !still.touchMuted && TouchControls.listed(again.current).size() == 1, "the other mute is still not on the list");
        check(TouchControls.unmute(again.current, 101) == null && !again.current.find(101).muted, "tap in the list unmutes it");
        TouchControls.reconcile(again);
        check(TouchControls.listed(again.current).isEmpty(), "the list is empty after that");
    }

    static Talkgroup tg(int id, String name) {
        Talkgroup t = new Talkgroup();
        t.decimal = id;
        t.alphaTag = name;
        return t;
    }

    static void pageAndServer() throws Exception {
        System.out.println("3. the home-network page has no touch buttons, and those addresses are not there");
        String html = LanPage.HTML;
        check(!html.contains(">Mute all<") && !html.contains(">Mute talkgroup<") && !html.contains("id=\"mutedList\"") && !html.contains(">Hold<"),
                "page does not have the Display only buttons");
        check(!html.contains("/api/touch/"), "page does not call the touch addresses");
        check(html.contains(">Listen<") && html.contains(">Start<") && html.contains(">Stop<") && html.contains("liveText"),
                "page still has Listen, Start, Stop and the live text colour");
        check(html.contains("Hold one talkgroup"), "the speaker choice is still there");
        check(!html.contains("Choose folder"), "page still cannot change the recordings folder");

        Properties props = new Properties();
        check(LanSecret.set(props, "home-pin".toCharArray()), "password");
        LanServer server = new LanServer(new LanServer.Actions() {
            public boolean monitoring() { return false; }
            public void start() {}
            public void stop() {}
            public boolean setMuted(int id, boolean muted) { return false; }
            public boolean setSpeaker(String mode, int talkgroup) { return false; }
            public void setVolume(int percent) {}
            public void setSpeakerMuted(boolean muted) {}
            public boolean setFavorite(int id, boolean favorite) { return false; }
            public LanServer.Snapshot now() {
                LanServer.Snapshot s = new LanServer.Snapshot();
                s.liveText = "#FF4E5C";
                return s;
            }
        }, props);
        String err = server.start(0);
        check(err == null, "page listens: " + err);
        int port = server.port();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        HttpResponse<String> login = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/login"))
                .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.ofString("{\"password\":\"home-pin\"}")).build(), HttpResponse.BodyHandlers.ofString());
        String cookie = login.headers().firstValue("set-cookie").orElse("");
        int semi = cookie.indexOf(';');
        if (semi > 0) cookie = cookie.substring(0, semi);
        for (String path : new String[]{"/api/touch/muteall", "/api/touch/mutetg", "/api/touch/hold", "/api/touch/unmute"}) {
            HttpResponse<String> gone = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(5)).header("Cookie", cookie).POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            check(gone.statusCode() == 404, path + " is not there, got " + gone.statusCode());
        }
        HttpResponse<String> state = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/state"))
                .timeout(Duration.ofSeconds(5)).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        check(state.statusCode() == 200 && state.body().contains("\"liveText\":\"#FF4E5C\"") && !state.body().contains("touchMute"),
                "state still carries the live text colour and not the buttons, body " + state.body());
        server.stop();
    }
}
