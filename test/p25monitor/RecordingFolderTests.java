package p25monitor;

import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.List;

/**
 * The recordings folder can be chosen, is saved, and is used for the next recording without a restart.
 * A missing or unwritable folder falls back to the usual folder. Older recordings stay put and can still be played.
 */
public final class RecordingFolderTests {
    static int pass, fail;
    static void check(boolean ok, String what) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] a) throws Exception {
        Path home = Files.createTempDirectory("rec-folder-home");
        System.setProperty("user.home", home.toString());
        System.setProperty("java.util.prefs.userRoot", home.resolve(".java-prefs").toString());
        System.clearProperty("trunkdeck.recordings");
        System.setProperty("trunkdeck.profile", home.resolve(".trunkdeck").toString());
        try {
            usualFolder(home);
            chooseAndKeepOld(home);
            rejectBadFolders(home);
            backToUsual(home);
            missingSavedFolder(home);
            systemProperty(home);
        } finally {
            System.clearProperty("trunkdeck.recordings");
        }
        System.out.println("RecordingFolderTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static App fresh(Path home) throws Exception {
        App app = new App(home);
        app.load();
        return app;
    }

    static void usualFolder(Path home) throws Exception {
        System.out.println("1. with no choice, recordings go to the usual folder");
        App app = fresh(home);
        Path usual = App.defaultRecordingDir();
        check(usual.equals(home.resolve("TrunkDeck").resolve("recordings")), "usual folder is home/TrunkDeck/recordings");
        check(App.recordingDir().equals(usual), "recordingDir is the usual folder");
        check(App.recordingFolderNote() == null, "no warning");
        check(app.settings.getProperty("recordings.dir", "").isBlank(), "recordings.dir is not set");
        check(App.recordingDir().resolve("iq").getParent().equals(usual), "I/Q folder is under the usual recordings folder");
    }

    static void chooseAndKeepOld(Path home) throws Exception {
        System.out.println("2. a chosen folder is used immediately, and the old recording stays put");
        App app = fresh(home);
        Path usual = App.defaultRecordingDir();
        Files.createDirectories(usual);
        Path oldFile = usual.resolve("20260102_030405_TrunkDeck_TO_100_FROM_7.mp3");
        byte[] oldBytes = new byte[]{1, 2, 3, 4};
        Files.write(oldFile, oldBytes);
        Path custom = Files.createDirectories(home.resolve("calls here"));
        String err = app.chooseRecordingFolder(custom);
        check(err == null, "folder accepted: " + err);
        check(App.recordingDir().equals(custom.toAbsolutePath().normalize()), "new recordings use the chosen folder without a reload");
        check(custom.toAbsolutePath().normalize().toString().equals(app.settings.getProperty("recordings.dir")), "saved in recordings.dir");
        check(App.recordingFolderNote() == null, "a usable folder has no warning");
        check(!Files.exists(custom.resolve(".trunkdeck-write-check")), "the writability check does not leave a file behind");
        check(Arrays.equals(oldBytes, Files.readAllBytes(oldFile)), "the old recording was not moved or changed");
        check(App.recordingDir().resolve("iq").getParent().equals(App.recordingDir()), "I/Q folder stays under the chosen recordings folder");
        Path found = RecordingFiles.find(App.recordingSearchDirs(), "2026-01-02 03:04:05", 100, 7);
        check(found != null && App.samePath(found, oldFile), "a call in the old folder can still be found");
        check(RecordingFiles.list(App.recordingSearchDirs(), "030405", 2000).stream().anyMatch(p -> App.samePath(p, oldFile)), "the recordings list still shows the old folder");

        Path neu = custom.resolve("20260102_030406_TrunkDeck_TO_100_FROM_8.mp3");
        Files.write(neu, new byte[]{9});
        Path shadowOld = usual.resolve("20260102_030407_TrunkDeck_TO_200.mp3");
        Path shadowNew = custom.resolve("20260102_030407_TrunkDeck_TO_200.mp3");
        Files.write(shadowOld, new byte[]{1});
        Files.write(shadowNew, new byte[]{2});
        Path shadow = RecordingFiles.find(App.recordingSearchDirs(), "2026-01-02 03:04:07", 200, -1);
        check(shadow != null && App.samePath(shadow, shadowNew), "when both folders have the call, the current folder is used");
        Files.setLastModifiedTime(oldFile, FileTime.fromMillis(System.currentTimeMillis() + 120_000));
        Files.setLastModifiedTime(neu, FileTime.fromMillis(System.currentTimeMillis()));
        Path latest = RecordingFiles.latest(App.recordingSearchDirs(), 100);
        check(latest != null && App.samePath(latest, oldFile), "replay last can be a newer file that is still in the old folder");
    }

    static void rejectBadFolders(Path home) throws Exception {
        System.out.println("3. a missing or unusable folder is refused, and the current folder stays");
        App app = fresh(home);
        Path custom = home.resolve("calls here").toAbsolutePath().normalize();
        check(App.recordingDir().equals(custom), "still using the chosen folder");
        Path missing = home.resolve("no-such-folder");
        String miss = app.chooseRecordingFolder(missing);
        check(miss != null && miss.contains("isn't there") && miss.contains(custom.toString()), "missing folder: " + miss);
        check(App.recordingDir().equals(custom), "a missing folder does not change where new recordings go");
        check(custom.toString().equals(app.settings.getProperty("recordings.dir")), "a missing folder is not saved");
        Path notDir = Files.createTempFile(home, "not-a-folder", ".txt");
        String nd = app.chooseRecordingFolder(notDir);
        check(nd != null && nd.contains("isn't a folder"), "a file is not a folder: " + nd);
        check(App.recordingDir().equals(custom), "a file does not change the folder");

        Path blocked = Files.createTempDirectory(home, "blocked");
        if (!blocked.toFile().setWritable(false)) System.out.println("  skip  could not remove write permission");
        else {
            String blockedMsg = app.chooseRecordingFolder(blocked);
            boolean stillWriting = App.whyUnusable(blocked) == null;
            if (blockedMsg == null && stillWriting) System.out.println("  skip  not-writable folder (this user can still write there)");
            else {
                check(blockedMsg != null && blockedMsg.toLowerCase().contains("can't save"), "not writable: " + blockedMsg);
                check(App.recordingDir().equals(custom), "an unwritable folder is not used");
            }
            blocked.toFile().setWritable(true);
            if (App.recordingDir().equals(blocked.toAbsolutePath().normalize())) app.chooseRecordingFolder(custom);
        }
        check(app.chooseRecordingFolder(null) != null, "no folder chosen");
        check(App.recordingDir().equals(custom), "cancelling a choice leaves the folder as it was");
    }

    static void backToUsual(Path home) throws Exception {
        System.out.println("4. Use default returns to the usual folder and still plays the previous one");
        App app = fresh(home);
        Path usual = App.defaultRecordingDir();
        Path custom = home.resolve("calls here").toAbsolutePath().normalize();
        Path neu = custom.resolve("20260102_030406_TrunkDeck_TO_100_FROM_8.mp3");
        Path oldFile = usual.resolve("20260102_030405_TrunkDeck_TO_100_FROM_7.mp3");
        app.useDefaultRecordingFolder();
        check(App.recordingDir().equals(usual), "use default");
        check(app.settings.getProperty("recordings.dir", "").isBlank(), "recordings.dir cleared");
        check(Files.exists(neu) && Files.exists(oldFile), "nothing was deleted");
        check(RecordingFiles.find(App.recordingSearchDirs(), "2026-01-02 03:04:06", 100, 8) != null, "a recording in the previous folder is still found");
        String also = app.settings.getProperty("recordings.also", "");
        check(also.contains(custom.toString()), "the previous folder is remembered");
        App again = fresh(home);
        check(App.recordingDir().equals(usual), "after a reload the usual folder is still in use");
        check(RecordingFiles.find(App.recordingSearchDirs(), "2026-01-02 03:04:06", 100, 8) != null, "after a reload the previous folder is still searched");
        check(again.settings.getProperty("recordings.also", "").contains(custom.toString()), "the remembered folder was saved");
    }

    static void missingSavedFolder(Path home) throws Exception {
        System.out.println("5. a saved folder that is missing or not a folder falls back to the usual one");
        App app = fresh(home);
        Path usual = App.defaultRecordingDir();
        Path gone = home.resolve("unplugged");
        app.settings.setProperty("recordings.dir", gone.toString());
        app.saveSettings();
        fresh(home);
        check(App.recordingDir().equals(usual), "a missing saved folder falls back to the usual folder");
        String note = App.recordingFolderNote();
        check(note != null && note.contains("isn't there") && note.contains(usual.toString()) && note.contains(gone.toString()), "plain message: " + note);
        check(RecordingFiles.find(App.recordingSearchDirs(), "2026-01-02 03:04:06", 100, 8) != null, "older recordings can still be played while falling back");
        Path notDir = Files.createTempFile(home, "saved-file", ".txt");
        app = fresh(home);
        app.settings.setProperty("recordings.dir", notDir.toString());
        app.saveSettings();
        fresh(home);
        check(App.recordingDir().equals(usual), "a saved file path falls back to the usual folder");
        check(App.recordingFolderNote() != null && App.recordingFolderNote().contains("isn't a folder"), "plain message: " + App.recordingFolderNote());
        String cleared = app.chooseRecordingFolder(usual);
        check(cleared == null && app.settings.getProperty("recordings.dir", "").isBlank(), "choosing the usual folder clears the bad setting");
        check(App.recordingFolderNote() == null, "the warning goes away");
        check(App.recordingDir().equals(usual), "new recordings use the usual folder");
    }

    static void systemProperty(Path home) throws Exception {
        System.out.println("6. -Dtrunkdeck.recordings still overrides the saved folder");
        Path prop = Files.createTempDirectory(home, "prop");
        System.setProperty("trunkdeck.recordings", prop.toString());
        check(App.recordingDir().equals(prop), "the system property is the folder new recordings use");
        List<Path> only = App.recordingSearchDirs();
        check(only.size() == 1 && only.get(0).equals(prop), "only that folder is searched");
        check(App.recordingFolderNote() == null, "the test override is not reported as a fallback");
        Path iq = App.recordingDir().resolve("iq");
        check(iq.getParent().equals(prop), "I/Q would be written under the override folder");
    }
}
