package p25monitor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Application state shared by all pages: profile folder, systems, streams, settings. */
public class App {
    public static final String NAME = "TrunkDeck";
    public static final String VERSION = "1.7.4";

    public final Path home;      // install folder (contains app/ and runtime/)
    public final Path profile;   // per-user data
    public final List<RadioSystem> systems = new ArrayList<>();
    public final List<StreamConfig> streams = new ArrayList<>();
    public final Properties settings = new Properties();
    public RadioSystem current;
    /** This profile was new when loaded (1.6.0: TrunkDeck offers RadioReference setup). */
    public boolean firstRun;

    public App(Path home) {
        this.home = home;
        String override = System.getProperty("trunkdeck.profile");
        if (override != null) profile = Paths.get(override);
        else if (Util.isWindows() && System.getenv("APPDATA") != null) profile = Paths.get(System.getenv("APPDATA"), NAME);
        else profile = Paths.get(System.getProperty("user.home"), ".trunkdeck");
    }

    Path systemsDir() { return profile.resolve("systems"); }
    Path streamsFile() { return profile.resolve("streams.properties"); }
    Path settingsFile() { return profile.resolve("settings.properties"); }

    public void load() throws IOException {
        Files.createDirectories(systemsDir());
        DiagLog.attach(profile);
        if (Files.exists(settingsFile())) try (Reader r = Files.newBufferedReader(settingsFile(), StandardCharsets.UTF_8)) { settings.load(r); }
        systems.clear();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(systemsDir())) {
            for (Path d : ds) if (Files.exists(d.resolve("system.properties"))) try { systems.add(RadioSystem.load(d)); } catch (Exception ignored) {}
        }
        firstRun = !"true".equals(settings.getProperty("presetsInstalled"));
        if (firstRun) {
            for (RadioSystem p : RadioSystem.presets()) {
                if (systems.stream().noneMatch(s -> s.id.equals(p.id))) { save(p); systems.add(p); }
            }
            settings.setProperty("presetsInstalled", "true");
            settings.setProperty("lastSystem", RadioSystem.presets().get(0).id); // first (example) preset selected by default
            saveSettings();
        }
        systems.sort(Comparator.comparing(s -> s.name.toLowerCase()));
        bindTalkgroups();   // sites of one radio system share one list; separate talkgroups.csv files are merged
        String last = settings.getProperty("lastSystem", "");
        current = systems.stream().filter(s -> s.id.equals(last)).findFirst().orElse(systems.isEmpty() ? null : systems.get(0));
        streams.clear();
        streams.addAll(StreamConfig.load(streamsFile()));
        LegacyMigration.run(this);
        String rd = settings.getProperty("recordings.dir", "");
        recordings = rd.isBlank() ? null : Paths.get(rd);
    }

    private static volatile Path recordings;

    /** Folder for recorded calls: Settings "recordings.dir" (kept from earlier versions), else ~/TrunkDeck/recordings. */
    public static Path recordingDir() {
        String o = System.getProperty("trunkdeck.recordings");
        if (o != null && !o.isBlank()) return Paths.get(o);
        Path r = recordings;
        return r != null ? r : defaultRecordingDir();
    }

    static Path defaultRecordingDir() { return Paths.get(System.getProperty("user.home"), NAME, "recordings"); }

    public void save(RadioSystem s) throws IOException {
        if (s.id == null || s.id.isBlank()) s.id = uniqueId(RadioSystem.safeId(s.name));
        s.save(systemsDir().resolve(s.id));
        shareTalkgroupFile(s);   // the same list is stored in every matching site's folder, so a later load cannot drift
    }

    /**
     * Make sites of the same radio system share one talkgroup list. When that changes a site's rows, write them back
     * so the folder on disk matches what the Talkgroups page shows.
     */
    public void bindTalkgroups() {
        Map<RadioSystem, String> before = new IdentityHashMap<>();
        for (RadioSystem s : systems) before.put(s, TalkgroupCsv.render(s.talkgroups));
        TalkgroupLists.bind(systems);
        for (RadioSystem s : systems) {
            if (TalkgroupCsv.render(s.talkgroups).equals(before.get(s))) continue;
            try { writeTalkgroupsOnly(s); } catch (IOException ignored) {}
        }
    }

    /** Writes talkgroups.csv for one site. Does nothing until that site has a folder. */
    void writeTalkgroupsOnly(RadioSystem s) throws IOException {
        if (s.id == null || s.id.isBlank()) return;
        Path dir = systemsDir().resolve(s.id);
        if (!Files.isDirectory(dir)) return;
        TalkgroupCsv.writeAtomic(dir.resolve("talkgroups.csv"), TalkgroupCsv.render(s.talkgroups));
    }

    /** Copies this site's talkgroup list into the other sites that share it, skipping a file that already matches. */
    void shareTalkgroupFile(RadioSystem s) throws IOException {
        String text = TalkgroupCsv.render(s.talkgroups);
        for (RadioSystem o : systems) {
            if (o == s || o.talkgroups != s.talkgroups || o.id == null || o.id.isBlank()) continue;
            Path dir = systemsDir().resolve(o.id);
            if (!Files.isDirectory(dir)) continue;
            Path p = dir.resolve("talkgroups.csv");
            if (Files.isRegularFile(p) && text.equals(Files.readString(p))) continue;
            TalkgroupCsv.writeAtomic(p, text);
        }
    }

    String uniqueId(String base) {
        String id = base; int n = 2;
        while (Files.exists(systemsDir().resolve(id))) id = base + "_" + n++;
        return id;
    }

    public void delete(RadioSystem s) throws IOException {
        Path d = systemsDir().resolve(s.id);
        if (Files.isDirectory(d)) try (var w = Files.walk(d)) {
            w.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
        systems.remove(s);
    }

    public void saveStreams() throws IOException { StreamConfig.save(streamsFile(), streams); }

    public void saveSettings() {
        try {
            Files.createDirectories(profile);
            Path file = settingsFile();
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { settings.store(w, NAME + " settings"); }
            // the home-network password verifier lives in this file; keep it owner-only where the OS allows
            if (settings.getProperty("lan.pass") != null) Util.restrictToOwner(file);
        } catch (IOException ignored) {}
    }

    public ListenMode listenMode() {
        try { return ListenMode.valueOf(settings.getProperty("listenMode", "ALL")); } catch (Exception e) { return ListenMode.ALL; }
    }
}
