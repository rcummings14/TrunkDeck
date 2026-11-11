package p25monitor;

import java.nio.file.*;

/**
 * Auto-start is saved in settings, only one system can be marked, a missing system is ignored,
 * and deleting the marked system clears it. The countdown wording is checked here; the window
 * part is exercised when you open TrunkDeck.
 */
public final class AutoStartTests {
    static int pass, fail;
    static void check(boolean ok, String what) {
        if (ok) { pass++; System.out.println("  ok   " + what); }
        else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] a) throws Exception {
        Path home = Files.createTempDirectory("autostart-home");
        System.setProperty("user.home", home.toString());
        System.setProperty("java.util.prefs.userRoot", home.resolve(".java-prefs").toString());
        System.setProperty("trunkdeck.profile", home.resolve(".trunkdeck").toString());
        noneByDefault(home);
        markAndReplace(home);
        persist(home);
        deleteClears(home);
        missingId(home);
        message();
        System.out.println("AutoStartTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static App fresh(Path home) throws Exception {
        App app = new App(home);
        app.load();
        return app;
    }

    static RadioSystem named(App app, String name) {
        RadioSystem s = new RadioSystem();
        s.name = name;
        s.controlChannelsMHz.add(851.0125);
        try { app.save(s); } catch (Exception e) { throw new RuntimeException(e); }
        app.systems.add(s);
        return s;
    }

    static void noneByDefault(Path home) throws Exception {
        System.out.println("1. a new profile has no auto-start system");
        App app = fresh(home);
        check(app.autoStartSystem() == null, "no system is marked");
        check(app.settings.getProperty(App.AUTO_START_KEY, "").isBlank(), "autoStartSystem is not set");
        check(App.AUTO_START_SECONDS == 10, "countdown is about 10 seconds");
    }

    static void markAndReplace(Path home) throws Exception {
        System.out.println("2. ticking one system marks it, and ticking another replaces it");
        App app = fresh(home);
        RadioSystem a = named(app, "North Site");
        RadioSystem b = named(app, "East Site");
        app.setAutoStart(a);
        check(app.isAutoStart(a), "North Site is marked");
        check(!app.isAutoStart(b), "East Site is not marked");
        check(app.autoStartSystem() == a, "autoStartSystem is North Site");
        check(a.id.equals(app.settings.getProperty(App.AUTO_START_KEY)), "saved as North Site's id");
        app.setAutoStart(b);
        check(app.isAutoStart(b), "East Site is now marked");
        check(!app.isAutoStart(a), "North Site was unmarked");
        check(app.autoStartSystem() == b, "only East Site is returned");
        app.setAutoStart(null);
        check(app.autoStartSystem() == null, "clearing leaves no marked system");
        check(app.settings.getProperty(App.AUTO_START_KEY, "").isBlank(), "the setting is removed");
    }

    static void persist(Path home) throws Exception {
        System.out.println("3. the choice is still there after a reload");
        App app = fresh(home);
        RadioSystem a = named(app, "Saved Site");
        app.setAutoStart(a);
        String id = a.id;
        App again = fresh(home);
        check(id.equals(again.settings.getProperty(App.AUTO_START_KEY)), "id survived a reload");
        RadioSystem found = again.autoStartSystem();
        check(found != null && id.equals(found.id), "autoStartSystem finds the same system");
    }

    static void deleteClears(Path home) throws Exception {
        System.out.println("4. deleting the marked system clears auto-start");
        App app = fresh(home);
        RadioSystem a = named(app, "Soon Gone");
        app.setAutoStart(a);
        app.delete(a);
        check(app.autoStartSystem() == null, "no system is marked after delete");
        check(app.settings.getProperty(App.AUTO_START_KEY, "").isBlank(), "the setting was cleared");
    }

    static void missingId(Path home) throws Exception {
        System.out.println("5. a saved id that is no longer there is ignored");
        App app = fresh(home);
        app.settings.setProperty(App.AUTO_START_KEY, "does-not-exist");
        app.saveSettings();
        check(app.autoStartSystem() == null, "unknown id returns null so launch will not start anything");
    }

    static void message() {
        System.out.println("6. countdown wording");
        check(App.autoStartMessage("Example County", 10).equals("Starting Example County in 10 seconds"),
                "plural seconds");
        check(App.autoStartMessage("Example County", 1).equals("Starting Example County in 1 second"),
                "singular second");
        check(App.autoStartMessage("", 5).equals("Starting the system in 5 seconds"),
                "blank name uses a fallback");
    }
}
