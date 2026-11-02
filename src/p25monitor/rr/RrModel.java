package p25monitor.rr;

import java.util.List;

/**
 * The RadioReference Database Web Service (SOAP2) data TrunkDeck uses, named after the WSDL types
 * (https://api.radioreference.com/soap2/?wsdl&v=latest; a copy of the version this was written against is in
 * test/p25monitor/rr/radioreference-soap2.wsdl). Only the fields TrunkDeck needs are kept.
 */
public final class RrModel {
    private RrModel() {}

    /** UserInfo: the signed-in member and when their Premium subscription ends (as RR sends it). */
    public record UserInfo(String username, String subExpireDate) {}
    /** Country (getCountryList) / State (CountryInfo.stateList) / County (StateInfo.countyList). */
    public record Country(int coid, String name, String code) { @Override public String toString() { return name; } }
    public record State(int stid, String name, String code) { @Override public String toString() { return name; } }
    public record County(int ctid, String name) { @Override public String toString() { return name; } }
    /** TrsListDef: a trunked system in a state or county list. */
    public record TrsRef(int sid, String name, int sType, int sFlavor, int sVoice, String city) { @Override public String toString() { return name; } }
    /** StateInfo: its counties and statewide trunked systems. */
    public record StateInfo(int stid, String name, List<County> counties, List<TrsRef> systems) {}
    /** CountyInfo: its trunked systems. */
    public record CountyInfo(int ctid, String name, int stid, List<TrsRef> systems) {}
    /** ZipInfo. */
    public record ZipInfo(int zip, String city, int stid, int ctid) {}
    /** trsSysidDef. */
    public record Sysid(String sysid, String wacn) {}
    /** Trs (getTrsDetails) plus the type description (getTrsType). */
    public record TrsDetails(int sid, String name, int sType, int sFlavor, int sVoice, String city, String typeName, List<Sysid> sysids) {}
    /** TrsSiteFreq: use "d" = primary control channel, "a" = alternate control channel, "" = voice. */
    public record SiteFreq(int lcn, double mhz, String use) {
        public boolean control() { return use != null && (use.trim().equalsIgnoreCase("d") || use.trim().equalsIgnoreCase("a")); }
        public boolean primary() { return use != null && use.trim().equalsIgnoreCase("d"); }
    }
    /** TrsSite. */
    public record Site(int siteId, int sid, int siteNumber, String descr, int rfss, String nac, String county, int ctid,
                       String modulation, int tdmaCc, List<SiteFreq> freqs) {
        /** "1-005" (RFSS-site), as RadioReference shows it. */
        public String label() { return rfss + "-" + String.format("%03d", siteNumber); }
        /** Control channels: primary ("d") first, then alternates ("a"), each in LCN order. Empty if none is marked. */
        public List<Double> controlChannels() {
            java.util.List<Double> l = new java.util.ArrayList<>();
            for (SiteFreq f : freqs) if (f.primary() && !l.contains(f.mhz())) l.add(f.mhz());
            for (SiteFreq f : freqs) if (f.control() && !l.contains(f.mhz())) l.add(f.mhz());
            return l;
        }
    }
    /** TalkgroupCat. */
    public record TgCat(int tgCid, String name, int sort) {}
    /** Talkgroup: enc 0 = clear, 1 = sometimes encrypted, 2 = always encrypted; tags = service tag descriptions. */
    public record Tg(int dec, String alpha, String descr, String mode, int enc, List<String> tags, int tgCid, int sort) {
        public boolean encrypted() { return enc >= 2 || (mode != null && mode.trim().endsWith("E")); }
        public boolean sometimesEncrypted() { return !encrypted() && (enc == 1 || (mode != null && mode.trim().endsWith("e"))); }
    }
}
