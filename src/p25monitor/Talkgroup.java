package p25monitor;

/** One talkgroup row. */
public class Talkgroup {
    public int decimal;
    public String alphaTag = "";
    public String mode = "";        // D, DE, E, T, A ... (RadioReference mode)
    public String description = "";
    public String tag = "";         // service tag e.g. Law Dispatch
    public String category = "";    // group / agency
    public boolean favorite;
    public boolean muted;           // not followed, not played
    /** Muted from the Display only or home-network touch button. Cleared if unmuted anywhere else. */
    public boolean touchMuted;
    /** Live text colour as #RRGGBB, or blank to keep the display theme. Shared with the other sites of this system. */
    public String color = "";
    public boolean record;
    public int priority = 100;
    /** Names of streams (Broadcastify Calls / OpenMHz / Feeds / Icecast) this talkgroup is sent to. */
    public java.util.TreeSet<String> streams = new java.util.TreeSet<>();      // priority: 1 = highest .. 100 = lowest (speaker priority)
    // runtime only
    public long lastHeard;
    public int calls;

    /** A copy (the engine works on copies: the UI may edit the originals at any time). */
    public Talkgroup copy() {
        Talkgroup t = new Talkgroup();
        t.decimal = decimal; t.alphaTag = alphaTag; t.mode = mode; t.description = description; t.tag = tag; t.category = category;
        t.favorite = favorite; t.muted = muted; t.touchMuted = touchMuted; t.record = record; t.priority = priority;
        t.color = color;
        t.streams = new java.util.TreeSet<>(streams);
        t.lastHeard = lastHeard; t.calls = calls;
        return t;
    }

    public String hex() { return Integer.toHexString(decimal).toUpperCase(); }

    /** RadioReference modes: D, T, A = clear; DE/TE = always encrypted; De/Te = sometimes encrypted. */
    public boolean isEncrypted() { return mode != null && mode.trim().endsWith("E"); }
    public boolean isSometimesEncrypted() { return mode != null && mode.trim().endsWith("e"); }

    public String displayName() {
        if (alphaTag != null && !alphaTag.isBlank()) return alphaTag;
        if (description != null && !description.isBlank()) return description;
        return "TG " + decimal;
    }
}
