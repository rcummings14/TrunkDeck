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
    /** Settings key: id of the one system that starts when TrunkDeck opens. */
    public static final String AUTO_START_KEY = "autoStartSystem";
    /** Seconds the Monitor page counts down before auto-start. */
    public static final int AUTO_START_SECONDS = 10;

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
        bindRecordingFolders();
        TouchControls.applySaved(this);
    }

    /** Saved folder for new recordings, or null when the usual home-folder location is in use. */
    private static volatile Path recordings;
    /** Folders that still hold recordings from before a folder change. Newest first. */
    private static volatile List<Path> recordingAlso = List.of();
    /** Last writability check, so opening the Recordings page does not keep creating a test file. */
    private static Path probePath;
    private static long probeAt;
    private static String probeWhy;

    void bindRecordingFolders() {
        String rd = settings.getProperty("recordings.dir", "");
        recordings = rd == null || rd.isBlank() ? null : Paths.get(rd.trim());
        recordingAlso = List.copyOf(parseAlso(settings.getProperty("recordings.also", "")));
    }

    /**
     * Folder new call recordings (and their I/Q files) are written to.
     * A {@code -Dtrunkdeck.recordings} override wins, so tests stay in a temporary folder.
     * Otherwise Settings {@code recordings.dir} when that folder is still there and writable,
     * else the usual folder {@code ~/TrunkDeck/recordings} ({@code %USERPROFILE%\TrunkDeck\recordings} on Windows).
     * Read at the moment a call is saved, so a folder chosen on the Recordings page applies without a restart.
     * The home-network page cannot change this.
     */
    public static Path recordingDir() {
        String o = System.getProperty("trunkdeck.recordings");
        if (o != null && !o.isBlank()) return Paths.get(o);
        Path r = recordings;
        if (r != null && whyUnusable(r) == null) return r;
        return defaultRecordingDir();
    }

    static Path defaultRecordingDir() { return Paths.get(System.getProperty("user.home"), NAME, "recordings"); }

    /**
     * Plain-language reason new recordings are going to the usual folder instead of the one that was chosen,
     * or null when the chosen folder (or the usual folder, if none was chosen) is being used.
     */
    public static String recordingFolderNote() {
        String o = System.getProperty("trunkdeck.recordings");
        if (o != null && !o.isBlank()) return null;
        Path saved = recordings;
        if (saved == null || whyUnusable(saved) == null) return null;
        String usual = defaultRecordingDir().toString();
        if (!Files.exists(saved)) return "The chosen recordings folder isn't there (" + saved + "). New recordings are being saved in " + usual + " instead.";
        if (!Files.isDirectory(saved)) return "The chosen recordings folder isn't a folder (" + saved + "). New recordings are being saved in " + usual + " instead.";
        return "TrunkDeck can't save files in the chosen recordings folder (" + saved + "). New recordings are being saved in " + usual + " instead.";
    }

    /**
     * Where to look when listing or playing a saved call: the folder new recordings use, then any older folders.
     * Nothing is moved. With {@code -Dtrunkdeck.recordings} set, only that folder is searched.
     */
    public static List<Path> recordingSearchDirs() {
        String o = System.getProperty("trunkdeck.recordings");
        if (o != null && !o.isBlank()) return List.of(Paths.get(o));
        LinkedHashSet<Path> dirs = new LinkedHashSet<>();
        dirs.add(recordingDir());
        Path saved = recordings;
        if (saved != null) dirs.add(saved.toAbsolutePath().normalize());
        for (Path p : recordingAlso) if (p != null) dirs.add(p);
        return List.copyOf(dirs);
    }

    /**
     * Save {@code dir} as the folder for new recordings ({@code recordings.dir}) and use it immediately.
     * Choosing the usual folder clears the choice. Returns a plain-language message when the folder
     * cannot be used; the previous folder stays in place. Older recordings are left where they are.
     * Desktop only — the home-network page does not call this.
     */
    public String chooseRecordingFolder(Path dir) {
        forgetProbe();
        if (dir == null) return "No folder was chosen. New recordings will stay in " + recordingDir() + ".";
        Path next;
        try { next = dir.toAbsolutePath().normalize(); }
        catch (Exception e) { return "That folder can't be used. New recordings will stay in " + recordingDir() + "."; }
        if (samePath(next, defaultRecordingDir())) { useDefaultRecordingFolder(); return null; }
        String why = whyUnusable(next);
        if (why != null) return why + " New recordings will stay in " + recordingDir() + ".";
        Path writing = recordingDir();
        Path saved = recordings;
        List<Path> also = new ArrayList<>(recordingAlso);
        remember(also, writing, next);
        remember(also, saved, next);
        recordingAlso = List.copyOf(also);
        storeAlso(also);
        settings.setProperty("recordings.dir", next.toString());
        recordings = next;
        saveSettings();
        return null;
    }

    /** New recordings go back to the usual folder. Recordings already saved are left where they are. */
    public void useDefaultRecordingFolder() {
        forgetProbe();
        Path writing = recordingDir();
        Path saved = recordings;
        Path usual = defaultRecordingDir();
        List<Path> also = new ArrayList<>(recordingAlso);
        remember(also, writing, usual);
        remember(also, saved, usual);
        recordingAlso = List.copyOf(also);
        storeAlso(also);
        settings.remove("recordings.dir");
        recordings = null;
        saveSettings();
    }

    static synchronized void forgetProbe() { probeAt = 0; probePath = null; }

    /** Null when new recordings can be saved here. A recent check for the same folder is reused. */
    static synchronized String whyUnusable(Path dir) {
        if (dir == null) return "No folder was chosen.";
        Path n;
        try { n = dir.toAbsolutePath().normalize(); }
        catch (Exception e) { return "That folder can't be used."; }
        long now = System.currentTimeMillis();
        if (n.equals(probePath) && now - probeAt < 3000) return probeWhy;
        String why = probeFolder(n);
        probePath = n;
        probeAt = now;
        probeWhy = why;
        return why;
    }

    static String probeFolder(Path dir) {
        Path probe = null;
        try {
            if (!Files.exists(dir)) return "That folder isn't there.";
            if (!Files.isDirectory(dir)) return "That isn't a folder.";
            probe = dir.resolve(".trunkdeck-write-check");
            Files.writeString(probe, "ok");
            return null;
        } catch (Exception e) {
            return "TrunkDeck can't save files in that folder.";
        } finally {
            if (probe != null) try { Files.deleteIfExists(probe); } catch (IOException ignored) {}
        }
    }

    static boolean samePath(Path a, Path b) {
        if (a == null || b == null) return false;
        try {
            if (Files.exists(a) && Files.exists(b)) return Files.isSameFile(a, b);
        } catch (IOException ignored) {}
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    /** Remember {@code folder} so calls saved there can still be played. {@code replacing} is the folder now in use. */
    static void remember(List<Path> also, Path folder, Path replacing) {
        if (folder == null) return;
        Path kept = folder.toAbsolutePath().normalize();
        Path now = replacing == null ? null : replacing.toAbsolutePath().normalize();
        also.removeIf(p -> samePath(p, kept) || (now != null && samePath(p, now)));
        if (now != null && samePath(kept, now)) return;
        also.add(0, kept);
        while (also.size() > 12) also.remove(also.size() - 1);
    }

    void storeAlso(List<Path> also) {
        if (also.isEmpty()) settings.remove("recordings.also");
        else {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < also.size(); i++) {
                if (i > 0) b.append('\n');
                b.append(also.get(i).toAbsolutePath().normalize());
            }
            settings.setProperty("recordings.also", b.toString());
        }
    }

    static List<Path> parseAlso(String raw) {
        List<Path> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String line : raw.split("\\R")) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            try { out.add(Paths.get(s).toAbsolutePath().normalize()); } catch (Exception ignored) {}
        }
        return out;
    }

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
        if (s != null && s.id != null && s.id.equals(settings.getProperty(AUTO_START_KEY, ""))) {
            settings.remove(AUTO_START_KEY);
            saveSettings();
        }
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

    /** The system marked to start when TrunkDeck opens, or null if none is marked or that system was deleted. */
    public RadioSystem autoStartSystem() {
        String id = settings.getProperty(AUTO_START_KEY, "");
        if (id == null || id.isBlank()) return null;
        return systems.stream().filter(s -> id.equals(s.id)).findFirst().orElse(null);
    }

    public boolean isAutoStart(RadioSystem s) {
        return s != null && s.id != null && !s.id.isBlank() && s.id.equals(settings.getProperty(AUTO_START_KEY, ""));
    }

    /**
     * Mark this system to start when TrunkDeck opens. {@code null} (or a system with no id) clears it.
     * Only one system can be marked: this replaces any earlier choice and is saved at once.
     */
    public void setAutoStart(RadioSystem s) {
        if (s == null || s.id == null || s.id.isBlank()) settings.remove(AUTO_START_KEY);
        else settings.setProperty(AUTO_START_KEY, s.id);
        saveSettings();
    }

    /** The line shown on the Monitor page (and the home-network page) during the launch countdown. */
    public static String autoStartMessage(String name, int seconds) {
        String n = name == null || name.isBlank() ? "the system" : name;
        return "Starting " + n + " in " + seconds + " second" + (seconds == 1 ? "" : "s");
    }
}
