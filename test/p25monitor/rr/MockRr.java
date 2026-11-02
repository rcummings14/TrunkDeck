package p25monitor.rr;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/**
 * A fake RadioReference SOAP2 server for tests and screenshots (same idea as MockIcecast). It answers the operations
 * TrunkDeck uses in the rpc/encoded form the WSDL describes (copy: radioreference-soap2.wsdl), checks the SOAPAction
 * and authInfo of every request, and records them. All data is fictional ("Demo State", "Demo County P25" ...).
 */
public final class MockRr implements AutoCloseable {
    public static final String KEY = "TEST-APP-KEY-0000";
    public static final String USER = "demo-user", PASS = "demo-pass&<\"'";   // XML characters on purpose
    public static final String LAPSED = "lapsed-user";
    public static final int DEMO_SID = 9001, SMALL_SID = 9002, EDACS_SID = 9003;

    public record Req(String op, String soapAction, Map<String, String> params, Map<String, String> auth) {}
    public final List<Req> requests = Collections.synchronizedList(new ArrayList<>());
    final HttpServer server;
    public volatile String premiumExpires = "2027-06-30";
    public volatile int delayMs;
    /** Refresh tests change the data between calls. */
    public final Map<Integer, List<String[]>> talkgroups = new HashMap<>();
    public final Map<Integer, List<Object[]>> sites = new HashMap<>();

    public MockRr() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/soap2/index.php", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "mock-rr"); t.setDaemon(true); return t; }));
        server.start();
        // talkgroups: dec, alpha, descr, mode, enc, tag, tgCid
        talkgroups.put(DEMO_SID, new ArrayList<>(List.of(
            new String[]{"101", "FIRE DISPATCH", "Demo County Fire Dispatch", "D", "0", "Fire Dispatch", "501"},
            new String[]{"102", "FIRE TAC 2", "Fire Tactical 2", "D", "0", "Fire-Tac", "501"},
            new String[]{"103", "FIRE TAC 3", "Fire Tactical 3", "D", "0", "Fire-Tac", "501"},
            new String[]{"201", "EMS OPS", "Demo County EMS Operations", "D", "0", "EMS Dispatch", "502"},
            new String[]{"202", "EMS TAC", "EMS Tactical", "D", "0", "EMS-Tac", "502"},
            new String[]{"301", "SHERIFF DISP", "Sheriff Dispatch", "De", "1", "Law Dispatch", "503"},
            new String[]{"302", "SHERIFF TAC", "Sheriff Tactical (encrypted)", "DE", "2", "Law Tac", "503"},
            new String[]{"303", "SHERIFF INV", "Sheriff Investigations", "D", "2", "Law Tac", "503"},
            new String[]{"401", "CITY PD MAIN", "Demo City Police Main", "D", "0", "Law Dispatch", "504"},
            new String[]{"402", "CITY PD TAC", "Demo City Police Tactical", "TE", "2", "Law Tac", "504"},
            new String[]{"601", "EOC INTEROP", "Countywide Interop", "D", "0", "Interop", "505"})));
        talkgroups.put(SMALL_SID, new ArrayList<>(List.of(
            new String[]{"1001", "SAMPLE PW", "Sample County Public Works", "D", "0", "Public Works", "701"},
            new String[]{"1002", "SAMPLE SCHOOLS", "Sample Schools", "D", "0", "Schools", "701"})));
        // sites: siteId, siteNumber, descr, rfss, nac, county, ctid, modulation, freqs "mhz:use" ...
        sites.put(DEMO_SID, new ArrayList<>(List.<Object[]>of(
            new Object[]{70001, 1, "Demo North", 1, "3C7", "Demo County", 9101, "LSM", new String[]{"851.0125:d", "851.5125:a", "852.0375:", "852.4875:"}},
            new Object[]{70002, 2, "Demo South", 1, "3C7", "Sample County", 9102, "C4FM", new String[]{"853.1125:d", "853.6625:a", "854.2375:"}},
            new Object[]{70003, 3, "Demo Simulcast", 1, "3C7", "Demo County", 9101, "LSM", new String[]{"855.0125:", "855.4875:"}})));
        sites.put(SMALL_SID, new ArrayList<>(List.<Object[]>of(
            new Object[]{70101, 1, "Sample Hill", 2, "1A4", "Sample County", 9102, "C4FM", new String[]{"772.0063:d", "772.3063:a", "773.0563:"}})));
    }

    public String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/soap2/index.php"; }
    public long count(String op) { synchronized (requests) { return requests.stream().filter(r -> r.op.equals(op)).count(); } }
    @Override public void close() { server.stop(0); }

    void handle(HttpExchange x) throws IOException {
        try {
            if (delayMs > 0) Thread.sleep(delayMs);
            byte[] in = x.getRequestBody().readAllBytes();
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true);
            Document d = f.newDocumentBuilder().parse(new ByteArrayInputStream(in));
            Element body = first(d.getDocumentElement(), "Body");
            Element call = body == null ? null : firstElement(body);
            if (call == null) { send(x, 500, fault("SOAP-ENV:Client", "Bad request")); return; }
            String op = call.getLocalName();
            Map<String, String> params = new LinkedHashMap<>(), auth = new LinkedHashMap<>();
            for (Element e : kids(call)) {
                if (e.getLocalName().equals("authInfo")) for (Element a : kids(e)) auth.put(a.getLocalName(), a.getTextContent());
                else params.put(e.getLocalName(), e.getTextContent());
            }
            String sa = x.getRequestHeaders().getFirst("SOAPAction");
            requests.add(new Req(op, sa, params, auth));
            if (sa == null || !sa.equals("\"http://api.radioreference.com/soap2#" + op + "\"")) { send(x, 500, fault("SOAP-ENV:Client", "Wrong SOAPAction")); return; }
            if (!op.equals("getCountryList")) {
                if (!KEY.equals(auth.get("appKey"))) { send(x, 500, fault("SOAP-ENV:Server", "Invalid Application Key")); return; }
                boolean good = (USER.equals(auth.get("username")) || LAPSED.equals(auth.get("username"))) && PASS.equals(auth.get("password"));
                if (!good) { send(x, 500, fault("SOAP-ENV:Server", "Invalid username or password")); return; }
                if (LAPSED.equals(auth.get("username")) && !op.equals("getUserData")) { send(x, 500, fault("SOAP-ENV:Server", "Your Premium Subscription has expired")); return; }
            }
            String r = answer(op, params, auth);
            if (r == null) { send(x, 500, fault("SOAP-ENV:Server", "Unknown method " + op)); return; }
            send(x, 200, envelope("<ns1:" + op + "Response>" + r + "</ns1:" + op + "Response>"));
        } catch (Exception e) {
            send(x, 500, fault("SOAP-ENV:Server", "mock error " + e));
        }
    }

    String answer(String op, Map<String, String> p, Map<String, String> auth) {
        int id = 0;
        for (String k : List.of("sid", "stid", "ctid", "coid", "zipcode", "id")) if (p.containsKey(k)) try { id = Integer.parseInt(p.get(k).trim()); } catch (Exception ignored) {}
        switch (op) {
            case "getUserData": return "<return xsi:type=\"ns1:UserInfo\">" + v("username", "string", auth.get("username"))
                    + v("subExpireDate", "string", LAPSED.equals(auth.get("username")) ? "2024-01-01" : premiumExpires) + "</return>";
            case "getCountryList": return arr("Countries", "Country", List.of(
                    v("coid", "int", 1) + v("countryName", "string", "United States") + v("countryCode", "string", "US"),
                    v("coid", "int", 2) + v("countryName", "string", "Canada") + v("countryCode", "string", "CA")));
            case "getCountryInfo": return "<return xsi:type=\"ns1:CountryInfo\">" + v("coid", "int", id) + v("countryName", "string", "United States")
                    + v("countryCode", "string", "US") + arrNamed("agencyList", "Agencies", "Agency", List.of()) + arrNamed("stateList", "States", "State", List.of(
                    v("stid", "int", 98) + v("stateName", "string", "Another Demo State") + v("stateCode", "string", "AD"),
                    v("stid", "int", 99) + v("stateName", "string", "Demo State") + v("stateCode", "string", "DS"))) + "</return>";
            case "getStateInfo": return "<return xsi:type=\"ns1:StateInfo\">" + v("stid", "int", id) + v("stateName", "string", id == 99 ? "Demo State" : "Another Demo State")
                    + v("stateEntityType", "string", "State")
                    + arrNamed("trsList", "TrsList", "TrsListDef", id == 99 ? List.of(trs(9004, "Demo Statewide P25 (fictional)", 8, "Statewide")) : List.of())
                    + arrNamed("agencyList", "Agencies", "Agency", List.of())
                    + arrNamed("countyList", "Counties", "County", id == 99 ? List.of(
                        v("ctid", "int", 9101) + v("countyName", "string", "Demo") + v("countyHeader", "string", "Demo County"),
                        v("ctid", "int", 9102) + v("countyName", "string", "Sample") + v("countyHeader", "string", "Sample County")) : List.of()) + "</return>";
            case "getCountyInfo": return "<return xsi:type=\"ns1:CountyInfo\">" + v("ctid", "int", id) + v("countyName", "string", id == 9101 ? "Demo" : "Sample")
                    + v("countyHeader", "string", id == 9101 ? "Demo County" : "Sample County") + v("stid", "int", 99) + v("fips", "string", "99999")
                    + arrNamed("cats", "Cats", "cat", List.of())
                    + arrNamed("trsList", "TrsList", "TrsListDef", id == 9101 ? List.of(trs(DEMO_SID, "Demo County P25", 8, "Demo City"), trs(EDACS_SID, "Demo City EDACS (fictional)", 2, "Demo City"))
                        : List.of(trs(SMALL_SID, "Sample County P25", 8, "Sample"), trs(DEMO_SID, "Demo County P25", 8, "Demo City")))
                    + arrNamed("agencyList", "Agencies", "Agency", List.of()) + "</return>";
            case "getZipcodeInfo": return id == 99999 ? "<return xsi:type=\"ns1:ZipInfo\">" + v("zipCode", "int", 99999) + v("lat", "string", "0") + v("lon", "string", "0")
                    + v("city", "string", "Demo City") + v("stid", "int", 99) + v("ctid", "int", 9101) + "</return>" : null;
            case "getTrsBySysid": return "3C7".equalsIgnoreCase(p.get("sysid")) ? arr("TrsList", "TrsListDef", List.of(trs(DEMO_SID, "Demo County P25", 8, "Demo City"))) : arr("TrsList", "TrsListDef", List.of());
            case "getTrsType": return arr("TrsType", "trsTypeDef", List.of(v("sType", "int", id) + v("sTypeDescr", "string", id == 8 ? "Project 25 Phase II" : id == 2 ? "EDACS Standard" : "Other")));
            case "getTrsDetails": {
                if (!sites.containsKey(id) && id != EDACS_SID) return null;
                String name = id == DEMO_SID ? "Demo County P25" : id == SMALL_SID ? "Sample County P25" : "Demo City EDACS (fictional)";
                return "<return xsi:type=\"ns1:Trs\">" + v("sName", "string", name) + v("sType", "int", id == EDACS_SID ? 2 : 8) + v("sFlavor", "int", 1) + v("sVoice", "int", 1)
                        + v("sCity", "string", "Demo City") + arrNamed("sCounty", "ctids", "ctid", List.of(v("ctid", "int", 9101))) + arrNamed("sState", "stids", "stid", List.of(v("stid", "int", 99)))
                        + v("sCountry", "string", "1") + arrNamed("sysid", "TrsSysid", "trsSysidDef", id == EDACS_SID ? List.of() : List.of(
                            v("sysid", "string", id == DEMO_SID ? "3c7" : "1a4") + v("ct", "string", "") + v("wacn", "string", "a1b2c") + v("model", "string", "")))
                        + "</return>";
            }
            case "getTrsSites": {
                List<String> items = new ArrayList<>();
                for (Object[] s : sites.getOrDefault(id, List.of())) {
                    List<String> fr = new ArrayList<>(); int lcn = 1;
                    for (String f : (String[]) s[8]) { String[] a = f.split(":", -1); fr.add(v("lcn", "int", lcn++) + v("freq", "decimal", a[0]) + v("use", "string", a[1]) + v("colorCode", "string", "") + v("ch_id", "string", "")); }
                    items.add(v("siteId", "int", s[0]) + v("sid", "int", id) + v("siteNumber", "int", s[1]) + v("siteDescr", "string", s[2]) + v("zoneNumber", "int", 0)
                            + v("zoneDescr", "string", "") + v("rfss", "int", s[3]) + v("nac", "string", s[4]) + v("ran", "int", 0) + v("siteCtid", "int", s[6]) + v("siteCt", "string", s[5])
                            + v("siteModulation", "string", s[7]) + v("tdma_cc", "int", 0) + arrNamed("siteFreqs", "TrsSiteFreqs", "TrsSiteFreq", fr));
                }
                return arr("TrsSites", "TrsSite", items);
            }
            case "getTrsTalkgroupCats": {
                List<String> items = new ArrayList<>();
                Map<Integer, String> names = new LinkedHashMap<>();
                if (id == DEMO_SID) { names.put(501, "Demo County Fire"); names.put(502, "Demo County EMS"); names.put(503, "Demo County Sheriff"); names.put(504, "Demo City Police"); names.put(505, "Interop"); }
                if (id == SMALL_SID) names.put(701, "Sample County");
                int sort = 1;
                for (var e : names.entrySet()) items.add(v("tgCid", "int", e.getKey()) + v("sid", "int", id) + v("tgCname", "string", e.getValue()) + v("tgSort", "int", sort++) + v("tgSortBy", "int", 0));
                return arr("TalkgroupCats", "TalkgroupCat", items);
            }
            case "getTrsTalkgroups": {
                List<String> items = new ArrayList<>(); int sort = 1;
                for (String[] t : talkgroups.getOrDefault(id, List.of()))
                    items.add(v("tgId", "int", 50000 + Integer.parseInt(t[0])) + v("tgDec", "int", t[0]) + v("tgSubfleet", "string", "") + v("tgLtr", "boolean", "false") + v("tgSlot", "string", "")
                            + v("tgDescr", "string", t[2]) + v("tgAlpha", "string", t[1]) + v("tgMode", "string", t[3]) + v("enc", "int", t[4])
                            + arrNamed("tags", "tags", "tag", List.of(v("tagId", "int", 1) + v("tagDescr", "string", t[5]))) + v("tgCid", "int", t[6]) + v("tgSort", "int", sort++)
                            + v("tgDate", "dateTime", "2026-01-01T00:00:00"));
                return arr("Talkgroups", "Talkgroup", items);
            }
            default: return null;
        }
    }

    static String trs(int sid, String name, int type, String city) {
        return v("sid", "int", sid) + v("sName", "string", name) + v("sType", "int", type) + v("sFlavor", "int", 1) + v("sVoice", "int", 1) + v("sCity", "string", city) + v("lastUpdated", "dateTime", "2026-01-01T00:00:00");
    }
    static String v(String n, String type, Object val) { return "<" + n + " xsi:type=\"xsd:" + type + "\">" + p25monitor.rr.RrSoap.esc(String.valueOf(val)) + "</" + n + ">"; }
    static String arr(String type, String item, List<String> items) { return arrNamed("return", type, item, items); }
    static String arrNamed(String name, String type, String item, List<String> items) {
        StringBuilder b = new StringBuilder("<" + name + " SOAP-ENC:arrayType=\"ns1:" + item + "[" + items.size() + "]\" xsi:type=\"ns1:" + type + "\">");
        for (String i : items) b.append("<item xsi:type=\"ns1:").append(item).append("\">").append(i).append("</item>");
        return b.append("</").append(name).append(">").toString();
    }
    static String envelope(String inner) {
        return "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n<SOAP-ENV:Envelope SOAP-ENV:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\" xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\" "
             + "xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:SOAP-ENC=\"http://schemas.xmlsoap.org/soap/encoding/\" "
             + "xmlns:ns1=\"http://api.radioreference.com/soap2\"><SOAP-ENV:Body>" + inner + "</SOAP-ENV:Body></SOAP-ENV:Envelope>";
    }
    static String fault(String code, String text) { return envelope("<SOAP-ENV:Fault><faultcode>" + code + "</faultcode><faultstring>" + p25monitor.rr.RrSoap.esc(text) + "</faultstring></SOAP-ENV:Fault>"); }
    static void send(HttpExchange x, int code, String xml) throws IOException {
        byte[] b = xml.getBytes(StandardCharsets.ISO_8859_1);
        x.getResponseHeaders().set("Content-Type", "text/xml; charset=ISO-8859-1");
        x.sendResponseHeaders(code, b.length);
        try (OutputStream o = x.getResponseBody()) { o.write(b); }
    }
    static Element first(Element e, String local) { for (Element k : kids(e)) if (local.equals(k.getLocalName())) return k; return null; }
    static Element firstElement(Element e) { List<Element> k = kids(e); return k.isEmpty() ? null : k.get(0); }
    static List<Element> kids(Element e) { List<Element> l = new ArrayList<>(); for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element el) l.add(el); return l; }
}
