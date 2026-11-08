package p25monitor;

import java.nio.file.*;
import java.util.*;

/**
 * Sites of one radio system share a talkgroup list. Fictional systems only: Sample Metro, towers, and made-up IDs.
 * Never touches the real profile folder.
 */
public final class TalkgroupShareTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("  FAIL " + what); } }

    public static void main(String[] args) throws Exception {
        identity();
        mergeRules();
        memory();
        disk();
        check(HelpPage.guideHtml().contains("share one talkgroup list"), "in-app help explains the shared list");
        check(Jargon.tip("WACN").contains("share one talkgroup list") && Jargon.tip("Sys ID").contains("share one talkgroup list"), "WACN and System ID tips mention the shared list");
        System.out.println("TalkgroupShareTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static RadioSystem site(String name, String siteName, String wacn, String sysId, int rr) {
        RadioSystem s = new RadioSystem();
        s.name = name; s.site = siteName; s.wacn = wacn; s.sysId = sysId; s.rrSid = rr;
        return s;
    }

    static Talkgroup row(int dec, String name) {
        Talkgroup t = new Talkgroup();
        t.decimal = dec; t.alphaTag = name;
        return t;
    }

    static void identity() {
        System.out.println("1. Which sites are the same radio system");
        RadioSystem a = site("Sample Metro", "Tower North", "B1001", "2A1", 0);
        RadioSystem b = site("Sample Metro", "Tower East", "0x0b1001", "02a1", 0);
        RadioSystem named = site("Sample Metro", "Tower East", "", "", 0);
        RadioSystem other = site("Other Town", "Tower 1", "C2002", "19B", 0);
        RadioSystem blank1 = site("Loose One", "Site A", "", "", 0);
        RadioSystem blank2 = site("Loose Two", "Site B", "  ", "", 0);
        RadioSystem wacnOnly = site("Partial", "Site", "B1001", "", 0);
        RadioSystem idOnly = site("Partial", "Site", "", "2A1", 0);
        RadioSystem idOnly2 = site("Partial 2", "Site", "", "02A1", 0);
        RadioSystem bad = site("Bad", "Site", "Tower", "Tower", 0);
        RadioSystem bad2 = site("Bad 2", "Site", "Tower", "Tower", 0);
        RadioSystem rr = site("Catalog North", "North", "", "", 424242);
        RadioSystem rr2 = site("Catalog East", "East", "D3003", "44C", 424242);
        RadioSystem rrZero = site("Zero", "Site", "", "", 0);
        RadioSystem rrZero2 = site("Zero 2", "Site", "B1001", "2A1", 0);
        check(TalkgroupLists.sameSystem(a, b), "WACN and System ID match with leading zeros, 0x and letter case (B1001/2A1)");
        check(TalkgroupLists.p25Key(a).equals("B1001.2A1") && TalkgroupLists.p25Key(b).equals(TalkgroupLists.p25Key(a)), "hex key normalizes to B1001.2A1");
        check(!TalkgroupLists.sameSystem(a, named), "the same display name is not enough");
        check(!TalkgroupLists.sameSystem(a, other), "different WACN and System ID stay apart");
        check(!TalkgroupLists.sameSystem(blank1, blank2), "two sites with no ids do not match");
        check(!TalkgroupLists.sameSystem(a, blank1), "a blank site does not match a site that has an id");
        check(!TalkgroupLists.sameSystem(a, wacnOnly) && !TalkgroupLists.sameSystem(wacnOnly, idOnly), "WACN alone or System ID alone does not match");
        check(!TalkgroupLists.sameSystem(idOnly, idOnly2), "the same System ID with no WACN does not match, even with leading zeros");
        check(!TalkgroupLists.sameSystem(bad, bad2), "text that is not hex does not match");
        check(TalkgroupLists.sameSystem(rr, rr2), "the same RadioReference system id matches, even when one site has no WACN");
        check(!TalkgroupLists.sameSystem(rrZero, rrZero2) && !TalkgroupLists.sameSystem(rr, site("x", "y", "", "", 0)), "RadioReference id 0 is not an id");
        check(TalkgroupLists.sameSystem(a, rrZero2), "a site with only the P25 id still matches the site that has that WACN and System ID");
        check(!TalkgroupLists.sameSystem(a, a), "a site is not paired with itself");
        RadioSystem conv = site("Town Fire", "Repeater", "B1001", "2A1", 424242);
        conv.kind = RadioSystem.CONVENTIONAL;
        check(!TalkgroupLists.sameSystem(a, conv) && !TalkgroupLists.sameSystem(rr, conv), "a conventional system keeps its own talkgroup list");
    }

    static void mergeRules() {
        System.out.println("2. Merging rows keeps flags and prefers a non-blank name");
        Talkgroup left = row(1100, "Town Hall");
        left.favorite = true; left.record = true; left.priority = 20; left.mode = "D"; left.category = "City";
        left.streams.add("Sample Feed");
        Talkgroup right = row(1100, "");
        right.muted = true; right.priority = 100; right.description = "Front counter"; right.tag = "Desk";
        right.streams.add("Tower Feed");
        List<Talkgroup> dest = new ArrayList<>(); dest.add(left);
        int[] n = TalkgroupLists.mergeInto(dest, List.of(right));
        Talkgroup m = dest.get(0);
        check(n[0] == 0 && n[1] == 1 && dest.size() == 1, "one row updated, not duplicated");
        check(m.favorite && m.muted && m.record, "favorite, mute and record from either copy stay on");
        check(m.priority == 20, "priority 100 does not replace a priority that was set");
        check(m.alphaTag.equals("Town Hall") && m.description.equals("Front counter") && m.tag.equals("Desk") && m.category.equals("City") && m.mode.equals("D"),
                "a blank name does not erase Town Hall; description and tag fill in");
        check(m.streams.contains("Sample Feed") && m.streams.contains("Tower Feed"), "stream assignments from both copies");

        Talkgroup bare = row(1100, "");
        bare.priority = 100;
        Talkgroup named = row(1100, "Town Hall");
        named.priority = 20; named.favorite = true;
        List<Talkgroup> flipped = new ArrayList<>(); flipped.add(bare);
        TalkgroupLists.mergeInto(flipped, List.of(named));
        check(flipped.get(0).alphaTag.equals("Town Hall") && flipped.get(0).priority == 20 && flipped.get(0).favorite, "the other order still keeps the name, priority and favorite");

        Talkgroup extra = row(1200, "Works");
        extra.record = true;
        TalkgroupLists.mergeInto(dest, List.of(extra));
        check(dest.size() == 2 && dest.get(1).decimal == 1200 && dest.get(1).record && dest.get(1) != extra, "a new talkgroup is added as a copy");
    }

    static void memory() {
        System.out.println("3. In memory: one list, an edit shows on the other site, strangers stay apart");
        RadioSystem north = site("Sample Metro", "Tower North", "B1001", "2A1", 0);
        RadioSystem east = site("Sample Metro", "Tower East", "B1001", "02A1", 0);
        north.talkgroups.add(row(1100, "Town Hall"));
        east.talkgroups.add(row(1100, "Town Hall"));
        RadioSystem other = site("Sample Metro", "Other Tower", "C2002", "19B", 0);
        other.talkgroups.add(row(1100, "Not the same"));
        RadioSystem blankA = site("Loose One", "Site A", "", "", 0);
        RadioSystem blankB = site("Loose Two", "Site B", "", "", 0);
        blankA.talkgroups.add(row(1100, "Blank A")); blankA.talkgroups.get(0).favorite = true;
        blankB.talkgroups.add(row(1100, "Blank B")); blankB.talkgroups.get(0).muted = true;
        RadioSystem idA = site("Id only", "A", "", "2A1", 0);
        RadioSystem idB = site("Id only", "B", "", "02A1", 0);
        idA.talkgroups.add(row(1100, "Id only A"));
        idB.talkgroups.add(row(1100, "Id only B"));
        List<RadioSystem> systems = new ArrayList<>(List.of(north, east, other, blankA, blankB, idA, idB));
        TalkgroupLists.bind(systems);
        check(north.talkgroups == east.talkgroups, "same WACN and System ID share one list object");
        north.find(1100).favorite = true;
        north.find(1100).alphaTag = "Town Hall Desk";
        north.find(1100).streams.add("Sample Feed");
        check(east.find(1100).favorite && east.find(1100).alphaTag.equals("Town Hall Desk") && east.find(1100).streams.contains("Sample Feed"),
                "an edit on Tower North is visible on Tower East");
        check(east.find(1100) == north.find(1100), "both sites use the same row");
        int size = north.talkgroups.size();
        List<Talkgroup> list = north.talkgroups;
        check(north.talkgroups == list && east.talkgroups == list && east.talkgroups.size() == size, "reading the other site does not duplicate or wipe the list");
        check(other.talkgroups != list && other.find(1100).alphaTag.equals("Not the same") && !other.find(1100).favorite, "a different system keeps its own list and its own name");
        check(blankA.talkgroups != blankB.talkgroups && blankA.find(1100).favorite && !blankA.find(1100).muted
                && blankB.find(1100).muted && !blankB.find(1100).favorite && !blankA.find(1100).alphaTag.equals(blankB.find(1100).alphaTag),
                "blank ids do not make the two loose sites share");
        check(idA.talkgroups != idB.talkgroups && idA.talkgroups != list && idA.find(1100).alphaTag.equals("Id only A") && idB.find(1100).alphaTag.equals("Id only B"),
                "System ID with no WACN does not join those sites to each other or to Sample Metro");
        String note = TalkgroupLists.shareNote(systems, north);
        check(note.startsWith("This list is shared with the other sites on this system") && note.contains("Tower East")
                && TalkgroupLists.shareNote(systems, other).isEmpty() && TalkgroupLists.shareNote(systems, blankA).isEmpty(),
                "the Talkgroups note names the other site, and stays quiet for a site on its own list");

        System.out.println("4. RadioReference id joins a site that has no WACN; clearing the id splits a copy");
        RadioSystem p25 = site("Sample Metro", "Tower North", "B1001", "2A1", 0);
        p25.id = "p25";
        Talkgroup keep = row(1501, "From P25"); keep.favorite = true; p25.talkgroups.add(keep);
        RadioSystem mid = site("Sample Metro", "Tower East", "B1001", "2A1", 5150);
        mid.id = "mid";
        Talkgroup midRow = row(1501, ""); midRow.muted = true; mid.talkgroups.add(midRow);
        RadioSystem catalog = site("Catalog", "Far Site", "", "", 5150);
        catalog.id = "catalog";
        Talkgroup cat = row(1502, "Catalog only"); cat.record = true; catalog.talkgroups.add(cat);
        RadioSystem stranger = site("Loose", "Site", "", "", 0);
        stranger.talkgroups.add(row(1501, "Alone"));
        List<RadioSystem> chain = new ArrayList<>(List.of(p25, mid, catalog, stranger));
        TalkgroupLists.bind(chain);
        check(p25.talkgroups == mid.talkgroups && mid.talkgroups == catalog.talkgroups, "P25 identity and the same RadioReference id are one list");
        check(p25.find(1501).favorite && p25.find(1501).muted && p25.find(1502).record && p25.find(1502).alphaTag.equals("Catalog only"),
                "flags and the catalog name survived the join");
        check(stranger.talkgroups != p25.talkgroups && stranger.find(1501).alphaTag.equals("Alone") && !stranger.find(1501).favorite, "a site with no id stays independent");
        catalog.wacn = ""; catalog.sysId = ""; catalog.rrSid = 0;
        TalkgroupLists.bind(chain);
        check(catalog.talkgroups != p25.talkgroups && p25.talkgroups == mid.talkgroups, "clearing the ids gives that site its own copy and leaves the others shared");
        check(catalog.find(1502) != null && catalog.find(1501) != null && p25.find(1502) != null, "the split keeps every talkgroup on both sides");
        catalog.find(1501).alphaTag = "Only this site";
        check(p25.find(1501).alphaTag.equals("From P25"), "an edit after the split stays on that site");
    }

    static void disk() throws Exception {
        System.out.println("5. Existing per-site files merge on load, a save does not drift, a new site adopts the list");
        Path home = Files.createTempDirectory("tg-share");
        Path profile = home.resolve("profile");
        Files.createDirectories(profile.resolve("systems"));
        Files.writeString(profile.resolve("settings.properties"), "presetsInstalled=true\n");
        System.setProperty("trunkdeck.profile", profile.toString());

        RadioSystem north = site("Sample Metro North", "Tower North", "B1001", "2A1", 0);
        north.id = "sample-north";
        Talkgroup n1100 = row(1100, "Town Hall");
        n1100.favorite = true; n1100.record = true; n1100.priority = 20; n1100.mode = "D"; n1100.category = "City";
        n1100.streams.add("Sample Feed");
        north.talkgroups.add(n1100);
        north.radios.setManual(42, "Tower Radio");

        RadioSystem east = site("Sample Metro East", "Tower East", "b1001", "02A1", 0);
        east.id = "sample-east";
        Talkgroup e1100 = row(1100, "");
        e1100.muted = true; e1100.description = "Front counter"; e1100.tag = "Desk"; e1100.streams.add("Tower Feed");
        east.talkgroups.add(e1100);
        Talkgroup e1200 = row(1200, "Works"); e1200.record = true; east.talkgroups.add(e1200);

        RadioSystem gap = site("Sample Metro Gap", "Tower Gap", "B1001", "2A1", 0);
        gap.id = "sample-gap";

        RadioSystem sameName = site("Sample Metro North", "Other Tower", "C2002", "19B", 0);
        sameName.id = "other-town";
        Talkgroup o = row(1100, "Not the same"); sameName.talkgroups.add(o);

        RadioSystem blankA = site("Loose One", "Site A", "", "", 0); blankA.id = "blank-a";
        Talkgroup ba = row(1100, "Blank A"); ba.favorite = true; blankA.talkgroups.add(ba);
        RadioSystem blankB = site("Loose Two", "Site B", "", "", 0); blankB.id = "blank-b";
        Talkgroup bb = row(1100, "Blank B"); bb.muted = true; blankB.talkgroups.add(bb);

        RadioSystem partial = site("Partial Site", "Partial", "B1001", "", 0); partial.id = "partial";
        partial.talkgroups.add(row(1100, "Partial only"));
        RadioSystem idOnly = site("Id only", "Id", "", "2A1", 0); idOnly.id = "id-only";
        idOnly.talkgroups.add(row(1100, "Id only"));

        RadioSystem catalog = site("Catalog Site", "Catalog", "", "", 424242); catalog.id = "catalog";
        Talkgroup c1400 = row(1400, "From the catalog"); c1400.record = true; catalog.talkgroups.add(c1400);
        RadioSystem far = site("Far Tower", "Far Tower", "D3003", "44C", 424242); far.id = "far";
        Talkgroup f1400 = row(1400, ""); f1400.muted = true; f1400.priority = 8; f1400.description = "Night watch"; far.talkgroups.add(f1400);

        Path systems = profile.resolve("systems");
        for (RadioSystem s : List.of(north, east, gap, sameName, blankA, blankB, partial, idOnly, catalog, far)) s.save(systems.resolve(s.id));
        Files.delete(systems.resolve("sample-gap").resolve("talkgroups.csv"));   // a site folder with no list yet

        App app = new App(home);
        app.load();
        RadioSystem n = byId(app, "sample-north"), e = byId(app, "sample-east"), g = byId(app, "sample-gap");
        check(n.talkgroups == e.talkgroups && e.talkgroups == g.talkgroups, "load: the three Sample Metro sites share one list");
        Talkgroup t = n.find(1100);
        check(t != null && t.favorite && t.muted && t.record && t.priority == 20, "load merge keeps favorite, mute, record and priority 20");
        check("Town Hall".equals(t.alphaTag) && "Front counter".equals(t.description) && "Desk".equals(t.tag) && "City".equals(t.category) && "D".equals(t.mode),
                "load merge keeps Town Hall and fills description, tag and category");
        check(t.streams.contains("Sample Feed") && t.streams.contains("Tower Feed"), "load merge keeps both stream assignments");
        check(n.find(1200) != null && n.find(1200).record && "Works".equals(n.find(1200).alphaTag) && n.talkgroups.size() == 2, "the talkgroup that existed on only one site is kept, once");
        String northCsv = Files.readString(systems.resolve("sample-north/talkgroups.csv"));
        String eastCsv = Files.readString(systems.resolve("sample-east/talkgroups.csv"));
        String gapCsv = Files.readString(systems.resolve("sample-gap/talkgroups.csv"));
        check(northCsv.equals(eastCsv) && eastCsv.equals(gapCsv), "the merged list is written to every Sample Metro site, including the one that had no file");

        RadioSystem other = byId(app, "other-town"), b1 = byId(app, "blank-a"), b2 = byId(app, "blank-b");
        RadioSystem part = byId(app, "partial"), only = byId(app, "id-only"), cat = byId(app, "catalog"), tower = byId(app, "far");
        check(other.talkgroups != n.talkgroups && "Not the same".equals(other.find(1100).alphaTag) && !other.find(1100).favorite, "same display name, different system: own list");
        check(b1.talkgroups != b2.talkgroups && b1.talkgroups != n.talkgroups && b1.find(1100).favorite && !b1.find(1100).muted
                && b2.find(1100).muted && "Blank B".equals(b2.find(1100).alphaTag), "blank sites stay independent of each other and of Sample Metro");
        check(part.talkgroups != n.talkgroups && "Partial only".equals(part.find(1100).alphaTag), "WACN with no System ID does not join Sample Metro");
        check(only.talkgroups != n.talkgroups && only.talkgroups != part.talkgroups && "Id only".equals(only.find(1100).alphaTag), "System ID with no WACN does not join anyone");
        check(cat.talkgroups == tower.talkgroups && cat.find(1400).record && cat.find(1400).muted && cat.find(1400).priority == 8
                && "From the catalog".equals(cat.find(1400).alphaTag) && "Night watch".equals(cat.find(1400).description),
                "same RadioReference system: one list, flags and both texts kept");
        check(n.radios.alias(42).equals("Tower Radio") && e.radios.isEmpty() && other.radios.isEmpty(), "radio names stay on the site they were saved on");

        t.favorite = false;
        t.alphaTag = "Town Hall Desk";
        t.priority = 4;
        app.current = n;
        app.save(n);   // one site saved; the others share the list so their files must follow
        check(Files.readString(systems.resolve("sample-east/talkgroups.csv")).contains("Town Hall Desk"), "saving Tower North updates Tower East's file");
        check(Files.readString(systems.resolve("sample-gap/talkgroups.csv")).contains("Town Hall Desk"), "saving Tower North updates Tower Gap's file");
        check(!b1.find(1100).alphaTag.equals("Town Hall Desk"), "the save does not rewrite an unrelated site");

        App again = new App(home);
        again.load();
        RadioSystem n2 = byId(again, "sample-north"), e2 = byId(again, "sample-east"), g2 = byId(again, "sample-gap");
        check(n2.talkgroups == e2.talkgroups && e2.talkgroups == g2.talkgroups, "after save and reload the three sites still share one list");
        check(n2.talkgroups.size() == 2 && !n2.find(1100).favorite && n2.find(1100).muted && n2.find(1100).record
                && n2.find(1100).priority == 4 && "Town Hall Desk".equals(n2.find(1100).alphaTag), "reloaded flags and the new name match");
        int shown = n2.talkgroups.size();
        List<Talkgroup> shared = n2.talkgroups;
        again.current = e2;
        again.current = g2;
        again.current = n2;
        check(n2.talkgroups == shared && e2.talkgroups == shared && n2.talkgroups.size() == shown, "switching the selected site after reload does not duplicate or wipe the list");

        RadioSystem west = site("Sample Metro West", "Tower West", "0xB1001", "2A1", 0);
        west.id = "sample-west";
        Talkgroup renamed = row(1100, "Town Hall Desk 2");
        renamed.description = "Side door";
        west.talkgroups.add(renamed);
        Talkgroup parks = row(1300, "Parks"); parks.record = true; parks.streams.add("Sample Feed"); west.talkgroups.add(parks);
        again.systems.add(west);
        again.bindTalkgroups();
        check(west.talkgroups == n2.talkgroups, "a new site of Sample Metro picks up the existing list");
        check(n2.find(1100).muted && n2.find(1100).record && !n2.find(1100).favorite && n2.find(1100).priority == 4, "adopting the list keeps mute, record and priority");
        check("Town Hall Desk 2".equals(n2.find(1100).alphaTag) && "Side door".equals(n2.find(1100).description), "the new site's name and description merge in");
        check(e2.find(1300) != null && e2.find(1300).record && e2.find(1300).streams.contains("Sample Feed") && e2.find(1300) == n2.find(1300),
                "a talkgroup added with the new site shows on the other sites");
        check(again.systems.stream().filter(s -> s.talkgroups == n2.talkgroups).count() == 4, "four Sample Metro sites, one list");
    }

    static RadioSystem byId(App app, String id) {
        return app.systems.stream().filter(s -> id.equals(s.id)).findFirst().orElseThrow();
    }
}
