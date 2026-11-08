package p25monitor.rr;

import p25monitor.rr.RrModel.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/**
 * A small client for the RadioReference Database Web Service (SOAP2, rpc/encoded SOAP 1.1), written against its WSDL
 * (https://api.radioreference.com/soap2/?wsdl&v=latest). Every call carries authInfo {username, password, appKey,
 * version, style} - the user's own RadioReference Premium login. Calls are made only when the user sets up or
 * refreshes a system (no background polling). The password and app key are never logged or put in error messages.
 */
public final class RrSoap {
    public static final String NS = "http://api.radioreference.com/soap2";
    public static final String DEFAULT_ENDPOINT = "https://api.radioreference.com/soap2/index.php";
    /** The SOAP2 version the code was written against (18 = "latest" when written; adds TrsSite.tdma_cc). */
    public static final String VERSION = "18";
    public static final String STYLE = "rpc";

    final String endpoint, appKey, username, password, userAgent;
    final HttpClient http;
    /** Requests sent (tests: proves nothing is fetched in the background or twice). */
    public final AtomicInteger requests = new AtomicInteger();
    /** Per-session cache: the same question is never asked twice in one wizard run. */
    final Map<String, Node> cache = new HashMap<>();
    static volatile int timeoutMs = 30_000;
    /**
     * Local cache of the place lists (countries, states, counties and their system lists), which change rarely: kept
     * for 30 days in the settings folder so browsing does not ask RadioReference again. System details, sites and
     * talkgroups are always fetched fresh, and only when the user sets up or refreshes a system.
     */
    Path diskCache;
    public static final long CACHE_DAYS = 30;
    static final Set<String> CACHED = Set.of("getCountryList", "getCountryInfo", "getStateInfo", "getCountyInfo");
    /** Answers taken from the local cache instead of RadioReference (tests). */
    public final AtomicInteger cacheHits = new AtomicInteger();

    public RrSoap cacheIn(Path dir) { diskCache = dir; return this; }

    /** Forgets this session's answers (after "Reload lists"). */
    public void forgetSession() { synchronized (cache) { cache.clear(); } }

    /** Deletes the cached place lists (the wizard's "Reload lists"). */
    public static void clearCache(Path dir) {
        try (var ds = Files.newDirectoryStream(dir, "*.xml")) { for (Path p : ds) Files.deleteIfExists(p); } catch (Exception ignored) {}
    }

    public RrSoap(String appKey, String username, String password, String userAgent) throws RrException {
        if (appKey == null || appKey.isBlank()) throw new RrException(RrException.Kind.NO_KEY, null);
        this.endpoint = System.getProperty("trunkdeck.rr.endpoint", DEFAULT_ENDPOINT);
        this.appKey = appKey.trim(); this.username = username == null ? "" : username.trim(); this.password = password == null ? "" : password;
        this.userAgent = userAgent;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    // ------------------------------------------------------------------ operations (names and parameters as in the WSDL)
    public UserInfo getUserData() throws RrException {
        Node r = call("getUserData");
        return new UserInfo(r.text("username"), r.text("subExpireDate"));
    }

    /**
     * Checks the login: getUserData, then the Premium end date. RadioReference answers a wrong password or a lapsed
     * account with a fault; a returned end date in the past also counts as "not Premium".
     */
    public UserInfo signIn() throws RrException {
        if (username.isEmpty() || password.isEmpty()) throw new RrException(RrException.Kind.LOGIN, "enter both");
        UserInfo u = getUserData();
        java.time.LocalDate end = expiry(u.subExpireDate());
        if (end != null && end.isBefore(java.time.LocalDate.now())) throw new RrException(RrException.Kind.NOT_PREMIUM, "Premium ended " + u.subExpireDate());
        return u;
    }

    /** RR's subExpireDate in the formats seen in the wild; null if it cannot be read (then RR's own check decides). */
    public static java.time.LocalDate expiry(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        for (String f : List.of("yyyy-MM-dd", "MM-dd-yyyy", "MM/dd/yyyy", "yyyy/MM/dd", "M/d/yyyy", "M-d-yyyy")) {
            try { return java.time.LocalDate.parse(t.length() > 10 && t.charAt(4) == '-' ? t.substring(0, 10) : t, java.time.format.DateTimeFormatter.ofPattern(f)); } catch (Exception ignored) {}
        }
        return null;
    }

    public List<Country> getCountryList() throws RrException {
        List<Country> l = new ArrayList<>();
        for (Node n : call("getCountryList").items()) l.add(new Country(n.integer("coid"), n.text("countryName"), n.text("countryCode")));
        return l;
    }

    public List<State> getCountryInfo(int coid) throws RrException {
        List<State> l = new ArrayList<>();
        for (Node n : call("getCountryInfo", "coid", coid).child("stateList").items()) l.add(new State(n.integer("stid"), n.text("stateName"), n.text("stateCode")));
        return l;
    }

    public StateInfo getStateInfo(int stid) throws RrException {
        Node r = call("getStateInfo", "stid", stid);
        List<County> c = new ArrayList<>();
        for (Node n : r.child("countyList").items()) c.add(new County(n.integer("ctid"), n.text("countyName")));
        return new StateInfo(r.integer("stid"), r.text("stateName"), c, trsList(r.child("trsList")));
    }

    public CountyInfo getCountyInfo(int ctid) throws RrException {
        Node r = call("getCountyInfo", "ctid", ctid);
        List<Subcat> subs = new ArrayList<>();
        for (Node cat : r.child("cats").items()) {
            String category = cat.text("cName");
            for (Node sc : cat.child("subcats").items()) {
                int scid = sc.integer("scid");
                if (scid > 0) subs.add(new Subcat(scid, category, sc.text("scName")));
            }
        }
        return new CountyInfo(r.integer("ctid"), r.text("countyName"), r.integer("stid"), trsList(r.child("trsList")), subs);
    }

    public ZipInfo getZipcodeInfo(int zip) throws RrException {
        Node r = call("getZipcodeInfo", "zipcode", zip);
        return new ZipInfo(r.integer("zipCode"), r.text("city"), r.integer("stid"), r.integer("ctid"));
    }

    /** By P25 System ID (hex, e.g. "2D5"). */
    public List<TrsRef> getTrsBySysid(String sysid) throws RrException { return trsList(call("getTrsBySysid", "sysid", sysid.trim())); }

    public TrsDetails getTrsDetails(int sid) throws RrException {
        Node r = call("getTrsDetails", "sid", sid);
        List<Sysid> ids = new ArrayList<>();
        for (Node n : r.child("sysid").items()) ids.add(new Sysid(n.text("sysid"), n.text("wacn")));
        int type = r.integer("sType");
        int flavor = r.integer("sFlavor");
        String typeName = "", flavorName = "";
        try { for (Node n : call("getTrsType", "id", type).items()) if (n.integer("sType") == type) typeName = n.text("sTypeDescr"); }
        catch (RrException e) { if (e.kind != RrException.Kind.FAULT && e.kind != RrException.Kind.BAD_RESPONSE) throw e; }
        try { for (Node n : call("getTrsFlavor", "id", type).items()) if (n.integer("sFlavor") == flavor) flavorName = n.text("sFlavorDescr"); }
        catch (RrException e) { if (e.kind != RrException.Kind.FAULT && e.kind != RrException.Kind.BAD_RESPONSE) throw e; }
        return new TrsDetails(sid, r.text("sName"), type, flavor, r.integer("sVoice"), r.text("sCity"), typeName, flavorName, ids);
    }

    /** Conventional frequencies of one subcategory (getSubcatFreqs). Output frequency only; input is not monitored. */
    public List<Freq> getSubcatFreqs(int scid) throws RrException {
        List<Freq> l = new ArrayList<>();
        for (Node n : call("getSubcatFreqs", "scid", scid).items()) {
            double mhz = n.decimal("out");
            if (mhz > 25 && mhz < 1300) l.add(new Freq(n.integer("fid"), mhz, n.text("alpha"), n.text("descr"), n.text("tone"), n.text("tg"), n.text("mode"), n.integer("enc")));
        }
        return l;
    }

    public List<Site> getTrsSites(int sid) throws RrException {
        List<Site> l = new ArrayList<>();
        for (Node n : call("getTrsSites", "sid", sid).items()) {
            List<SiteFreq> fr = new ArrayList<>();
            for (Node f : n.child("siteFreqs").items()) {
                double mhz = f.decimal("freq");
                if (mhz > 25 && mhz < 1300) fr.add(new SiteFreq(f.integer("lcn"), mhz, f.text("use")));
            }
            fr.sort(Comparator.comparingInt(SiteFreq::lcn).thenComparingDouble(SiteFreq::mhz));
            l.add(new Site(n.integer("siteId"), n.integer("sid"), n.integer("siteNumber"), n.text("siteDescr"), n.integer("rfss"), n.text("nac"),
                    n.text("siteCt"), n.integer("siteCtid"), n.text("siteModulation"), n.integer("tdma_cc"), fr));
        }
        return l;
    }

    public List<TgCat> getTrsTalkgroupCats(int sid) throws RrException {
        List<TgCat> l = new ArrayList<>();
        for (Node n : call("getTrsTalkgroupCats", "sid", sid).items()) l.add(new TgCat(n.integer("tgCid"), n.text("tgCname"), n.integer("tgSort")));
        return l;
    }

    /** All talkgroups of a system (tgCid, tgTag and tgDec 0 = no filter). */
    public List<Tg> getTrsTalkgroups(int sid) throws RrException {
        List<Tg> l = new ArrayList<>();
        for (Node n : call("getTrsTalkgroups", "sid", sid, "tgCid", 0, "tgTag", 0, "tgDec", 0).items()) {
            List<String> tags = new ArrayList<>();
            for (Node t : n.child("tags").items()) { String d = t.text("tagDescr"); if (!d.isBlank()) tags.add(d); }
            l.add(new Tg(n.integer("tgDec"), n.text("tgAlpha"), n.text("tgDescr"), n.text("tgMode"), n.integer("enc"), tags, n.integer("tgCid"), n.integer("tgSort")));
        }
        return l;
    }

    static List<TrsRef> trsList(Node list) {
        List<TrsRef> l = new ArrayList<>();
        for (Node n : list.items()) l.add(new TrsRef(n.integer("sid"), n.text("sName"), n.integer("sType"), n.integer("sFlavor"), n.integer("sVoice"), n.text("sCity")));
        return l;
    }

    // ------------------------------------------------------------------ SOAP
    /** One rpc/encoded call; params are name, value pairs (Integer -> xsd:int, String -> xsd:string). */
    Node call(String op, Object... params) throws RrException {
        StringBuilder key = new StringBuilder(op);
        for (Object p : params) key.append('|').append(p);
        synchronized (cache) { Node c = cache.get(key.toString()); if (c != null) return c; }
        Path cf = diskCache != null && CACHED.contains(op) ? diskCache.resolve(key.toString().replaceAll("[^A-Za-z0-9]+", "_") + ".xml") : null;
        if (cf != null) try {
            if (Files.isRegularFile(cf) && System.currentTimeMillis() - Files.getLastModifiedTime(cf).toMillis() < CACHE_DAYS * 86_400_000L) {
                Node ret = parse(Files.readAllBytes(cf)).child("Body").child(op + "Response").child("return");
                if (!ret.missing()) { cacheHits.incrementAndGet(); synchronized (cache) { cache.put(key.toString(), ret); } return ret; }
            }
        } catch (Exception ignored) {}
        String body = envelope(op, params);
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", "\"" + NS + "#" + op + "\"")
                .header("User-Agent", userAgent)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<byte[]> res;
        requests.incrementAndGet();
        try { res = http.send(req, HttpResponse.BodyHandlers.ofByteArray()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RrException(RrException.Kind.NETWORK, "cancelled"); }
        catch (java.net.http.HttpTimeoutException e) { throw new RrException(RrException.Kind.NETWORK, "no answer within " + (timeoutMs >= 1000 ? timeoutMs / 1000 + " s" : timeoutMs + " ms")); }
        catch (IOException e) { throw new RrException(RrException.Kind.NETWORK, netWhy(e)); }
        Node env;
        try { env = parse(res.body()); }
        catch (Exception e) {
            if (res.statusCode() != 200) throw new RrException(RrException.Kind.NETWORK, "the server answered HTTP " + res.statusCode());
            throw new RrException(RrException.Kind.BAD_RESPONSE, "not SOAP XML");
        }
        Node b = env.child("Body");
        Node fault = b.child("Fault");
        if (!fault.missing()) {
            String code = fault.text("faultcode"), text = fault.text("faultstring");
            throw new RrException(RrException.classify(code, text), text.isBlank() ? code : text);
        }
        if (res.statusCode() != 200) throw new RrException(RrException.Kind.NETWORK, "the server answered HTTP " + res.statusCode());
        Node resp = b.child(op + "Response");
        if (resp.missing()) throw new RrException(RrException.Kind.BAD_RESPONSE, "no " + op + "Response");
        Node ret = resp.child("return");
        if (ret.missing() && !resp.kids.isEmpty()) ret = resp.kids.get(0);
        synchronized (cache) { cache.put(key.toString(), ret); }
        if (cf != null) try { Files.createDirectories(diskCache); Files.write(cf, res.body()); } catch (Exception ignored) {}
        return ret;
    }

    static String netWhy(IOException e) {
        Throwable t = e; while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String m = t.getMessage();
        if (t instanceof java.net.UnknownHostException) return "the address api.radioreference.com could not be looked up";
        if (t instanceof java.net.ConnectException) return "the connection was refused";
        if (t instanceof javax.net.ssl.SSLException) return "secure connection failed: " + m;
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    String envelope(String op, Object... params) {
        StringBuilder s = new StringBuilder(1024);
        s.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
         .append("xmlns:ns1=\"").append(NS).append("\" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
         .append("xmlns:SOAP-ENC=\"http://schemas.xmlsoap.org/soap/encoding/\" SOAP-ENV:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
         .append("<SOAP-ENV:Body><ns1:").append(op).append('>');
        for (int i = 0; i + 1 < params.length; i += 2) {
            String n = (String) params[i]; Object v = params[i + 1];
            s.append('<').append(n).append(" xsi:type=\"xsd:").append(v instanceof Integer ? "int" : "string").append("\">").append(esc(String.valueOf(v))).append("</").append(n).append('>');
        }
        if (!op.equals("getCountryList")) {   // the only operation without authInfo in the WSDL
            s.append("<authInfo xsi:type=\"ns1:authInfo\">")
             .append("<username xsi:type=\"xsd:string\">").append(esc(username)).append("</username>")
             .append("<password xsi:type=\"xsd:string\">").append(esc(password)).append("</password>")
             .append("<appKey xsi:type=\"xsd:string\">").append(esc(appKey)).append("</appKey>")
             .append("<version xsi:type=\"xsd:string\">").append(VERSION).append("</version>")
             .append("<style xsi:type=\"xsd:string\">").append(STYLE).append("</style>")
             .append("</authInfo>");
        }
        s.append("</ns1:").append(op).append("></SOAP-ENV:Body></SOAP-ENV:Envelope>");
        return s.toString();
    }

    static String esc(String v) {
        StringBuilder b = new StringBuilder(v.length() + 8);
        for (char c : v.toCharArray()) switch (c) {
            case '&' -> b.append("&amp;"); case '<' -> b.append("&lt;"); case '>' -> b.append("&gt;");
            case '"' -> b.append("&quot;"); case '\'' -> b.append("&apos;");
            default -> { if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') b.append(c); }
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ a tolerant view of the SOAP answer
    /** An element by local name; SOAP-ENC href/id references resolved; arrays are any child elements ("item"). */
    public static final class Node {
        static final Node NONE = new Node("", "", List.of());
        final String name, value; final List<Node> kids;
        Node(String name, String value, List<Node> kids) { this.name = name; this.value = value; this.kids = kids; }
        public boolean missing() { return this == NONE; }
        public Node child(String n) { for (Node k : kids) if (k.name.equals(n)) return k; return NONE; }
        public List<Node> items() { return kids; }
        public String text(String n) { Node c = child(n); return c.missing() ? "" : c.value.strip(); }
        public int integer(String n) { try { return (int) Math.round(Double.parseDouble(text(n))); } catch (Exception e) { return 0; } }
        public double decimal(String n) { try { return Double.parseDouble(text(n)); } catch (Exception e) { return 0; } }
    }

    static Node parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);   // no DTDs / external entities
        f.setXIncludeAware(false); f.setExpandEntityReferences(false);
        Document d = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        Map<String, Element> ids = new HashMap<>();
        NodeList all = d.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) { Element e = (Element) all.item(i); if (e.hasAttribute("id")) ids.put(e.getAttribute("id"), e); }
        return node(d.getDocumentElement(), ids, 0);
    }

    static Node node(Element e, Map<String, Element> ids, int depth) {
        String href = e.getAttribute("href");
        Element src = e;
        if (href.startsWith("#") && ids.containsKey(href.substring(1)) && depth < 40) src = ids.get(href.substring(1));
        List<Node> kids = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        for (org.w3c.dom.Node c = src.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element ce) { if (depth < 40) kids.add(node(ce, ids, depth + 1)); }
            else if (c.getNodeType() == org.w3c.dom.Node.TEXT_NODE || c.getNodeType() == org.w3c.dom.Node.CDATA_SECTION_NODE) text.append(c.getNodeValue());
        }
        String ln = e.getLocalName() != null ? e.getLocalName() : e.getNodeName();
        return new Node(ln, text.toString(), kids);
    }
}
