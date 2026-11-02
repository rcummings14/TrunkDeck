package p25monitor.rr;

import p25monitor.RadioSystem;
import p25monitor.Talkgroup;
import p25monitor.rr.RrModel.*;
import java.nio.file.*;
import java.util.*;

/** RadioReference setup (1.6.0) against MockRr (fictional data). No real RadioReference server or key is used. */
public final class RrTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok   " : "  FAIL ") + what); }
    static RrException expect(RrException.Kind k, Call c, String what) {
        try { c.run(); check(false, what + " (no error)"); return null; }
        catch (RrException e) { check(e.kind == k, what + " -> " + e.kind + ": " + e.getMessage()); return e; }
        catch (Exception e) { check(false, what + " threw " + e); return null; }
    }
    interface Call { void run() throws Exception; }
    static RrSoap client(MockRr m, String key, String user, String pw) throws RrException {
        System.setProperty("trunkdeck.rr.endpoint", m.endpoint());
        return new RrSoap(key, user, pw, "TrunkDeck/test");
    }

    public static void main(String[] a) throws Exception {
        Path tmp = Files.createTempDirectory("rrtests");
        try (MockRr m = new MockRr()) {
            System.out.println("Request format (rpc/encoded, SOAPAction, authInfo)");
            RrSoap rr = client(m, MockRr.KEY, MockRr.USER, MockRr.PASS);
            UserInfo u = rr.signIn();
            check(u.username().equals(MockRr.USER) && u.subExpireDate().equals("2027-06-30"), "signIn: getUserData user + Premium end date (" + u.subExpireDate() + ")");
            MockRr.Req r0 = m.requests.get(0);
            check(r0.op().equals("getUserData") && r0.soapAction().equals("\"http://api.radioreference.com/soap2#getUserData\""), "SOAPAction \"http://api.radioreference.com/soap2#getUserData\"");
            check(r0.auth().keySet().equals(new LinkedHashSet<>(List.of("username", "password", "appKey", "version", "style"))), "authInfo has username, password, appKey, version, style: " + r0.auth().keySet());
            check("18".equals(r0.auth().get("version")) && "rpc".equals(r0.auth().get("style")), "version 18, style rpc");
            check(MockRr.PASS.equals(r0.auth().get("password")), "password with & < \" ' arrives intact (XML-escaped)");
            String env = rr.envelope("getTrsSites", "sid", 5);
            check(env.contains("<sid xsi:type=\"xsd:int\">5</sid>") && env.contains("xsi:type=\"ns1:authInfo\"") && env.contains("SOAP-ENV:encodingStyle"), "envelope: typed rpc/encoded parameters");
            check(!rr.envelope("getCountryList").contains("authInfo"), "getCountryList sends no authInfo (as in the WSDL)");

            System.out.println("Happy path: state -> county -> system -> sites -> talkgroups -> new system");
            List<Country> cs = rr.getCountryList();
            check(cs.size() == 2 && cs.get(0).name().equals("United States"), "getCountryList: " + cs);
            List<State> st = rr.getCountryInfo(1);
            check(st.stream().anyMatch(s -> s.stid() == 99 && s.name().equals("Demo State")), "getCountryInfo: states " + st);
            StateInfo si = rr.getStateInfo(99);
            check(si.counties().size() == 2 && si.systems().size() == 1, "getStateInfo: 2 counties, 1 statewide system");
            CountyInfo ci = rr.getCountyInfo(9101);
            check(ci.systems().stream().anyMatch(t -> t.sid() == MockRr.DEMO_SID && t.name().equals("Demo County P25")), "getCountyInfo: systems " + ci.systems());
            TrsDetails d = rr.getTrsDetails(MockRr.DEMO_SID);
            check(d.typeName().equals("Project 25 Phase II") && d.sysids().get(0).sysid().equals("3c7"), "getTrsDetails + getTrsType: " + d.typeName() + ", sysid " + d.sysids());
            List<Site> sites = rr.getTrsSites(MockRr.DEMO_SID);
            check(sites.size() == 3 && sites.get(0).controlChannels().equals(List.of(851.0125, 851.5125)), "getTrsSites: 3 sites, site 1 control channels d then a " + sites.get(0).controlChannels());
            check(sites.get(0).label().equals("1-001") && sites.get(0).nac().equals("3C7") && sites.get(0).modulation().equals("LSM"), "site label 1-001, NAC, modulation");
            List<TgCat> cats = rr.getTrsTalkgroupCats(MockRr.DEMO_SID);
            List<Tg> tgs = rr.getTrsTalkgroups(MockRr.DEMO_SID);
            check(cats.size() == 5 && tgs.size() == 11, "getTrsTalkgroupCats 5, getTrsTalkgroups 11");
            MockRr.Req tr = m.requests.stream().filter(q -> q.op().equals("getTrsTalkgroups")).findFirst().get();
            check(tr.params().equals(Map.of("sid", "9001", "tgCid", "0", "tgTag", "0", "tgDec", "0")), "getTrsTalkgroups(sid, tgCid=0, tgTag=0, tgDec=0) = all");
            RrImport.Fetched f = new RrImport.Fetched(d, sites, cats, tgs);
            Set<Integer> def = RrImport.defaultSelection(f.ordered());
            check(!def.contains(302) && !def.contains(303) && !def.contains(402) && def.contains(301) && def.size() == 8, "default: every non-encrypted talkgroup (8 of 11; 302 DE, 303 enc=2, 402 TE left out; 301 De in) " + def);
            RadioSystem s = RrImport.build(f, List.of(sites.get(0)), def, null);
            check(s.name.equals("Demo County P25 - Demo North") && s.site.equals("Demo North"), "name \"" + s.name + "\", site " + s.site);
            check(s.controlChannelsMHz.equals(List.of(851.0125, 851.5125)), "control channels " + s.freqText());
            check(s.nac.equals("3C7") && s.sysId.equals("3C7") && s.wacn.equals("A1B2C") && s.rfss.equals("1") && s.siteNumber.equals("001"), "NAC/SysID/WACN/RFSS/site # filled");
            check(s.nativeModulation.equals("auto") && s.trafficChannels == 20, "TrunkDeck defaults kept (demodulator auto, 20 calls)");
            Talkgroup t101 = s.find(101), t301 = s.find(301);
            check(t101 != null && t101.alphaTag.equals("FIRE DISPATCH") && t101.category.equals("Demo County Fire") && t101.tag.equals("Fire Dispatch") && t101.description.equals("Demo County Fire Dispatch"), "talkgroup 101: alpha tag, category, tag, description");
            check(t301 != null && t301.mode.equals("De") && t301.isSometimesEncrypted() && !t301.isEncrypted(), "301 \"De\" = sometimes encrypted (imported, ticked)");
            check(s.talkgroups.get(0).decimal == 101 && s.talkgroups.get(s.talkgroups.size() - 1).decimal == 601, "category order kept");
            RadioSystem enc = RrImport.build(f, List.of(sites.get(0)), Set.of(302, 303, 402), "x");
            check(enc.talkgroups.stream().allMatch(Talkgroup::isEncrypted) && enc.find(303).mode.equals("DE"), "encrypted ones marked so the existing encrypted-call skip applies (303 enc=2 \"D\" -> \"DE\")");
            Path dir = tmp.resolve("sys"); s.save(dir);
            RadioSystem back = RadioSystem.load(dir);
            check(back.rrSid == 9001 && back.rrSites.equals("70001") && !back.rrUpdated.isEmpty() && back.talkgroups.size() == 8, "saved and loaded: rr.sid, rr.sites, rr.updated, talkgroups");
            RadioSystem plain = new RadioSystem(); plain.name = "x"; plain.controlChannelsMHz.add(851.0); plain.save(tmp.resolve("plain"));
            check(!Files.readString(tmp.resolve("plain/system.properties")).contains("rr."), "systems not from RadioReference: system.properties unchanged (no rr.* keys)");

            System.out.println("Zip code and system ID search");
            ZipInfo z = rr.getZipcodeInfo(99999);
            check(z.ctid() == 9101 && z.stid() == 99, "getZipcodeInfo -> county " + z.ctid());
            check(rr.getTrsBySysid("3c7").get(0).sid() == MockRr.DEMO_SID, "getTrsBySysid(3c7) -> Demo County P25");
            expect(RrException.Kind.FAULT, () -> rr.getZipcodeInfo(12), "unknown zip -> RR fault text shown");

            System.out.println("Multi-site");
            RadioSystem ms = RrImport.build(f, List.of(sites.get(0), sites.get(1)), def, null);
            check(ms.controlChannelsMHz.equals(List.of(851.0125, 851.5125, 853.1125, 853.6625)), "two sites: both sites' control channels, in order " + ms.freqText());
            check(ms.site.equals("Demo North + Demo South") && ms.rrSites.equals("70001,70002") && ms.name.equals("Demo County P25"), "site names joined, both site IDs kept, name without site");
            check(RrImport.controlChannels(List.of(sites.get(2))).equals(List.of(855.0125, 855.4875)), "a site with no marked control channel: all its frequencies (the engine hunts)");
            check(RrImport.obvious(sites, 9102) == sites.get(1) && RrImport.obvious(sites, 9101) == null && RrImport.obvious(sites, 0) == null, "pre-selected site: the only one in the chosen county, none if ambiguous");
            List<Site> one = rr.getTrsSites(MockRr.SMALL_SID);
            check(RrImport.obvious(one, 0) == one.get(0), "single-site system: that site pre-selected");

            System.out.println("Refresh: merge, user settings kept");
            RadioSystem mine = RrImport.build(f, List.of(sites.get(0)), def, "My Demo");
            mine.find(101).muted = true; mine.find(201).record = true; mine.find(401).streams.add("Broadcastify Feed");
            mine.find(102).favorite = true; mine.find(102).priority = 5;
            Talkgroup own = new Talkgroup(); own.decimal = 999; own.alphaTag = "MY OWN TG"; mine.talkgroups.add(own);
            int before = mine.talkgroups.size();
            List<String[]> tl = m.talkgroups.get(MockRr.DEMO_SID);
            tl.get(0)[1] = "FIRE DISPATCH 1";                                  // renamed
            tl.get(4)[2] = "EMS Tactical (new description)";                   // details
            tl.removeIf(x -> x[0].equals("103"));                              // gone from RR
            tl.add(new String[]{"104", "FIRE TAC 4", "Fire Tactical 4", "D", "0", "Fire-Tac", "501"});
            tl.add(new String[]{"403", "CITY PD INV", "Investigations", "DE", "2", "Law Tac", "504"});
            ((String[]) m.sites.get(MockRr.DEMO_SID).get(0)[8])[1] = "851.7625:a";
            RrSoap rr2 = client(m, MockRr.KEY, MockRr.USER, MockRr.PASS);
            List<Site> s2 = rr2.getTrsSites(MockRr.DEMO_SID);
            RrImport.Fetched f2 = new RrImport.Fetched(rr2.getTrsDetails(MockRr.DEMO_SID), s2, rr2.getTrsTalkgroupCats(MockRr.DEMO_SID), rr2.getTrsTalkgroups(MockRr.DEMO_SID));
            List<Tg> news = RrImport.newOnRr(mine, f2);
            check(news.size() == 2 && news.stream().anyMatch(t -> t.dec() == 104) && news.stream().anyMatch(t -> t.dec() == 403), "refresh offers only really new talkgroups (104, 403), not the encrypted ones skipped at set-up");
            Set<Integer> fresh = RrImport.defaultSelection(news);
            RrImport.Summary sum = RrImport.merge(mine, f2, List.of(s2.get(0)), fresh, true);
            System.out.println(sum.text().replaceAll("(?m)^", "        "));
            check(sum.added.size() == 1 && sum.added.get(0).decimal == 104 && sum.newSkipped == 1, "new: 104 added, encrypted 403 not chosen");
            check(sum.renamed.size() == 1 && sum.renamed.get(0).contains("FIRE DISPATCH \u2192 FIRE DISPATCH 1") && mine.find(101).alphaTag.equals("FIRE DISPATCH 1"), "renamed: " + sum.renamed);
            check(sum.detailsUpdated == 1 && mine.find(202).description.startsWith("EMS Tactical (new"), "description updated");
            check(sum.notOnRr == 2 && mine.find(103) != null && mine.find(999) != null && mine.talkgroups.size() == before + 1, "nothing deleted: 103 (gone from RR) and the user's own 999 kept");
            check(mine.find(101).muted && mine.find(201).record && mine.find(401).streams.contains("Broadcastify Feed") && mine.find(102).favorite && mine.find(102).priority == 5,
                    "mute, record, stream assignment, favourite, priority kept");
            check(sum.ccChanged() && mine.controlChannelsMHz.equals(List.of(851.0125, 851.7625)) && mine.name.equals("My Demo"), "control channel change reported (" + mine.freqText() + "), the user's name kept");
            RadioSystem keepNames = RrImport.build(f, List.of(sites.get(0)), def, "k");
            RrImport.Summary s3 = RrImport.merge(keepNames, f2, List.of(s2.get(0)), Set.of(), false);
            check(keepNames.find(101).alphaTag.equals("FIRE DISPATCH") && s3.renamed.isEmpty() && s3.added.isEmpty(), "\"update names\" off: own names kept, nothing added when nothing chosen");

            System.out.println("Errors");
            expect(RrException.Kind.LOGIN, () -> client(m, MockRr.KEY, MockRr.USER, "wrong").signIn(), "bad password");
            expect(RrException.Kind.LOGIN, () -> client(m, MockRr.KEY, "", "").signIn(), "empty login (no request sent)");
            RrException np = expect(RrException.Kind.NOT_PREMIUM, () -> client(m, MockRr.KEY, MockRr.LAPSED, MockRr.PASS).signIn(), "lapsed Premium (end date in the past)");
            expect(RrException.Kind.NOT_PREMIUM, () -> client(m, MockRr.KEY, MockRr.LAPSED, MockRr.PASS).getStateInfo(99), "lapsed Premium (RR fault on data call)");
            expect(RrException.Kind.NO_KEY, () -> new RrSoap("", "u", "p", "ua"), "no app key: refused before any request");
            RrException bk = expect(RrException.Kind.BAD_KEY, () -> client(m, "WRONG-KEY", MockRr.USER, MockRr.PASS).signIn(), "invalid app key");
            check(np != null && np.getMessage().contains("Premium") && bk != null && bk.getMessage().contains("app key"), "messages say what to do");
            int port; try (var ss = new java.net.ServerSocket(0)) { port = ss.getLocalPort(); }
            System.setProperty("trunkdeck.rr.endpoint", "http://127.0.0.1:" + port + "/soap2/index.php");
            RrSoap dead = new RrSoap(MockRr.KEY, MockRr.USER, MockRr.PASS, "ua");
            RrException ne = expect(RrException.Kind.NETWORK, dead::signIn, "network failure (nothing listening)");
            m.delayMs = 2500; RrSoap.timeoutMs = 600;
            RrException to = expect(RrException.Kind.NETWORK, () -> client(m, MockRr.KEY, MockRr.USER, MockRr.PASS).signIn(), "no answer in time");
            m.delayMs = 0; RrSoap.timeoutMs = 30_000;
            boolean leak = false;
            for (RrException e : new RrException[]{np, bk, ne, to}) if (e != null && (e.getMessage().contains(MockRr.PASS) || e.getMessage().contains(MockRr.KEY) || e.getMessage().contains("WRONG-KEY"))) leak = true;
            check(!leak, "no password or app key in any error message");
            check(RrException.classify("SOAP-ENV:Server", "Invalid username or password") == RrException.Kind.LOGIN
                    && RrException.classify("", "Invalid Application Key") == RrException.Kind.BAD_KEY
                    && RrException.classify("", "Your Premium Subscription has expired") == RrException.Kind.NOT_PREMIUM
                    && RrException.classify("", "Database is down for maintenance") == RrException.Kind.FAULT, "fault classification by RR's text");
            check(RrSoap.expiry("06-30-2027").toString().equals("2027-06-30") && RrSoap.expiry("2027-06-30 00:00:00").toString().equals("2027-06-30") && RrSoap.expiry("soon") == null, "Premium end date formats");

            System.out.println("Fetch only on request, cache locally");
            m.requests.clear();
            Path cache = tmp.resolve("rr-cache");
            RrSoap c1 = client(m, MockRr.KEY, MockRr.USER, MockRr.PASS).cacheIn(cache);
            c1.getStateInfo(99); c1.getStateInfo(99); c1.getCountyInfo(9101);
            check(m.count("getStateInfo") == 1 && c1.requests.get() == 2, "the same question is asked once per session");
            RrSoap c2 = client(m, MockRr.KEY, MockRr.USER, MockRr.PASS).cacheIn(cache);
            StateInfo cached = c2.getStateInfo(99); c2.getCountyInfo(9101);
            check(m.count("getStateInfo") == 1 && m.count("getCountyInfo") == 1 && c2.cacheHits.get() == 2 && cached.counties().size() == 2, "place lists come from the local cache next time (" + c2.cacheHits.get() + " hits, 0 requests)");
            c2.getTrsSites(MockRr.DEMO_SID); c2.getTrsTalkgroups(MockRr.DEMO_SID);
            RrSoap c3 = client(m, MockRr.KEY, MockRr.USER, MockRr.PASS).cacheIn(cache);
            c3.getTrsSites(MockRr.DEMO_SID);
            check(m.count("getTrsSites") == 2, "system data (sites, talkgroups) always fetched fresh on set-up / refresh");
            boolean secret = false;
            try (var w = Files.walk(cache)) { for (Path p : w.filter(Files::isRegularFile).toList()) { String x = Files.readString(p, java.nio.charset.StandardCharsets.ISO_8859_1); if (x.contains(MockRr.KEY) || x.contains("demo-pass")) secret = true; } }
            check(!secret, "the cache holds no login or key");
            RrSoap.clearCache(cache);
            check(client(m, MockRr.KEY, MockRr.USER, MockRr.PASS).cacheIn(cache).getStateInfo(99) != null && m.count("getStateInfo") == 2, "Reload lists clears the cache");
            long n0 = m.requests.size(); Thread.sleep(1500);
            check(m.requests.size() == n0, "nothing is fetched in the background");
        }

        System.out.println("App key: where it is found");
        Path home = tmp.resolve("home"), prof = tmp.resolve("profile"); Files.createDirectories(home.resolve("app")); Files.createDirectories(prof);
        check(RrKeys.find(Map.of(), home, prof, "/no/such/resource") == null, "no key anywhere -> none (wizard says setup isn't available)");
        RrKeys.save(prof, "  PASTED-KEY-1 \n");
        check(Files.readString(prof.resolve("rr-app-key.txt")).trim().equals("PASTED-KEY-1"), "pasted key saved to the settings folder (trimmed)");
        try { check(Files.getPosixFilePermissions(prof.resolve("rr-app-key.txt")).toString().equals("[OWNER_READ, OWNER_WRITE]"), "key file readable by the owner only"); } catch (UnsupportedOperationException e) { check(true, "key file permissions (not POSIX here)"); }
        check(RrKeys.find(Map.of(), home, prof, "/x").key().equals("PASTED-KEY-1"), "settings folder key found");
        Files.writeString(home.resolve("app/rr-app-key.txt"), "# comment\nAPP-FOLDER-KEY\n");
        check(RrKeys.find(Map.of(), home, prof, "/x").key().equals("APP-FOLDER-KEY"), "file next to the app wins over the settings folder");
        RrKeys.Found fe = RrKeys.find(Map.of(RrKeys.ENV, "ENV-KEY"), home, prof, "/x");
        check(fe.key().equals("ENV-KEY") && fe.source().contains(RrKeys.ENV), "environment variable wins over files");
        String b64 = Base64.getEncoder().encodeToString(new StringBuilder("BAKED-KEY-9").reverse().toString().getBytes());
        Path res = tmp.resolve("res/p25monitor/rr"); Files.createDirectories(res); Files.writeString(res.resolve("appkey.dat"), b64 + "\n");
        try (var cl = new java.net.URLClassLoader(new java.net.URL[]{tmp.resolve("res").toUri().toURL()})) {
            String got = new String(Base64.getDecoder().decode(new String(cl.getResourceAsStream("p25monitor/rr/appkey.dat").readAllBytes()).trim()));
            check(new StringBuilder(got).reverse().toString().equals("BAKED-KEY-9"), "build-time key format (base64 of the reversed key) decodes");
        }
        check(RrKeys.baked(RrKeys.BAKED).isEmpty(), "this build has no built-in key");
        RrKeys.save(prof, "");
        check(!Files.exists(prof.resolve("rr-app-key.txt")), "clearing the field deletes the key file");
        check(RrKeys.hint("ABCDEFGHIJKLMNOP").equals("ABCD\u2026MNOP (16 characters)"), "key shown only as a hint");
        // Windows Notepad variants
        Path np = tmp.resolve("notepad"); Files.createDirectories(np);
        Files.write(np.resolve("rr-app-key.txt"), ("\uFEFFBOM-KEY-1\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        check("BOM-KEY-1".equals(RrKeys.find(Map.of(), null, np, "/x").key()), "Notepad UTF-8 file with a byte-order mark and CRLF");
        Files.write(np.resolve("rr-app-key.txt"), ("\uFEFFU16-KEY-2\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
        check("U16-KEY-2".equals(RrKeys.find(Map.of(), null, np, "/x").key()), "Notepad \"Unicode\" (UTF-16) file");
        Files.delete(np.resolve("rr-app-key.txt")); Files.writeString(np.resolve("rr-app-key.txt.txt"), "DOUBLE-EXT-3\n");
        check("DOUBLE-EXT-3".equals(RrKeys.find(Map.of(), null, np, "/x").key()), "rr-app-key.txt.txt (hidden file extensions) is found too");

        System.out.println("Login: Remember me");
        RrLogin l = new RrLogin(); l.username = "demo-user"; l.password = "s3cret-pw"; l.remember = false; l.save(prof);
        String file = Files.readString(prof.resolve(RrLogin.FILE));
        check(file.contains("demo-user") && !file.contains("password=") && RrLogin.load(prof).password.isEmpty(), "Remember me off: username only, no password stored");
        l.remember = true; l.save(prof);
        file = Files.readString(prof.resolve(RrLogin.FILE));
        check(file.contains("password=v1\\:") && !file.contains("s3cret-pw") && RrLogin.load(prof).password.equals("s3cret-pw"), "Remember me on: password stored scrambled, read back");
        try { check(Files.getPosixFilePermissions(prof.resolve(RrLogin.FILE)).toString().equals("[OWNER_READ, OWNER_WRITE]"), "login file readable by the owner only"); } catch (UnsupportedOperationException e) { check(true, "login file permissions (not POSIX here)"); }
        Path other = tmp.resolve("other-profile"); Files.createDirectories(other); Files.copy(prof.resolve(RrLogin.FILE), other.resolve(RrLogin.FILE));
        check(RrLogin.load(other).password.isEmpty(), "copied to another profile folder: password not readable (asked again)");
        RrLogin.forget(prof);
        check(!Files.readString(prof.resolve(RrLogin.FILE)).contains("password=") && RrLogin.load(prof).username.equals("demo-user"), "Forget: password removed, username kept");

        System.out.println("RrTests: " + pass + " passed, " + fail + " failed (mock RadioReference server - not tested against the real service)");
        System.exit(fail == 0 ? 0 : 1);
    }
}
