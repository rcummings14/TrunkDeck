package p25monitor;

import java.util.*;

/**
 * Sites of one radio system share one talkgroup list. Two saved systems are the same radio system when their P25
 * identity matches (WACN and System ID, both present, compared as hex so a leading zero does not matter) or when both
 * were imported from the same RadioReference system ({@code rrSid} above 0). The display name is never enough.
 * A site with no WACN, no System ID and no RadioReference id stays on its own list, even if another site is just as
 * blank. One shared list is the whole connected set: a site tied by WACN/System ID and a site tied by the same
 * RadioReference id are one system.
 */
public final class TalkgroupLists {
    private TalkgroupLists() {}

    /** "WACN.SYSID" with hex normalized, or "" when either value is missing or not hex. */
    public static String p25Key(RadioSystem s) {
        if (s == null) return "";
        String w = hex(s.wacn), id = hex(s.sysId);
        if (w.isEmpty() || id.isEmpty()) return "";
        return w + "." + id;
    }

    /** Hex digits only, leading zeros removed, upper case. Optional 0x prefix. "" if it is not hex. */
    static String hex(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.length() >= 2 && t.regionMatches(true, 0, "0x", 0, 2)) t = t.substring(2).trim();
        if (t.isEmpty() || !t.matches("(?i)[0-9a-f]+")) return "";
        try { return Long.toHexString(Long.parseLong(t, 16)).toUpperCase(Locale.ROOT); }
        catch (NumberFormatException e) { return ""; }
    }

    /**
     * True when the two sites belong to one trunked radio system. A site is never "the same" as itself.
     * A conventional system keeps its own talkgroup list, even when a WACN, System ID or RadioReference id matches.
     */
    public static boolean sameSystem(RadioSystem a, RadioSystem b) {
        if (a == null || b == null || a == b) return false;
        if (a.conventional() || b.conventional()) return false;
        String pa = p25Key(a), pb = p25Key(b);
        if (!pa.isEmpty() && pa.equals(pb)) return true;
        return a.rrSid > 0 && a.rrSid == b.rrSid;
    }

    /** Sites currently using {@code sys}'s talkgroup list, including {@code sys}. Empty when {@code sys} is null. */
    public static List<RadioSystem> sharing(List<RadioSystem> systems, RadioSystem sys) {
        List<RadioSystem> l = new ArrayList<>();
        if (sys == null || systems == null) return l;
        for (RadioSystem o : systems) if (o != null && o.talkgroups == sys.talkgroups) l.add(o);
        return l;
    }

    /**
     * Plain sentence for the Talkgroups page, or "" when this site keeps its own list.
     * Names the other sites (the site name when it has one) so it is obvious which towers follow this list.
     */
    public static String shareNote(List<RadioSystem> systems, RadioSystem sys) {
        List<String> names = new ArrayList<>();
        for (RadioSystem o : sharing(systems, sys)) {
            if (o == sys) continue;
            String n = o.site != null && !o.site.isBlank() ? o.site.trim() : (o.name == null ? "" : o.name.trim());
            if (n.isEmpty()) n = "another site";
            names.add(n);
        }
        if (names.isEmpty()) return "";
        String who;
        if (names.size() == 1) who = names.get(0);
        else if (names.size() == 2) who = names.get(0) + " and " + names.get(1);
        else who = names.get(0) + ", " + names.get(1) + ", and " + (names.size() - 2) + " more";
        return "This list is shared with the other sites on this system (" + who + "). "
                + "A name, favorite, mute, record, priority, or stream change here updates all of them.";
    }

    /**
     * Point every site of a radio system at one list. Lists that already match are left as they are (so a save does
     * not rebuild them). Separate lists for one system are merged. A site that no longer belongs gets its own copy,
     * so clearing its WACN does not wipe or keep editing the others.
     */
    public static void bind(List<RadioSystem> systems) {
        if (systems == null || systems.size() < 2) return;
        for (RadioSystem s : systems) if (s != null && s.talkgroups == null) s.talkgroups = new ArrayList<>();
        List<List<RadioSystem>> multi = new ArrayList<>();
        for (List<RadioSystem> g : groups(systems)) if (g.size() > 1) multi.add(g);
        Set<List<RadioSystem>> done = Collections.newSetFromMap(new IdentityHashMap<>());
        while (true) {
            List<RadioSystem> best = null;
            int bestScore = Integer.MAX_VALUE;
            for (List<RadioSystem> g : multi) {
                if (done.contains(g) || exclusive(systems, g)) continue;
                int score = 0;
                for (RadioSystem s : g) if (usedOutside(systems, g, s.talkgroups)) score++;
                if (score < bestScore) { bestScore = score; best = g; }
            }
            if (best == null) break;
            mergeGroup(systems, best);
            done.add(best);
        }
        detachStrays(systems);
    }

    /**
     * Union {@code incoming} into {@code dest} by talkgroup number. Same rules as Import CSV → Merge and as a
     * RadioReference refresh of names: a non-blank name, mode, description, tag or category is kept (and updates a
     * name that is already set); a blank never erases one. Favorite, mute and record stay on if either copy has them.
     * A priority other than 100 replaces the stored one, so a default 100 does not wipe a priority the user set.
     * Stream assignments are combined. New rows are copies, so two lists never share a row by accident.
     *
     * @return {@code {added, updated}}
     */
    public static int[] mergeInto(List<Talkgroup> dest, Collection<Talkgroup> incoming) {
        Map<Integer, Talkgroup> by = new HashMap<>();
        for (Talkgroup t : dest) if (t != null && t.decimal > 0) by.putIfAbsent(t.decimal, t);
        int added = 0, updated = 0;
        if (incoming == null) return new int[]{0, 0};
        for (Talkgroup n : incoming) {
            if (n == null || n.decimal <= 0) continue;
            Talkgroup old = by.get(n.decimal);
            if (old == null) {
                Talkgroup c = n.copy();
                dest.add(c);
                by.put(c.decimal, c);
                added++;
            } else if (old != n) {
                if (n.alphaTag != null && !n.alphaTag.isBlank()) old.alphaTag = n.alphaTag;
                if (n.mode != null && !n.mode.isBlank()) old.mode = n.mode;
                if (n.description != null && !n.description.isBlank()) old.description = n.description;
                if (n.tag != null && !n.tag.isBlank()) old.tag = n.tag;
                if (n.category != null && !n.category.isBlank()) old.category = n.category;
                old.favorite |= n.favorite;
                old.muted |= n.muted;
                old.record |= n.record;
                if (n.priority != 100) old.priority = n.priority;
                if (n.color != null && !n.color.isBlank()) old.color = TalkgroupColors.store(n.color);
                if (n.streams != null) old.streams.addAll(n.streams);
                if (n.lastHeard > old.lastHeard) old.lastHeard = n.lastHeard;
                if (n.calls > old.calls) old.calls = n.calls;
                updated++;
            }
        }
        return new int[]{added, updated};
    }

    // ------------------------------------------------------------------ grouping

    static List<List<RadioSystem>> groups(List<RadioSystem> systems) {
        int n = systems.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            if (systems.get(i) == null) continue;
            for (int j = i + 1; j < n; j++) if (sameSystem(systems.get(i), systems.get(j))) union(parent, i, j);
        }
        Map<Integer, List<RadioSystem>> m = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) if (systems.get(i) != null) m.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(systems.get(i));
        return new ArrayList<>(m.values());
    }

    static int find(int[] p, int i) { return p[i] == i ? i : (p[i] = find(p, p[i])); }
    static void union(int[] p, int a, int b) { a = find(p, a); b = find(p, b); if (a != b) p[b] = a; }

    static boolean usedOutside(List<RadioSystem> systems, List<RadioSystem> group, List<Talkgroup> list) {
        for (RadioSystem s : systems) if (s != null && s.talkgroups == list && !group.contains(s)) return true;
        return false;
    }

    /** Every member uses one list, and no site outside the group uses it. */
    static boolean exclusive(List<RadioSystem> systems, List<RadioSystem> group) {
        if (group.isEmpty()) return true;
        List<Talkgroup> list = group.get(0).talkgroups;
        for (RadioSystem s : group) if (s.talkgroups != list) return false;
        return !usedOutside(systems, group, list);
    }

    /** Fold every member's rows into one list and point the group at it. Prefers a list nobody else is using. */
    static void mergeGroup(List<RadioSystem> systems, List<RadioSystem> group) {
        List<Talkgroup> dest = null;
        for (RadioSystem s : group) if (!usedOutside(systems, group, s.talkgroups)) { dest = s.talkgroups; break; }
        if (dest == null) dest = new ArrayList<>();
        List<RadioSystem> ordered = new ArrayList<>(group);
        ordered.sort(Comparator.comparing(s -> s.id == null ? "" : s.id));
        for (RadioSystem s : ordered) if (s.talkgroups != dest) mergeInto(dest, s.talkgroups);
        for (RadioSystem s : group) s.talkgroups = dest;
    }

    /**
     * A list still held by sites that are not one radio system (the WACN was cleared, or two blank sites never
     * matched). The first cluster keeps the list; each other cluster gets a copy with the same rows.
     */
    static void detachStrays(List<RadioSystem> systems) {
        Map<List<Talkgroup>, List<RadioSystem>> by = new IdentityHashMap<>();
        for (RadioSystem s : systems) if (s != null && s.talkgroups != null) by.computeIfAbsent(s.talkgroups, k -> new ArrayList<>()).add(s);
        for (List<RadioSystem> holders : by.values()) {
            if (holders.size() < 2) continue;
            List<List<RadioSystem>> clusters = groups(holders);
            if (clusters.size() <= 1) continue;
            for (int i = 1; i < clusters.size(); i++) {
                List<Talkgroup> own = new ArrayList<>();
                for (Talkgroup t : holders.get(0).talkgroups) own.add(t.copy());
                for (RadioSystem s : clusters.get(i)) s.talkgroups = own;
            }
        }
    }
}
