package p25monitor.rr;

import p25monitor.RadioSystem;
import p25monitor.Talkgroup;
import p25monitor.rr.RrModel.*;
import java.util.*;

/** Turns RadioReference data into a TrunkDeck system, and merges a refresh into an existing one. */
public final class RrImport {
    private RrImport() {}

    /** Everything fetched for one system (what the wizard shows). */
    public record Fetched(TrsDetails details, List<Site> sites, List<TgCat> cats, List<Tg> talkgroups) {
        public String category(int tgCid) { for (TgCat c : cats) if (c.tgCid() == tgCid) return c.name(); return ""; }
        public int catSort(int tgCid) { for (TgCat c : cats) if (c.tgCid() == tgCid) return c.sort(); return Integer.MAX_VALUE; }
        /** Talkgroups once per decimal ID, in RadioReference's category order. */
        public List<Tg> ordered() {
            Map<Integer, Tg> m = new LinkedHashMap<>();
            for (Tg t : talkgroups) if (t.dec() > 0) m.putIfAbsent(t.dec(), t);
            List<Tg> l = new ArrayList<>(m.values());
            l.sort(Comparator.comparingInt((Tg t) -> catSort(t.tgCid())).thenComparingInt(Tg::tgCid).thenComparingInt(Tg::sort).thenComparingInt(Tg::dec));
            return l;
        }
    }

    /** The decimal IDs RadioReference listed at the system's last import. */
    public static Set<Integer> seen(RadioSystem s) {
        Set<Integer> l = new HashSet<>();
        for (String x : (s.rrSeen == null ? "" : s.rrSeen).split(",")) try { if (!x.isBlank()) l.add(Integer.parseInt(x.trim())); } catch (Exception ignored) {}
        return l;
    }

    /** Talkgroups that are new on RadioReference since the last import (not in the system, not listed last time). */
    public static List<Tg> newOnRr(RadioSystem s, Fetched f) {
        Set<Integer> seen = seen(s);
        List<Tg> l = new ArrayList<>();
        for (Tg t : f.ordered()) if (s.find(t.dec()) == null && !seen.contains(t.dec())) l.add(t);
        return l;
    }

    /** The default choice: every talkgroup that is not always encrypted. */
    public static Set<Integer> defaultSelection(List<Tg> tgs) {
        Set<Integer> s = new LinkedHashSet<>();
        for (Tg t : tgs) if (!t.encrypted() && t.dec() > 0) s.add(t.dec());
        return s;
    }

    /** RadioReference mode with the encryption flag in TrunkDeck's convention: ...E = always, ...e = sometimes encrypted. */
    public static String mode(Tg t) {
        String m = t.mode() == null ? "" : t.mode().trim();
        if (t.enc() >= 2 && !m.endsWith("E")) m = (m.endsWith("e") ? m.substring(0, m.length() - 1) : m.isEmpty() ? "D" : m) + "E";
        else if (t.enc() == 1 && !m.endsWith("e") && !m.endsWith("E")) m = (m.isEmpty() ? "D" : m) + "e";
        return m;
    }

    static Talkgroup talkgroup(Tg t, Fetched f) {
        Talkgroup n = new Talkgroup();
        n.decimal = t.dec();
        fill(n, t, f);
        return n;
    }

    static void fill(Talkgroup n, Tg t, Fetched f) {
        n.alphaTag = t.alpha();
        n.description = t.descr();
        n.tag = t.tags().isEmpty() ? "" : t.tags().get(0);
        n.category = f.category(t.tgCid());
        n.mode = mode(t);
    }

    /** Control channels of the chosen sites: each site's primary first, then its alternates; all frequencies if a site marks none. */
    public static List<Double> controlChannels(List<Site> sites) {
        List<Double> l = new ArrayList<>();
        for (Site s : sites) {
            List<Double> cc = s.controlChannels();
            if (cc.isEmpty()) for (SiteFreq f : s.freqs()) cc.add(f.mhz());
            for (double d : cc) if (!l.contains(d)) l.add(d);
        }
        return l;
    }

    /** The site TrunkDeck pre-selects: the only one, else the only one in the chosen county, else none. */
    public static Site obvious(List<Site> sites, int ctid) {
        if (sites.size() == 1) return sites.get(0);
        Site only = null;
        if (ctid > 0) for (Site s : sites) if (s.ctid() == ctid) { if (only != null) return null; only = s; }
        return only;
    }

    /** A new system from the chosen sites and talkgroups, with TrunkDeck's usual defaults. */
    public static RadioSystem build(Fetched f, List<Site> sites, Set<Integer> chosen, String name) {
        RadioSystem s = new RadioSystem();
        s.name = name == null || name.isBlank() ? defaultName(f, sites) : name.trim();
        s.talkgroups.clear();
        applySystem(s, f, sites);
        for (Tg t : f.ordered()) if (chosen.contains(t.dec())) s.talkgroups.add(talkgroup(t, f));
        return s;
    }

    public static String defaultName(Fetched f, List<Site> sites) {
        String n = f.details().name();
        if (sites.size() == 1 && !sites.get(0).descr().isBlank()) n += " - " + sites.get(0).descr();
        return n;
    }

    static void applySystem(RadioSystem s, Fetched f, List<Site> sites) {
        s.controlChannelsMHz = controlChannels(sites);
        StringJoiner names = new StringJoiner(" + ");
        for (Site x : sites) names.add(x.descr().isBlank() ? "Site " + x.label() : x.descr());
        s.site = names.toString();
        Site first = sites.isEmpty() ? null : sites.get(0);
        if (first != null) {
            s.nac = first.nac() == null ? "" : first.nac().trim().toUpperCase(Locale.ROOT);
            s.rfss = first.rfss() > 0 ? String.valueOf(first.rfss()) : "";
            s.siteNumber = first.siteNumber() > 0 ? String.format("%03d", first.siteNumber()) : "";
        }
        if (!f.details().sysids().isEmpty()) {
            s.sysId = f.details().sysids().get(0).sysid().trim().toUpperCase(Locale.ROOT);
            s.wacn = f.details().sysids().get(0).wacn().trim().toUpperCase(Locale.ROOT);
        }
        s.rrSid = f.details().sid();
        StringJoiner ids = new StringJoiner(",");
        for (Site x : sites) ids.add(String.valueOf(x.siteId()));
        s.rrSites = ids.toString();
        s.rrUpdated = java.time.LocalDate.now().toString();
        StringJoiner seen = new StringJoiner(",");
        for (Tg t : f.ordered()) seen.add(String.valueOf(t.dec()));
        s.rrSeen = seen.toString();
        StringBuilder notes = new StringBuilder("From RadioReference: ").append(f.details().name());
        if (!f.details().typeName().isBlank()) notes.append(" (").append(f.details().typeName()).append(")");
        for (Site x : sites) {
            notes.append("\nSite ").append(x.label()).append(x.descr().isBlank() ? "" : " " + x.descr());
            if (!x.county().isBlank()) notes.append(", ").append(x.county());
            if (!x.modulation().isBlank()) notes.append(" - ").append(x.modulation());
        }
        s.notes = notes.toString();
    }

    /** What a refresh changed. */
    public static final class Summary {
        public final List<Talkgroup> added = new ArrayList<>();
        public final List<String> renamed = new ArrayList<>();
        public int detailsUpdated, unchanged, notOnRr, newSkipped;
        public List<Double> ccBefore = List.of(), ccAfter = List.of();
        public boolean ccChanged() { return !ccBefore.equals(ccAfter); }
        public String text() {
            StringBuilder b = new StringBuilder();
            b.append(added.size()).append(" new talkgroup").append(added.size() == 1 ? "" : "s").append(" added");
            if (newSkipped > 0) b.append(" (").append(newSkipped).append(" new one").append(newSkipped == 1 ? "" : "s").append(" not chosen)");
            b.append("\n").append(renamed.size()).append(" renamed");
            for (int i = 0; i < Math.min(8, renamed.size()); i++) b.append("\n    ").append(renamed.get(i));
            if (renamed.size() > 8) b.append("\n    \u2026 and ").append(renamed.size() - 8).append(" more");
            b.append("\n").append(detailsUpdated).append(" with updated description, tag, category or mode");
            b.append("\n").append(unchanged).append(" unchanged");
            if (notOnRr > 0) b.append("\n").append(notOnRr).append(" no longer listed on RadioReference - kept, with your settings");
            if (ccChanged()) b.append("\nControl channels: ").append(join(ccBefore)).append(" \u2192 ").append(join(ccAfter));
            else b.append("\nControl channels unchanged");
            b.append("\nYour favourites, mutes, recording, priorities and stream assignments were kept.");
            return b.toString();
        }
        static String join(List<Double> l) { StringJoiner j = new StringJoiner(", "); for (double d : l) j.add(RadioSystem.fmt(d)); return l.isEmpty() ? "none" : j.toString(); }
    }

    /**
     * Merges a refresh into an existing system. Talkgroups are matched by decimal ID. RadioReference's fields (alpha
     * tag, description, tag, category, mode) are updated (names only if updateNames); the user's own settings
     * (favourite, muted, record, priority, streams) are never touched; nothing is deleted. New talkgroups are added if
     * they are in addNew. Control channels / site IDs come from the chosen sites.
     */
    public static Summary merge(RadioSystem s, Fetched f, List<Site> sites, Set<Integer> addNew, boolean updateNames) {
        Summary sum = new Summary();
        sum.ccBefore = new ArrayList<>(s.controlChannelsMHz);
        Map<Integer, Talkgroup> local = new HashMap<>();
        for (Talkgroup t : s.talkgroups) local.putIfAbsent(t.decimal, t);
        Set<Integer> seen = new HashSet<>(), before = seen(s);
        for (Tg t : f.ordered()) {
            seen.add(t.dec());
            Talkgroup old = local.get(t.dec());
            if (old == null) {
                if (addNew.contains(t.dec())) { Talkgroup n = talkgroup(t, f); s.talkgroups.add(n); sum.added.add(n); }
                else if (!before.contains(t.dec())) sum.newSkipped++;
                continue;
            }
            Talkgroup want = talkgroup(t, f);
            boolean renamed = updateNames && !Objects.equals(nz(old.alphaTag), nz(want.alphaTag));
            boolean other = !Objects.equals(nz(old.description), nz(want.description)) || !Objects.equals(nz(old.tag), nz(want.tag))
                    || !Objects.equals(nz(old.category), nz(want.category)) || !Objects.equals(nz(old.mode), nz(want.mode));
            if (renamed) { sum.renamed.add(t.dec() + ": " + (nz(old.alphaTag).isEmpty() ? "(no name)" : old.alphaTag) + " \u2192 " + want.alphaTag); old.alphaTag = want.alphaTag; }
            if (other) {
                sum.detailsUpdated++;
                old.description = want.description; old.tag = want.tag; old.category = want.category; old.mode = want.mode;
            }
            if (!renamed && !other) sum.unchanged++;
        }
        for (Talkgroup t : s.talkgroups) if (!seen.contains(t.decimal)) sum.notOnRr++;
        if (!sites.isEmpty()) {
            String keepName = s.name, keepNotes = s.notes;
            applySystem(s, f, sites);
            s.name = keepName;
            if (keepNotes != null && !keepNotes.isBlank() && !keepNotes.startsWith("From RadioReference")) s.notes = keepNotes + "\n\n" + s.notes;
        }
        sum.ccAfter = new ArrayList<>(s.controlChannelsMHz);
        return sum;
    }

    static String nz(String s) { return s == null ? "" : s.trim(); }
}
