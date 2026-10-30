package p25monitor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Application state shared by all pages: profile folder, systems, streams, settings. */
public class App {
    public static final String NAME = "TrunkDeck";
    public static final String VERSION = "1.5.0";

    public final Path home;      // install folder (contains app/ and runtime/)
    public final Path profile;   // per-user data
    public final List<RadioSystem> systems = new ArrayList<>();
    public final List<StreamConfig> streams = new ArrayList<>();
    public final Properties settings = new Properties();
    public RadioSystem current;

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
        if (Files.exists(settingsFile())) try (Reader r = Files.newBufferedReader(settingsFile(), StandardCharsets.UTF_8)) { settings.load(r); }
        systems.clear();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(systemsDir())) {
            for (Path d : ds) if (Files.exists(d.resolve("system.properties"))) try { systems.add(RadioSystem.load(d)); } catch (Exception ignored) {}
        }
        boolean firstRun = !"true".equals(settings.getProperty("presetsInstalled"));
        if (firstRun) {
            for (RadioSystem p : RadioSystem.presets()) {
                if (systems.stream().noneMatch(s -> s.id.equals(p.id))) { save(p); systems.add(p); }
            }
            settings.setProperty("presetsInstalled", "true");
            settings.setProperty("lastSystem", RadioSystem.presets().get(0).id); // first (example) preset selected by default
            saveSettings();
        }
        systems.sort(Comparator.comparing(s -> s.name.toLowerCase()));
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
            try (Writer w = Files.newBufferedWriter(settingsFile(), StandardCharsets.UTF_8)) { settings.store(w, NAME + " settings"); }
        } catch (IOException ignored) {}
    }

    public ListenMode listenMode() {
        try { return ListenMode.valueOf(settings.getProperty("listenMode", "ALL")); } catch (Exception e) { return ListenMode.ALL; }
    }
}
