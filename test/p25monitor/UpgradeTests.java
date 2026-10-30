package p25monitor;

import java.nio.file.*;
import java.util.*;
import java.util.prefs.Preferences;

/**
 * Upgrade from 1.1.x: engine=sdrtrunk switches to the TrunkDeck engine, the JMBE jar is copied into the profile,
 * recordings stay where they were, TrunkDeck's own SDRTrunk playlists and playlist preferences are removed, and
 * everything else (SDRTrunk's own playlists, settings, talkgroups) is untouched.
 * Runs in its own JVM: user.home and the Java preferences root point at a temporary folder.
 */
public final class UpgradeTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("  FAIL: " + what); } }

    public static void main(String[] a) throws Exception {
        Path home = Files.createTempDirectory("upgrade-home");
        System.setProperty("user.home", home.toString());
        System.setProperty("java.util.prefs.userRoot", home.resolve(".java-prefs").toString());
        Path profile = home.resolve(".trunkdeck");
        System.setProperty("trunkdeck.profile", profile.toString());
        // a 1.1.x profile
        Files.createDirectories(profile.resolve("systems"));
        Files.writeString(profile.resolve("settings.properties"), "engine=sdrtrunk\nhideEngine=true\nnative.hangMs=3000\nsystem=My County\n");
        Files.writeString(profile.resolve("systems").resolve("My County.properties"), "name=My County\n");
        Files.writeString(profile.resolve("streams.properties"), "stream.0.name=Feed\n");
        // SDRTrunk-side files 1.1.x knew about
        Path sdr = home.resolve("SDRTrunk");
        Path jmbe = sdr.resolve("jmbe").resolve("jmbe-1.0.9.jar"); Files.createDirectories(jmbe.getParent()); Files.write(jmbe, new byte[]{1, 2, 3});
        Path rec = sdr.resolve("recordings"); Files.createDirectories(rec); Files.write(rec.resolve("20260101_TrunkDeck_TO_100.mp3"), new byte[]{9});
        Path pl = sdr.resolve("playlist"); Files.createDirectories(pl);
        Files.writeString(pl.resolve("TrunkDeck_My_County.xml"), "<playlist/>"); Files.writeString(pl.resolve("TrunkDeck_My_County.xml.backup"), "x");
        Files.writeString(pl.resolve("default.xml"), "<playlist/>");
        Preferences.userRoot().node(LegacyMigration.NODE_DECODER).put("path.jmbe.library.1.0.0", jmbe.toString());
        Preferences.userRoot().node(LegacyMigration.NODE_PLAYLIST).put("playlist.path", pl.resolve("TrunkDeck_My_County.xml").toString());
        Preferences.userRoot().node(LegacyMigration.NODE_PLAYLIST).put("playlist.list", pl.resolve("default.xml") + "," + pl.resolve("TrunkDeck_My_County.xml"));
        Preferences.userRoot().flush();

        App app = new App(home);
        app.load();   // runs the migration
        check(!app.settings.containsKey("engine") && !app.settings.containsKey("hideEngine"), "engine settings not removed");
        check("3000".equals(app.settings.getProperty("native.hangMs")), "other settings lost");
        check(Files.exists(profile.resolve("systems").resolve("My County.properties")), "system (talkgroups) file lost");
        check(Files.exists(profile.resolve("streams.properties")), "streams lost");
        check(Files.exists(profile.resolve("jmbe").resolve("jmbe-1.0.9.jar")), "JMBE jar not copied into the profile");
        check(Files.exists(jmbe), "original JMBE jar was touched");
        check(rec.toString().equals(app.settings.getProperty("recordings.dir")) && App.recordingDir().equals(rec), "recordings folder not kept");
        check(Files.exists(rec.resolve("20260101_TrunkDeck_TO_100.mp3")), "recording deleted");
        check(!Files.exists(pl.resolve("TrunkDeck_My_County.xml")) && !Files.exists(pl.resolve("TrunkDeck_My_County.xml.backup")), "TrunkDeck playlists not deleted");
        check(Files.exists(pl.resolve("default.xml")), "SDRTrunk's own playlist deleted");
        Preferences n = Preferences.userRoot().node(LegacyMigration.NODE_PLAYLIST);
        check(n.get("playlist.path", null) == null, "playlist preference still points at TrunkDeck's playlist");
        check(pl.resolve("default.xml").toString().equals(n.get("playlist.list", null)), "playlist list not cleaned: " + n.get("playlist.list", null));
        check(Files.exists(profile.resolve("upgrade-1.2.0.log")), "no upgrade log");
        Files.write(profile.resolve("jmbe").resolve("jmbe-api-1.0.0.jar"), new byte[]{4});   // an interface-only jar next to the codec
        Path chosen = p25monitor.nativeengine.EngineLibs.findJmbe(profile, "");
        check(chosen != null && chosen.getFileName().toString().equals("jmbe-1.0.9.jar"), "codec lookup picked " + chosen + " instead of jmbe-1.0.9.jar");
        List<String> again = LegacyMigration.run(app);
        check(again.isEmpty(), "second run did something: " + again);
        System.out.println(Files.readString(profile.resolve("upgrade-1.2.0.log")).trim().replaceAll("(?m)^", "  log "));
        System.out.printf("Upgrade migration: %d checks passed, %d failed%n", pass, fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
