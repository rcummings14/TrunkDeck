package p25monitor;

import p25monitor.p25.MotorolaAlias;
import p25monitor.p25.P25Framer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 1.4.1 radio ID names: DSD+ .radios and CSV import, merge rules (names the user typed win unless overwrite),
 * radios.csv round trip and persistence next to talkgroups.csv, labels, OpenMHz source_list tag.
 * Your own DSD+ file can be tested with -DradiosFile (default private-testdata/my.radios);
 * it is user data and not part of the release - that part is skipped if the file is not there.
 * Never touches ~/.trunkdeck: everything is written to a temporary folder.
 */
public final class RadioAliasTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) { pass++; System.out.println("  ok   " + what); } else { fail++; System.out.println("  FAIL " + what); } }

    public static void main(String[] a) throws Exception {
        Path tmp = Files.createTempDirectory("radio-alias-tests");
        System.setProperty("trunkdeck.profile", tmp.resolve("profile").toString());
        userFile();
        dsdPlusSnippet();
        csvImport();
        mergeRules();
        roundTrip(tmp);
        persistence(tmp);
        labelsAndUploads();
        otaAlias();
        System.out.println("RadioAliasTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ your own DSD+ file (optional)
    static void userFile() throws Exception {
        System.out.println("1. Your own DSD+ radios file (optional: -DradiosFile=FILE -DradiosNetwork=WACN.SYSID, e.g. A4321.2D5)");
        Path p = Paths.get(System.getProperty("radiosFile", "private-testdata/my.radios"));
        if (!Files.exists(p)) { System.out.println("   " + p + " not present - skipped"); return; }
        String text = RadioAliases.readText(p);
        check(RadioAliases.looksLikeDsdPlus(p, text), "recognised as a DSD+ radios file");
        String net = System.getProperty("radiosNetwork", RadioAliases.networkOf(RadioSystem.presets().get(0)));
        RadioAliases.ParseResult r = RadioAliases.parseDsdPlus(text, net);
        int mine = r.entries.size() + r.noAlias + r.badId;
        System.out.printf("   %d records: %d of %s (%d named, %d without a name), skipped %d not P25 + %d other P25 systems; networks %s%n",
                r.records, mine, net, r.entries.size(), r.noAlias, r.notP25, r.otherNetwork, r.networks);
        check(r.records > 0 && r.records == mine + r.notP25 + r.otherNetwork, "every record counted once (" + r.records + ")");
        check(r.networks.getOrDefault(net, 0) == mine, "network count " + net + " = " + mine);
        RadioAliases store = new RadioAliases();
        RadioAliases.MergeResult m = store.merge(r.entries, false);
        System.out.println("   " + m.summary(r).replace("\n", "\n   "));
        check(m.added == r.entries.size() && m.updated == 0 && store.size() == r.entries.size(), r.entries.size() + " names imported into an empty list");
        check(store.snapshot().values().stream().allMatch(e -> !e.alias().isEmpty() && !e.alias().startsWith("*")), "every imported name non-empty, no '*' prefix");
        RadioAliases.MergeResult again = store.merge(RadioAliases.parseDsdPlus(text, net).entries, false);
        check(again.added == 0 && again.updated == 0 && again.unchanged == r.entries.size(), "importing the same file again changes nothing");
    }

    // ------------------------------------------------------------------ format details
    static void dsdPlusSnippet() {
        System.out.println("2. DSD+ format details");
        String t = String.join("\n",
                "; DSD+ 2.547; radio records",
                "; line format: protocol, networkID, group, radio, priority, override, hits, timestamp, \"radio alias\" [, \"P25 talker alias\", checksum]",
                "   ;;",
                "P25,       A4321.2D5, -2,         2000,       50,  Normal,       0,  2026/01/01 12:00,  \"\", \"TEST UNIT 02\",   12345678",
                "P25,       A4321.2D5, 101,        2001,       50,  Normal,       3,  2026/01/01 12:00,  \"My name\", \"OTA NAME\",   12345678",
                "P25,       A4321.2D5, -1,         2002,       50,  Normal,       3,  2026/01/01 12:00,  *\"Starred\"",
                "P25,       a4321.02d5, 7777,      2003,       50,  Normal,       3,  2026/01/01 12:00,  \"\", \"Comma, inside\", 1",
                "P25,       A4321.2D5, -2,         2004,       50,  Normal,       0,  2026/01/01 12:00,  \"\"",
                "P25,       A4321.2D5, -2,         99999999,   50,  Normal,       0,  2026/01/01 12:00,  \"too big\"",
                "P25,       A4321.2D6, -2,         2005,       50,  Normal,       0,  2026/01/01 12:00,  \"other system\"",
                "P25,       2D5,       -2,         2006,       50,  Normal,       0,  2026/01/01 12:00,  \"no wacn\"",
                "DMR,       0,         -1,         2007,       50,  Normal,       0,  2026/01/01 12:00,  \"dmr\"",
                "P25,       A4321.2D5, -2,         2008,       50,  Normal,       0,  2026/01/01 12:00,  \"  spaced \t out  \", \"x\", 1",
                "");
        RadioAliases.ParseResult r = RadioAliases.parseDsdPlus(t, "A4321.2D5");
        Map<Integer, String> m = new HashMap<>();
        for (RadioAliases.Parsed p : r.entries) m.put(p.id(), p.alias());
        check("TEST UNIT 02".equals(m.get(2000)) && r.entries.stream().anyMatch(p -> p.id() == 2000 && p.source() == RadioAliases.Source.OTA),
                "talker alias used (group -2) and stored as over the air");
        check("OTA NAME".equals(m.get(2001)) && !m.containsValue("My name"), "the heard name is used and the typed radio alias is ignored");
        check(!m.containsKey(2002), "a typed radio alias with no talker alias is skipped");
        check("Comma, inside".equals(m.get(2003)), "quoted comma kept; network matched case-insensitively with leading zeros (a4321.02d5)");
        check(!m.containsKey(2004) && r.noAlias == 2, "records without a talker alias are skipped and counted");
        check(r.badId == 1, "radio ID above 24 bits skipped as invalid");
        check(r.otherNetwork == 2 && !m.containsKey(2005) && !m.containsKey(2006), "other SysID and SysID-without-WACN skipped (2)");
        check(r.notP25 == 1, "DMR record skipped");
        check("x".equals(m.get(2008)), "talker alias used; a leading '*' on a typed alias is not the name");
        check(r.records == 10 && r.comments == 3, "10 records, 3 comment lines (" + r.records + ", " + r.comments + ")");
        check(RadioAliases.clean("x".repeat(60)).length() == RadioAliases.MAX_LEN, "names are capped at 40 characters");
    }

    static void csvImport() {
        System.out.println("3. Plain CSV import");
        RadioAliases.ParseResult a = RadioAliases.parseCsv(Csv.parse("radio_id,alias\n123456,EXAMPLE UNIT 7\n2000,\"EX, CNSLT 02\"\n,missing\nabc,bad\n0,zero\n5,\n"));
        check(a.entries.size() == 2 && a.entries.get(1).alias().equals("EX, CNSLT 02"), "with header: 2 names, quoted comma kept");
        check(a.badId == 3 && a.noAlias == 1, "empty / non-numeric / 0 IDs and an empty name skipped (3 + 1)");
        RadioAliases.ParseResult b = RadioAliases.parseCsv(Csv.parse("123456,EXAMPLE UNIT 7\r\n2000,TEST UNIT 02\r\n"));
        check(b.entries.size() == 2 && b.entries.get(0).id() == 123456, "without header (CRLF)");
        RadioAliases.ParseResult c = RadioAliases.parseCsv(Csv.parse("Name;Radio ID\nUnit A;10\nUnit B;11\n"));
        check(c.entries.size() == 2 && c.entries.get(0).id() == 10 && c.entries.get(0).alias().equals("Unit A"), "header in another order, ';' delimiter");
        RadioAliases.ParseResult d = RadioAliases.parseCsv(Csv.parse("radio_id,alias,source\n10,Mine,manual\n11,Theirs,import\n"));
        check(d.entries.get(0).source() == RadioAliases.Source.MANUAL && d.entries.get(1).source() == RadioAliases.Source.IMPORT, "TrunkDeck export: manual / import kept");
        check(!RadioAliases.looksLikeDsdPlus(Paths.get("x.csv"), "radio_id,alias\n"), "a CSV is not taken for a DSD+ file");
    }

    static void mergeRules() {
        System.out.println("4. Merge rules");
        RadioAliases s = new RadioAliases();
        s.setManual(10, "My own name");
        s.merge(List.of(new RadioAliases.Parsed(11, "Old import", RadioAliases.Source.IMPORT)), false);
        List<RadioAliases.Parsed> in = List.of(new RadioAliases.Parsed(10, "Imported", RadioAliases.Source.IMPORT),
                new RadioAliases.Parsed(11, "New import", RadioAliases.Source.IMPORT), new RadioAliases.Parsed(12, "Brand new", RadioAliases.Source.IMPORT));
        check(s.conflictsWithManual(in) == 1, "1 conflict with a name the user typed is reported before importing");
        RadioAliases.MergeResult m = s.merge(in, false);
        check(m.added == 1 && m.updated == 1 && m.keptManual == 1, "keep: 1 added, 1 imported name updated, 1 own name kept");
        check(s.alias(10).equals("My own name") && s.entry(10).source() == RadioAliases.Source.MANUAL, "own name not overwritten");
        check(s.alias(11).equals("New import"), "an imported name is updated by a newer import");
        RadioAliases.MergeResult o = s.merge(in, true);
        check(o.updated == 1 && s.alias(10).equals("Imported") && s.entry(10).source() == RadioAliases.Source.IMPORT, "overwrite: own name replaced");
        s.setManual(12, "  ");
        check(s.alias(12).isEmpty() && s.entry(12) == null, "an empty name clears the radio");
        boolean threw = false;
        try { s.setManual(0x1000000, "x"); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "radio IDs above 16777215 are refused");
    }

    static void roundTrip(Path tmp) throws Exception {
        System.out.println("5. CSV round trip (Export -> Import)");
        RadioAliases s = new RadioAliases();
        s.setManual(123456, "EXAMPLE UNIT 7");
        s.setManual(1, "Quote \"q\", comma");
        s.setManual(16777215, "Ünïcødé 名前");
        s.merge(List.of(new RadioAliases.Parsed(2000, "TEST UNIT 02", RadioAliases.Source.IMPORT)), false);
        Path f = tmp.resolve("export.csv");
        s.write(f);
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        check(lines.get(0).equals("radio_id,alias,source") && lines.size() == 5, "header radio_id,alias,source + 4 rows");
        check(lines.get(1).equals("1,\"Quote \"\"q\"\", comma\",manual"), "quotes and commas escaped (" + lines.get(1) + ")");
        RadioAliases back = new RadioAliases();
        back.merge(RadioAliases.parseCsv(Csv.read(f)).entries, false);
        check(back.snapshot().equals(s.snapshot()), "re-imported list identical (names, IDs and sources)");
    }

    static void persistence(Path tmp) throws Exception {
        System.out.println("6. Stored per system next to talkgroups.csv");
        App app = new App(tmp);
        app.load();
        RadioSystem sys = app.systems.stream().filter(x -> x.name.contains("Site 001")).findFirst().orElseThrow();
        Path dir = app.systemsDir().resolve(sys.id);
        check(!Files.exists(dir.resolve(RadioAliases.FILE)), "no radios.csv until a radio is named");
        sys.radios.setManual(123456, "EXAMPLE UNIT 7");
        app.save(sys);
        check(Files.exists(dir.resolve("radios.csv")) && Files.exists(dir.resolve("talkgroups.csv")), "radios.csv written beside talkgroups.csv in " + dir);
        long m0 = Files.getLastModifiedTime(dir.resolve("radios.csv")).toMillis();
        Thread.sleep(20);
        Files.setLastModifiedTime(dir.resolve("radios.csv"), java.nio.file.attribute.FileTime.fromMillis(m0 - 5000));
        app.save(sys);
        check(Files.getLastModifiedTime(dir.resolve("radios.csv")).toMillis() == m0 - 5000, "saving the system without radio changes does not rewrite radios.csv");
        App app2 = new App(tmp); app2.load();
        RadioSystem s2 = app2.systems.stream().filter(x -> x.id.equals(sys.id)).findFirst().orElseThrow();
        check(s2.radios.alias(123456).equals("EXAMPLE UNIT 7") && s2.radios.entry(123456).source() == RadioAliases.Source.MANUAL, "loaded back after a restart (manual)");
        RadioSystem dup = SystemsPage.copy(s2);
        dup.radios.setManual(5, "only in the copy");
        check(dup.radios.alias(123456).equals("EXAMPLE UNIT 7") && s2.radios.alias(5).isEmpty(), "Duplicate system copies the radio names (independent copy)");
        RadioSystem other = app2.systems.stream().filter(x -> !x.id.equals(sys.id)).findFirst().orElseThrow();
        check(other.radios.isEmpty(), "other systems have their own (empty) list");
        s2.radios.setManual(123456, "");
        app2.save(s2);
        App app3 = new App(tmp); app3.load();
        check(app3.systems.stream().filter(x -> x.id.equals(sys.id)).findFirst().orElseThrow().radios.isEmpty(), "clearing the last name persists");
        check(app.profile.startsWith(tmp), "test profile is a temporary folder (" + app.profile + ")");
    }

    static void labelsAndUploads() {
        System.out.println("7. Labels and OpenMHz source_list tag");
        RadioSystem s = new RadioSystem();
        s.radios.setManual(123456, "EXAMPLE UNIT 7");
        s.radios.setManual(7, "Say \"hi\" \\ ok");
        check(s.radioLabel(123456).equals("EXAMPLE UNIT 7 (123456)") && s.radioLabel(42).equals("42") && s.radioLabel(-1).isEmpty(), "labels: named, bare ID, none");
        var call = new p25monitor.nativeengine.OpenMhzUploader.Call(2001, 413_400_000L, 0, new float[8000], 8000,
                List.of(new long[]{0, 123456}, new long[]{4000, 42}, new long[]{6000, 7}), false, 0, List.of());
        String plain = p25monitor.nativeengine.OpenMhzProbe.sourceList(call, null);
        check(plain.equals("[{ \"pos\": 0.00, \"src\": 123456, \"tag\": \"\" }, { \"pos\": 0.50, \"src\": 42, \"tag\": \"\" }, { \"pos\": 0.75, \"src\": 7, \"tag\": \"\" }]"),
                "names off (default): source_list unchanged from 1.4.0");
        String named = p25monitor.nativeengine.OpenMhzProbe.sourceList(call, s.radios::alias);
        check(named.contains("\"src\": 123456, \"tag\": \"EXAMPLE UNIT 7\"") && named.contains("\"src\": 42, \"tag\": \"\"")
                && named.contains("\"tag\": \"Say \\\"hi\\\" \\\\ ok\""), "names on: tag = radio name, JSON-escaped (" + named + ")");
        check(p25monitor.nativeengine.MockOpenMhz.parseJsonList(named).size() == 3, "the mock OpenMHz server still parses the list");
        StreamConfig c = new StreamConfig();
        check(!c.sendRadioNames, "sending names to OpenMHz is off unless ticked");
    }

    /** sdrtrunk issue 2092's Motorola train, plus a TDULC build/decode round trip. The log's "*ALIAS 319439*" is that
     *  program's alias-list label; the CRC-checked payload is the 11-character name. */
    static void otaAlias() {
        System.out.println("8. Motorola over-the-air talker alias");
        byte[][] train = {
                {(byte) 0x15, (byte) 0x90, 0x2D, 0x2A, 0x06, 0x01, 0x00, (byte) 0xE4, (byte) 0xAF},
                {(byte) 0x17, (byte) 0x90, 0x01, (byte) 0xEB, (byte) 0xEE, 0x07, 0x40, (byte) 0xF0, 0x4D},
                {(byte) 0x17, (byte) 0x90, 0x02, (byte) 0xEF, (byte) 0xCF, 0x2D, 0x21, 0x68, 0x1A},
                {(byte) 0x17, (byte) 0x90, 0x03, (byte) 0xE1, (byte) 0xB5, 0x2F, (byte) 0xFB, (byte) 0xFB, (byte) 0xFE},
                {(byte) 0x17, (byte) 0x90, 0x04, (byte) 0xEE, 0x53, (byte) 0xBD, 0x50, 0x6F, (byte) 0xA3},
                {(byte) 0x17, (byte) 0x90, 0x05, (byte) 0xE1, 0x23, 0x44, 0x5C, 0x7C, 0x35},
                {(byte) 0x17, (byte) 0x90, 0x06, (byte) 0xE0, (byte) 0x8A, (byte) 0xDD, 0x33, 0x00, 0x00}
        };
        MotorolaAlias mot = new MotorolaAlias();
        MotorolaAlias.Decoded early = null;
        for (int i = 0; i < train.length - 1; i++) early = mot.feed(word(train[i]));
        check(early == null, "an unfinished train is not published");
        MotorolaAlias.Decoded d = mot.feed(word(train[train.length - 1]));
        check(d != null && "SPPD-706813".equals(d.alias()) && d.radioId() == 319439 && d.wacn() == 0xBEE07 && d.systemId() == 0x40F,
                "CRC-checked alias SPPD-706813 for radio 319439, WACN BEE07, system 40F" + (d == null ? "" : " (got " + d.alias() + " radio " + d.radioId() + ")"));
        check(mot.feed(word(train[train.length - 1])) == null, "the same data block does not publish again without a new header");
        if (d == null) return;
        MotorolaAlias bad = new MotorolaAlias();
        byte[] flipped = train[6].clone();
        flipped[4] ^= 0x01;
        for (int i = 0; i < 6; i++) bad.feed(word(train[i]));
        check(bad.feed(word(flipped)) == null, "a train that fails CRC is not published");
        RadioAliases learned = new RadioAliases();
        learned.setOverTheAir(d.radioId(), d.alias());
        check("SPPD-706813".equals(learned.alias(319439)) && learned.entry(319439).source() == RadioAliases.Source.OTA, "OTA name stored");
        RadioAliases typed = new RadioAliases();
        typed.setManual(319439, "TYPED");
        typed.setOverTheAir(319439, d.alias());
        check("SPPD-706813".equals(typed.alias(319439)) && typed.entry(319439).source() == RadioAliases.Source.OTA, "an over-the-air name replaces a typed name");
        check(typed.conflictsWithManual(List.of(new RadioAliases.Parsed(319439, "HAR SWFD BASE", RadioAliases.Source.OTA))) == 0, "an over-the-air import does not ask to keep a typed name");
        typed.merge(List.of(new RadioAliases.Parsed(319439, "HAR SWFD BASE", RadioAliases.Source.OTA)), false);
        check("HAR SWFD BASE".equals(typed.alias(319439)) && typed.entry(319439).source() == RadioAliases.Source.OTA, "a DSD+ talker alias replaces the stored name");

        for (byte[] msg : new byte[][] {train[0], train[1]}) {
            P25Framer.LinkControl[] got = {null};
            int[] terms = {0};
            int[] nacGot = {-1};
            P25Framer fr = new P25Framer(new P25Framer.Listener() {
                public void tdulc(int nac, P25Framer.LinkControl lc) { got[0] = lc; nacGot[0] = nac; }
                public void terminator(int nac, int duid) { terms[0]++; }
            });
            fr.sync(1f);
            int[] dibits = P25Framer.buildTdulc(0x40D, msg);
            for (int i = 24; i < dibits.length; i++) fr.symbol(dibits[i], 1f);
            check(got[0] != null && Arrays.equals(msg, got[0].b) && nacGot[0] == 0x40D && terms[0] == 1,
                    "TDULC " + String.format("%02X %02X", msg[0], msg[2]) + " round-trips and still ends the transmission");
        }
    }

    static P25Framer.LinkControl word(byte[] b) {
        return new P25Framer.LinkControl(P25Framer.hexbits(b, 12));
    }
}
