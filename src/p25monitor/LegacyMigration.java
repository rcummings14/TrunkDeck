package p25monitor;

import java.nio.file.*;
import java.util.*;
import java.util.prefs.Preferences;

/**
 * One-time clean-up when upgrading from TrunkDeck 1.1.x, which could also run the separate SDRTrunk program as its
 * decoder. 1.2.0 decodes everything itself, so this:
 * <ul>
 * <li>switches a saved "engine=sdrtrunk" choice to the TrunkDeck engine (the setting no longer exists);</li>
 * <li>copies the JMBE voice-codec jar SDRTrunk created into TrunkDeck's own profile folder (profile/jmbe), so voice
 *     keeps working without SDRTrunk;</li>
 * <li>keeps recordings where they are: if 1.1.x wrote recordings into SDRTrunk's recordings folder, that folder stays
 *     the TrunkDeck recordings folder (nothing is moved or deleted);</li>
 * <li>deletes only what TrunkDeck itself created for SDRTrunk: the TrunkDeck_*.xml playlists, and the playlist
 *     preferences pointing at them. SDRTrunk's own files and settings are left alone (you may use SDRTrunk yourself).</li>
 * </ul>
 * The previously downloaded SDRTrunk program folder (install folder/engine) is removed by the installer.
 */
public final class LegacyMigration {
    private LegacyMigration() {}

    static final String NODE_DIR = "io/github/dsheirer/preference/directory";
    static final String NODE_DECODER = "io/github/dsheirer/preference/decoder";
    static final String NODE_PLAYLIST = "io/github/dsheirer/preference/playlist";

    static String pref(String node, String key) {
        try {
            Preferences root = Preferences.userRoot();
            if (!root.nodeExists(node)) return null;
            return root.node(node).get(key, null);
        } catch (Exception e) { return null; }
    }

    static Path legacyRoot() {
        String p = pref(NODE_DIR, "directory.application.root");
        return p != null ? Paths.get(p) : Paths.get(System.getProperty("user.home"), "SDRTrunk");
    }

    static Path legacyDir(String key, String sub) {
        String p = pref(NODE_DIR, key);
        return p != null ? Paths.get(p) : legacyRoot().resolve(sub);
    }

    /** Runs the clean-up once (it is safe to run again). Returns what it did, also logged to profile/upgrade-1.2.0.log. */
    public static List<String> run(App app) {
        List<String> done = new ArrayList<>();
        Properties s = app.settings;
        if (s.containsKey("engine")) {
            String was = s.getProperty("engine");
            s.remove("engine");
            if ("sdrtrunk".equalsIgnoreCase(was)) done.add("Engine setting 'SDRTrunk' replaced by the TrunkDeck engine.");
        }
        s.remove("hideEngine");
        if ("true".equals(s.getProperty("migrated.1.2.0"))) { if (!done.isEmpty()) app.saveSettings(); return done; }
        // the voice codec jar SDRTrunk created -> profile/jmbe
        try {
            Path own = app.profile.resolve("jmbe");
            boolean have = false;
            if (Files.isDirectory(own)) try (DirectoryStream<Path> ds = Files.newDirectoryStream(own, "jmbe-*.jar")) { for (Path p : ds) if (p25monitor.nativeengine.EngineLibs.isCodecJar(p)) have = true; }
            String jp = pref(NODE_DECODER, "path.jmbe.library.1.0.0");
            String configured = s.getProperty("native.jmbe", "");
            if (!have && jp != null && Files.isRegularFile(Paths.get(jp))) {
                Path src = Paths.get(jp);
                String name = src.getFileName().toString();
                if (!p25monitor.nativeengine.EngineLibs.isCodecJar(src)) name = "jmbe-imported.jar";
                Files.createDirectories(own);
                Files.copy(src, own.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                if (configured.equals(jp)) s.remove("native.jmbe");
                done.add("JMBE voice codec copied from " + src + " to " + own.resolve(name));
            }
        } catch (Exception e) { done.add("Could not copy the JMBE codec jar: " + e.getMessage()); }
        // recordings: keep using the folder 1.1.x wrote to, if it has TrunkDeck-era recordings
        if (s.getProperty("recordings.dir", "").isBlank()) {
            Path old = legacyDir("directory.recording", "recordings");
            if (hasFiles(old)) { s.setProperty("recordings.dir", old.toString()); done.add("Recordings stay in " + old); }
        }
        // the playlists TrunkDeck wrote for SDRTrunk, and the preferences pointing at them
        Path pl = legacyDir("directory.playlist", "playlist");
        if (Files.isDirectory(pl)) try (DirectoryStream<Path> ds = Files.newDirectoryStream(pl, "TrunkDeck_*.xml*")) {
            for (Path p : ds) { Files.deleteIfExists(p); done.add("Deleted TrunkDeck playlist " + p); }
        } catch (Exception e) { done.add("Could not delete TrunkDeck playlists in " + pl + ": " + e.getMessage()); }
        try {
            Preferences root = Preferences.userRoot();
            if (root.nodeExists(NODE_PLAYLIST)) {
                Preferences n = root.node(NODE_PLAYLIST);
                String cur = n.get("playlist.path", null);
                if (cur != null && Paths.get(cur).getFileName().toString().startsWith("TrunkDeck_")) { n.remove("playlist.path"); done.add("Removed the SDRTrunk playlist setting that pointed at " + cur); }
                String list = n.get("playlist.list", null);
                if (list != null) {
                    List<String> keep = new ArrayList<>();
                    for (String x : list.split(",")) if (!x.isBlank() && !Paths.get(x).getFileName().toString().startsWith("TrunkDeck_")) keep.add(x);
                    if (keep.isEmpty()) n.remove("playlist.list"); else n.put("playlist.list", String.join(",", keep));
                }
                n.flush();
            }
        } catch (Exception e) { done.add("Could not update SDRTrunk playlist preferences: " + e.getMessage()); }
        s.setProperty("migrated.1.2.0", "true");
        app.saveSettings();
        if (!done.isEmpty()) try {
            Files.createDirectories(app.profile);
            Files.writeString(app.profile.resolve("upgrade-1.2.0.log"), String.join(System.lineSeparator(), done) + System.lineSeparator());
        } catch (Exception ignored) {}
        return done;
    }

    static boolean hasFiles(Path d) {
        if (!Files.isDirectory(d)) return false;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d, "*.{mp3,wav}")) { return ds.iterator().hasNext(); } catch (Exception e) { return false; }
    }
}
