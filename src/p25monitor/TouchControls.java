package p25monitor;

import java.util.*;

/**
 * The large buttons on Display only and on the home-network page. The main Monitor window does not show them.
 * <p>
 * Mute all silences what you hear (this computer's speaker, a recording playing in TrunkDeck, and Listen on the
 * home-network page). Decoding, the call log, recordings and streams keep going. It is not remembered after a restart.
 * <p>
 * Mute talkgroup uses the same mute as the rest of TrunkDeck, and also marks that talkgroup as muted from this button.
 * The muted list shows only those marked talkgroups. The mark is saved with the profile and dropped if the talkgroup
 * is unmuted anywhere else. Hold follows one talkgroup until it is turned off, and is not remembered after a restart.
 */
public final class TouchControls {
    /** Saved as system id, tab, talkgroup number, one per line. */
    public static final String KEY = "touch.muted";

    /** When true, speaker, in-app playback and the home-network Listen audio are silent. */
    public static volatile boolean silenceAll;

    private boolean muteAll;
    private boolean hold;
    private int holdTg = -1;

    public boolean muteAll() { return muteAll; }

    public void setMuteAll(boolean on) { muteAll = on; silenceAll = on; }

    public boolean hold() { return hold; }

    public int holdTg() { return holdTg; }

    /** Turn hold on for a talkgroup that is on the air, or off if it is already on. */
    public String toggleHold(int activeTg) {
        if (hold) { hold = false; holdTg = -1; return null; }
        if (activeTg <= 0) return "No talkgroup is on the air.";
        hold = true;
        holdTg = activeTg;
        return null;
    }

    /** The speaker was changed somewhere else, so this temporary hold is no longer what is playing. */
    public void releaseHoldIfChanged(boolean speakerHolding, int speakerTg) {
        if (hold && (!speakerHolding || speakerTg != holdTg)) { hold = false; holdTg = -1; }
    }

    /** Mute this talkgroup everywhere, and remember that the touch button did it. */
    public static void markMuted(Talkgroup t) {
        if (t == null) return;
        t.muted = true;
        t.touchMuted = true;
    }

    /**
     * Unmute a talkgroup that was muted from the touch button.
     * @return a plain message when it is not in the touch list
     */
    public static String unmute(RadioSystem sys, int id) {
        Talkgroup t = sys == null ? null : sys.find(id);
        if (t == null || !t.touchMuted || !t.muted) return "That talkgroup is not in the muted list.";
        t.muted = false;
        t.touchMuted = false;
        return null;
    }

    /** Talkgroups the muted-list dropdown may show: muted from the touch button, and still muted. */
    public static List<Talkgroup> listed(RadioSystem sys) {
        List<Talkgroup> out = new ArrayList<>();
        if (sys == null || sys.talkgroups == null) return out;
        Set<Integer> seen = new HashSet<>();
        for (Talkgroup t : sys.talkgroups) {
            if (t != null && t.touchMuted && t.muted && t.decimal > 0 && seen.add(t.decimal)) out.add(t);
        }
        out.sort(Comparator.comparing(Talkgroup::displayName, String.CASE_INSENSITIVE_ORDER).thenComparingInt(t -> t.decimal));
        return out;
    }

    /** Put saved marks back onto talkgroups that are still muted. Others are forgotten. */
    public static void applySaved(App app) {
        if (app == null) return;
        Set<String> saved = decode(app.settings.getProperty(KEY, ""));
        for (RadioSystem s : app.systems) {
            if (s == null || s.id == null || s.id.isBlank() || s.talkgroups == null) continue;
            for (Talkgroup t : s.talkgroups) {
                if (t != null && t.muted && saved.contains(token(s.id, t.decimal))) t.touchMuted = true;
            }
        }
        reconcile(app);
    }

    /** Drop the mark wherever the talkgroup is no longer muted, and save the list if it changed. */
    public static void reconcile(App app) {
        if (app == null) return;
        Set<Talkgroup> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (RadioSystem s : app.systems) {
            if (s == null || s.talkgroups == null) continue;
            for (Talkgroup t : s.talkgroups) {
                if (t != null && seen.add(t) && t.touchMuted && !t.muted) t.touchMuted = false;
            }
        }
        String now = encode(app);
        String was = app.settings.getProperty(KEY, "");
        if (now.equals(was)) return;
        if (now.isEmpty()) app.settings.remove(KEY);
        else app.settings.setProperty(KEY, now);
        app.saveSettings();
    }

    static String encode(App app) {
        List<String> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RadioSystem s : app.systems) {
            if (s == null || s.id == null || s.id.isBlank() || s.talkgroups == null) continue;
            for (Talkgroup t : s.talkgroups) {
                if (t != null && t.touchMuted && t.muted && t.decimal > 0 && seen.add(token(s.id, t.decimal))) rows.add(token(s.id, t.decimal));
            }
        }
        Collections.sort(rows);
        return String.join("\n", rows);
    }

    static Set<String> decode(String raw) {
        Set<String> out = new HashSet<>();
        if (raw == null || raw.isBlank()) return out;
        for (String line : raw.split("\\R")) {
            String s = line.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    static String token(String systemId, int tg) { return systemId + "\t" + tg; }

    /** Speaker gain: mute-all and the ordinary speaker mute both silence this computer. */
    public static float outputGain(boolean speakerMuted, boolean silenceAll, float volume) {
        if (speakerMuted || silenceAll) return 0f;
        return volume;
    }
}
