/*
 * EDACS control channel message decoding: a Java translation of the message parsing in DSD-FME's edacs-fme.c
 * (https://github.com/lwvmobile/dsd-fme, commit dad6d6c3).
 *
 * EDACS-FME: A program for decoding EDACS (ported to DSD-FME), https://github.com/lwvmobile/edacs-fm
 * Portions originally from https://github.com/sp5wwp/ledacs (XTAL Labs, 30 IV 2016).
 * LWVMOBILE 2023-11 Version EDACS-FM Florida Man Edition.
 * ilyacodes 2024-03 rewrite EDACS standard parsing to spec, add reverse-engineered EA messages.
 *
 * DSD-FME licence for this code:
 *
 *   Copyright (C) 2010 DSD Author
 *   GPG Key ID: 0x3F1D7FD0 (74EF 430D F7F2 0A48 FCE6  F630 FAA2 635D 3F1D 7FD0)
 *
 *   Permission to use, copy, modify, and/or distribute this software for any
 *   purpose with or without fee is hereby granted, provided that the above
 *   copyright notice and this permission notice appear in all copies.
 *
 *   THE SOFTWARE IS PROVIDED "AS IS" AND ISC DISCLAIMS ALL WARRANTIES WITH
 *   REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY
 *   AND FITNESS.  IN NO EVENT SHALL ISC BE LIABLE FOR ANY SPECIAL, DIRECT,
 *   INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM
 *   LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE
 *   OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR
 *   PERFORMANCE OF THIS SOFTWARE.
 *
 * Changes for TrunkDeck (2026), as part of TrunkDeck (GPL-3.0-or-later): translated from C to Java; the terminal
 * printing, call tuning, audio and event-history code was replaced by the Listener below (group calls go to grant(),
 * every message to message() as text); the frame voting and BCH check are done before this class by TrunkDeck's own
 * EdacsDemod and EdacsBch (DSD-FME's BCH file is not used); a message whose second word failed its check is still
 * read where only the first word is needed (a group call then has no talking radio).
 */
package p25monitor.edacs;

import java.util.Locale;
import java.util.function.IntToLongFunction;

/**
 * Turns EDACS control channel messages into call grants and site facts. Each frame carries two 28-bit words
 * ({@code m1}, {@code m2}, -1 where one failed its check). LCN 1 to 25 are voice channels numbered as in the site's
 * frequency list; 26 to 31 are status codes (system busy, call queued, denied...). Standard / networked addressing
 * reads message types MT-A, MT-B and MT-D; extended addressing (EA) reads MT1 and MT2 (US patent US7546135B2). With ESK
 * both words are XORed with 0xA0 in bits 27-20 first.
 */
public final class EdacsSite {
    public interface Listener {
        /** A group call (or its update): {@code digital} = ProVoice, else analog FM. {@code hz} 0 = LCN not in the list. */
        void grant(int group, int src, int lcn, long hz, boolean digital, boolean emergency, boolean update);
        /** Every decoded message, described for the message log. */
        void message(String text);
    }

    public enum Mode {
        STANDARD("std", "Standard / networked"), STANDARD_ESK("std-esk", "Standard with ESK"),
        EXTENDED("ea", "Extended addressing (EA)"), EXTENDED_ESK("ea-esk", "EA with ESK");
        public final String key, label;
        Mode(String key, String label) { this.key = key; this.label = label; }
        public boolean ea() { return this == EXTENDED || this == EXTENDED_ESK; }
        public boolean esk() { return this == STANDARD_ESK || this == EXTENDED_ESK; }
        public static Mode of(String k) { for (Mode m : values()) if (m.key.equalsIgnoreCase(k == null ? "" : k.trim())) return m; return STANDARD; }
        @Override public String toString() { return label; }
    }

    public static final int ESK = 0xA0 << 20;
    /** Agency / fleet / subfleet split of an 11-bit standard group (the usual 4:4:3). */
    static final int A_BITS = 4, F_BITS = 4, S_BITS = 3;
    static final int F_SHIFT = S_BITS, A_SHIFT = F_BITS + S_BITS;
    static final int A_MASK = (1 << A_BITS) - 1, F_MASK = (1 << F_BITS) - 1, S_MASK = (1 << S_BITS) - 1;

    private final Listener out;
    private final Mode mode;
    private final IntToLongFunction lcnHz;
    public int siteId = -1, ccLcn = -1, systemId = -1, areaCode = -1;
    public long grants, digitalGrants;

    public EdacsSite(Mode mode, IntToLongFunction lcnHz, Listener out) { this.mode = mode; this.lcnHz = lcnHz; this.out = out; }

    public Mode mode() { return mode; }

    public void frame(int m1, int m2) {
        if (m1 < 0) return;                     // the first word failed its check
        if (mode.esk()) { m1 ^= ESK; if (m2 >= 0) m2 ^= ESK; }
        if (mode.ea()) extended(m1, m2); else standard(m1, m2);
    }

    // ------------------------------------------------------------------ extended addressing
    void extended(int msg1, int msg2) {
        int mt1 = (msg1 & 0xF800000) >>> 23;
        int mt2 = (msg1 & 0x780000) >>> 19;
        boolean two = msg2 >= 0;
        int m2 = two ? msg2 : 0;

        // MT1 of 0x1F: MT2 is the opcode (US7546135B2, figure 2b)
        if (mt1 == 0x1F) {
            if (mt2 == 0x0) {
                int ccLcn = (msg1 & 0x3E000) >>> 13, wcLcn = (msg1 & 0xF80) >>> 7;
                say("Initiate Test Call :: CC LCN [%02d] WC LCN [%02d]", ccLcn, wcLcn);
            } else if (mt2 == 0x1) {
                int adjLcn = (msg1 & 0x1F000) >>> 12, adjIdx = (msg1 & 0xF00) >>> 8, adjSite = msg1 & 0xFF;
                out.message((adjSite > 0
                        ? f("Adjacent Site :: Site ID [%02X][%03d] Index [%d] on CC LCN [%02d]%s", adjSite, adjSite, adjIdx, adjLcn, lcnStatus(adjLcn))
                        : f("Adjacent Site :: Total Indexed [%d]", adjIdx)) + adjacency(adjSite, adjIdx));
            } else if (mt2 == 0x4) {
                if (!two) return;
                int status = msg1 & 0xFF, source = m2 & 0xFFFFF;
                if (status == 248) say("Status Request :: Target [%08d]", source);
                else say("Message Acknowledgement :: Status [%03d] Source [%08d]", status, source);
            } else if (mt2 == 0x7) {
                if (!two) return;
                int qualifier = (m2 & 0xC000000) >>> 26, target = m2 & 0xFFFFF;
                say("Unit Enable/Disable :: %s Target [%08d]", enableQualifier(qualifier), target);
            } else if (mt2 == 0x8) {
                if (!two) return;
                int system = msg1 & 0xFFFF, lcn = m2 & 0x1F;
                if (lcn == 0) { say("System Information"); return; }
                ccLcn = lcn;
                if (system != 0) systemId = system;
                say("System Information :: System ID [%04X] CC LCN [%02d]%s%s", system, lcn, lcnStatus(lcn), mhz(lcnHz.applyAsLong(lcn)));
            } else if (mt2 == 0xA) {
                int site = ((msg1 & 0x7000) >>> 7) | (msg1 & 0x1F);
                int area = (msg1 & 0xFE0) >>> 5;
                siteId = site; areaCode = area;
                say("Extended Addressing :: Site ID [%02X][%03d] Area [%02X][%03d]", site, site, area, area);
            } else if (mt2 == 0xB) {
                if (!two) return;
                out.message("System Dynamic Regroup Plan Bitmap"
                        + planBank((msg1 & 0x10000) >>> 16, (msg1 & 0xFF00) >>> 8, msg1 & 0xFF)
                        + planBank((m2 & 0x10000) >>> 16, (m2 & 0xFF00) >>> 8, m2 & 0xFF));
            } else if (mt2 == 0xC) {
                if (!two) return;
                int tga = (msg1 & 0x70000) >>> 16;     // unknown 3-bit value before the SGID
                int unk1 = (msg1 & 0xFF00) >>> 8;
                int sgid = msg1 & 0xFF;                // patched supergroup ID
                int ssn = (m2 & 0xF800000) >>> 23;
                int unk2 = (m2 & 0x700000) >>> 20;
                int target = m2 & 0xFFFFF;             // group or radio in the supergroup
                int unk3 = (msg1 & 0x7FF00) >>> 8, unk4 = (m2 & 0x7FF00) >>> 8;
                StringBuilder sb = new StringBuilder(f("System Dynamic Regroup :: SP-WGID: %03d; Target: %07d;", sgid, target));
                if (sgid != target) {
                    sb.append(" Patch").append((tga & 1) != 0 ? " Active;" : " Delete;").append(f(" OPT: %01X;", tga));
                    if (unk1 != 0) sb.append(f(" UNK1: %01X;", unk1));
                    if (unk2 != 0) sb.append(f(" UNK2: %02X;", unk2));
                    sb.append(f(" SSN: %02X;", ssn));
                } else {
                    sb.append(" Patch Delete;");
                    if (unk3 != 0) sb.append(f(" UNK3: %01X;", unk3));
                    if (unk4 != 0) sb.append(f(" UNK4: %02X;", unk4));
                }
                out.message(sb.toString());
            } else if (mt2 == 0xD) {
                say("Serial Number Request");
            } else unknown(msg1, msg2);
        } else if (mt1 == 0x1) {
            if (!two) return;
            int lcn = (msg1 & 0x3E0000) >>> 17, group = msg1 & 0xFFFF, source = m2 & 0xFFFFF;
            say("TDMA Group Call :: Group [%05d] Source [%08d] LCN [%02d]%s", group, source, lcn, lcnStatus(lcn));
        } else if (mt1 == 0x2) {
            if (!two) return;
            int lcn = (msg1 & 0x3E0000) >>> 17, group = msg1 & 0xFFFF, source = m2 & 0xFFFFF;
            say("Data Group Call :: Group [%05d] Source [%08d] LCN [%02d]%s", group, source, lcn, lcnStatus(lcn));
        } else if (mt1 == 0x3 || mt1 == 0x6) {
            int lcn = (msg1 & 0x3E0000) >>> 17;
            boolean digital = mt1 == 0x3;
            boolean update = (msg1 & 0x10000) != 0;
            int group = msg1 & 0xFFFF;
            boolean txTrunking = (m2 & 0x200000) != 0;
            boolean emergency = (m2 & 0x100000) != 0;
            int source = two ? m2 & 0xFFFFF : -1;
            String text = f("%s Group Call %s :: Group [%05d] Source [%s] LCN [%02d]%s", digital ? "Digital" : "Analog",
                    update ? "Update" : "Assignment", group, source < 0 ? "?" : f("%08d", source), lcn, lcnStatus(lcn))
                    + (two && !txTrunking ? " [Message Trunking]" : "") + (emergency ? " [EMERGENCY]" : "");
            grant(group, source, lcn, digital, emergency, update, text);
        } else if (mt1 == 0x10) {
            if (!two) return;
            int lcn = (m2 & 0x1F00000) >>> 20;
            boolean digital = (msg1 & 0x200000) != 0, update = (msg1 & 0x100000) != 0;
            int target = msg1 & 0xFFFFF, source = m2 & 0xFFFFF;
            if (target == 0 && source == 0)
                say("Test Call %s :: LCN [%02d]%s", update ? "Update" : "Assignment", lcn, lcnStatus(lcn));
            else
                say("%s I-Call %s :: Target [%08d] Source [%08d] LCN [%02d]%s", digital ? "Digital" : "Analog",
                        update ? "Update" : "Assignment", target, source, lcn, lcnStatus(lcn));
        } else if (mt1 == 0x12) {
            if (!two) return;
            int lcn = (m2 & 0x1F00000) >>> 20, source = m2 & 0xFFFFF;
            say("Channel Assignment (Unknown Data) :: Source [%08d] LCN [%02d]%s", source, lcn, lcnStatus(lcn));
        } else if (mt1 == 0x16) {
            int lcn = (msg1 & 0x3E0000) >>> 17;
            boolean digital = (msg1 & 0x10000) != 0, update = (msg1 & 0x8000) != 0;
            String source = two ? f("%08d", m2 & 0xFFFFF) : "?";
            say("%s System All-Call %s :: Source [%s] LCN [%02d]%s", digital ? "Digital" : "Analog", update ? "Update" : "Assignment", source, lcn, lcnStatus(lcn));
        } else if (mt1 == 0x19) {
            if (!two) return;
            say("Login :: Group [%05d] Source [%08d]", msg1 & 0xFFFF, m2 & 0xFFFFF);
        } else unknown(msg1, msg2);
    }

    // ------------------------------------------------------------------ standard / networked
    void standard(int msg1, int msg2) {
        int mtA = (msg1 & 0xE000000) >>> 25;
        int mtB = (msg1 & 0x1C00000) >>> 22;
        int mtD = (msg1 & 0x3E0000) >>> 17;
        boolean two = msg2 >= 0;
        int m2 = two ? msg2 : 0;

        if (mtA <= 0x3) {
            boolean digital = mtA == 0x2 || mtA == 0x3;
            boolean emergency = mtA == 0x1 || mtA == 0x3;
            int lid = two ? ((msg1 & 0x1FC0000) >>> 11) | ((m2 & 0xFE0000) >>> 17) : -1;
            int lcn = (msg1 & 0x1F000) >>> 12;
            boolean txTrunk = (msg1 & 0x800) != 0;
            int group = msg1 & 0x7FF;
            String text = f("Voice Group Channel Assignment :: %s Group [%04d] AFS [%s] LID [%s] LCN [%02d]%s", digital ? "Digital" : "Analog",
                    group, afs(group), lid < 0 ? "?" : f("%05d", lid), lcn, lcnStatus(lcn))
                    + groupKind(group) + (txTrunk ? "" : " [Message Trunking]") + (emergency ? " [EMERGENCY]" : "");
            grant(group, lid, lcn, digital, emergency, false, text);
        } else if (mtA == 0x5) {
            if (!two) return;
            boolean individualCall = (msg1 & 0x1000000) != 0, fromLid = (msg1 & 0x800000) != 0;
            int port = ((msg1 & 0x700000) >>> 17) | ((m2 & 0x700000) >>> 20);
            int lcn = (msg1 & 0xF8000) >>> 15;
            boolean individualId = (msg1 & 0x4000) != 0;
            int target = individualId ? msg1 & 0x3FFF : msg1 & 0x7FF;
            say("Data Call Channel Assignment :: Type %s %s %s Port [%02d] LCN [%02d]%s", individualCall ? "[Individual]" : "[Group]",
                    individualId ? f("LID [%05d]", target) : f("Group [%04d]", target), fromLid ? "-->" : "<--", port, lcn, lcnStatus(lcn));
        } else if (mtA == 0x6) {
            say("Login Acknowledgement :: Group [%04d] LID [%05d]", (msg1 & 0x1FFC000) >>> 14, msg1 & 0x3FFF);
        } else if (mtA == 0x7) {
            if (mtB == 0x0) {
                int status = (msg1 & 0x3FC000) >>> 14, lid = msg1 & 0x3FFF;
                if (status == 248) say("Status Request :: LID [%05d]", lid);
                else say("Message Acknowledgement :: Status [%03d] LID [%05d]", status, lid);
            } else if (mtB == 0x1) {
                int mtC = (msg1 & 0x300000) >>> 20, lcn = (msg1 & 0xF8000) >>> 15;
                boolean individualId = (msg1 & 0x4000) != 0;
                int target = individualId ? msg1 & 0x3FFF : msg1 & 0x7FF;
                boolean digital = mtC == 2 || mtC == 3;
                say("Interconnect Channel Assignment :: Type %s %s LCN [%02d]%s", digital ? "Digital" : "Analog",
                        individualId ? f("LID [%05d]", target) : f("Group [%04d]", target), lcn, lcnStatus(lcn));
            } else if (mtB == 0x3) {
                int mtC = (msg1 & 0x300000) >>> 20, lcn = (msg1 & 0xF8000) >>> 15;
                boolean individual = (msg1 & 0x4000) != 0;
                boolean emergency = !individual && (msg1 & 0x2000) != 0;
                int group = msg1 & 0x7FF, lid = msg1 & 0x3FFF;
                int source = m2 & 0x3FFF;           // only in individual calls
                int target = individual ? lid : group;
                boolean testCall = target == 0 && source == 0;
                boolean txTrunk = mtC == 2 || mtC == 3, digital = mtC == 1 || mtC == 3;
                if (individual && !two) return;
                String text = (!individual ? "Voice Group Channel Update ::" : !testCall ? "Voice Individual Channel Update ::" : "Voice Test Channel Update ::")
                        + (digital ? " Digital" : " Analog")
                        + (!individual ? f(" Group [%04d] AFS [%s]", target, afs(target)) : !testCall ? f(" Callee [%05d] Caller [%05d]", target, source) : "")
                        + f(" LCN [%02d]%s", lcn, lcnStatus(lcn))
                        + (individual ? "" : groupKind(group)) + (txTrunk ? "" : " [Message Trunking]") + (emergency ? " [EMERGENCY]" : "");
                if (!individual) grant(group, -1, lcn, digital, emergency, true, text);
                else out.message(text);
            } else if (mtB == 0x4) {
                say("System Assigned ID :: SGID [%04d] Group [%04d]", (msg1 & 0x3FF800) >>> 11, msg1 & 0x7FF);
            } else if (mtB == 0x5) {
                if (!two) return;
                boolean txTrunk = (msg1 & 0x200000) != 0;
                int lcn = (msg1 & 0xF8000) >>> 15;
                boolean digital = (msg1 & 0x4000) != 0;
                int target = msg1 & 0x3FFF, source = m2 & 0x3FFF;
                if (target == 0 && source == 0) say("Test Call Channel Assignment :: LCN [%02d]%s", lcn, lcnStatus(lcn));
                else out.message(f("Voice Individual Channel Assignment :: %s Callee [%05d] Caller [%05d] LCN [%02d]%s", digital ? "Digital" : "Analog",
                        target, source, lcn, lcnStatus(lcn)) + (txTrunk ? "" : " [Message Trunking]"));
            } else if (mtB == 0x6) {
                boolean drop = (msg1 & 0x80000) != 0;
                int lcn = (msg1 & 0x7C000) >>> 14, lid = msg1 & 0x3FFF;
                say("Console %s :: LID [%05d] LCN [%02d]%s", drop ? "Drop" : "Unkey", lid, lcn, lcnStatus(lcn));
            } else if (mtB == 0x7) {
                siteMessage(mtD, msg1, msg2, two, m2);
            } else {
                out.message("Reserved Command (MT-B) :: " + raw(msg1, msg2));
            }
        } else {
            out.message("Reserved Command (MT-A) :: " + raw(msg1, msg2));
        }
    }

    /** Standard MT-A 7, MT-B 7: the MT-D site and system messages. */
    private void siteMessage(int mtD, int msg1, int msg2, boolean two, int m2) {
        if (mtD == 0x00) {
            int knob = (msg1 & 0x1C000) >>> 14, lid = msg1 & 0x3FFF;
            say("Cancel Dynamic Regroup :: LID [%05d] Knob position [%1d]", lid, knob + 1);
        } else if (mtD == 0x01) {
            int adjCcLcn = (msg1 & 0x1F000) >>> 12, adjIndex = (msg1 & 0xE00) >>> 9, adjSite = (msg1 & 0x1F0) >>> 4;
            out.message(f("Adjacent Site Control Channel :: Site ID [%02X][%03d] Index [%1d] LCN [%02d]%s", adjSite, adjSite, adjIndex, adjCcLcn,
                    lcnStatus(adjCcLcn)) + adjacency(adjSite, adjIndex));
        } else if (mtD == 0x02) {
            say("Extended Site Options :: Message Num [%1d] Data [%04X]", (msg1 & 0xE000) >>> 13, msg1 & 0x1FFF);
        } else if (mtD == 0x04) {
            out.message("System Dynamic Regroup Plan Bitmap" + planBank((msg1 & 0x10000) >>> 16, (msg1 & 0xFF00) >>> 8, msg1 & 0xFF));
        } else if (mtD == 0x05) {
            int auxCcLcn = (msg1 & 0x1F000) >>> 12, group = msg1 & 0x7FF;
            say("Assignment to Auxiliary CC :: Group [%04d] Aux CC LCN [%02d]%s", group, auxCcLcn, lcnStatus(auxCcLcn));
        } else if (mtD == 0x06) {
            say("Initiate Test Call Command :: CC LCN [%02d] WC LCN [%02d]", (msg1 & 0x1F000) >>> 12, (msg1 & 0xF80) >>> 7);
        } else if (mtD == 0x07) {
            say("Unit Enable/Disable :: %s LID [%05d]", enableQualifier((msg1 & 0xC000) >>> 14), msg1 & 0x3FFF);
        } else if (mtD >= 0x08 && mtD <= 0x0B) {
            int cc = (msg1 & 0x1F000) >>> 12, priority = (msg1 & 0xE00) >>> 9;
            boolean scat = (msg1 & 0x80) != 0, failsoft = (msg1 & 0x40) != 0, auxiliary = (msg1 & 0x20) != 0;
            int site = msg1 & 0x1F;
            siteId = site;
            if (!auxiliary) ccLcn = cc;
            out.message(f("Standard/Networked :: Site ID [%02X][%03d] Priority [%1d] CC LCN [%02d]%s%s", site, site, priority, cc, lcnStatus(cc),
                    mhz(lcnHz.applyAsLong(cc))) + (failsoft ? " [FAILSOFT]" : "") + (scat ? " [SCAT]" : "") + (auxiliary ? " [Auxiliary]" : ""));
        } else if (mtD == 0x0F) {
            int lcn = (msg1 & 0x1F000) >>> 12;
            boolean digital = (msg1 & 0x800) != 0, update = (msg1 & 0x400) != 0, txTrunk = (msg1 & 0x200) != 0;
            String lid = two ? f("%05d", (msg1 & 0x7F) | ((m2 & 0xFE) << 6)) : "?";
            out.message(f("System All-Call Channel %s :: %s LID [%s] LCN [%02d]%s%s", update ? "Update" : "Assignment", digital ? "Digital" : "Analog",
                    lid, lcn, lcnStatus(lcn), mhz(lcnHz.applyAsLong(lcn))) + (txTrunk ? "" : " [Message Trunking]"));
        } else if (mtD == 0x10) {
            if (!two) return;
            int fleetBits = (msg1 & 0x1C000) >>> 14, lid = msg1 & 0x3FFF;
            int plan = (m2 & 0x1E0000) >>> 17, type = (m2 & 0x18000) >>> 15, knob = (m2 & 0x7000) >>> 12, group = m2 & 0x7FF;
            say("Dynamic Regrouping :: Plan [%02d] Knob position [%1d] LID [%05d] Group [%04d] Fleet bits [%1d] %s", plan, knob + 1, lid, group, fleetBits,
                    switch (type) { case 0 -> "[Forced select, no deselect]"; case 1 -> "[Forced select, optional deselect]"; case 2 -> "[Reserved]"; default -> "[Optional select]"; });
        } else {
            out.message("Reserved Command (MT-D) :: " + raw(msg1, msg2));
        }
    }

    // ------------------------------------------------------------------ helpers
    private void grant(int group, int src, int lcn, boolean digital, boolean emergency, boolean update, String text) {
        if (lcn < 1 || lcn > 25) { out.message(text); return; }
        long hz = lcnHz.applyAsLong(lcn);
        if (!update) { grants++; if (digital) digitalGrants++; }
        out.grant(group, src, lcn, hz, digital, emergency, update);
        out.message(text + (hz > 0 ? mhz(hz) : " (not in the channel list)"));
    }

    private void unknown(int msg1, int msg2) { out.message("Unknown Command :: " + raw(msg1, msg2)); }

    private void say(String format, Object... args) { out.message(f(format, args)); }

    private static String f(String format, Object... args) { return String.format(Locale.ROOT, format, args); }

    static String raw(int msg1, int msg2) { return f("MSG_1 [%07X] MSG_2 [%s]", msg1, msg2 < 0 ? "bad" : f("%07X", msg2)); }

    /** The LCN status names (LCN 26 to 31 are not channels). */
    static String lcnStatus(int lcn) {
        return switch (lcn) {
            case 26, 27 -> " [Reserved LCN Status]";
            case 28 -> " [Convert To Callee]";
            case 29 -> " [Call Queued]";
            case 30 -> " [System Busy]";
            case 31 -> " [Call Denied]";
            default -> "";
        };
    }

    /** A standard group as agency-fleet/subfleet, e.g. 05-123. */
    static String afs(int group) {
        return f("%02d-%02d%01d", (group >>> A_SHIFT) & A_MASK, (group >>> F_SHIFT) & F_MASK, group & S_MASK);
    }

    /** A call to a whole agency (fleet and subfleet 0) or a whole fleet (subfleet 0). */
    static String groupKind(int group) {
        if ((group & (S_MASK | F_MASK << F_SHIFT)) == 0) return " [Agency]";
        if ((group & S_MASK) == 0) return " [Fleet]";
        return "";
    }

    private static String adjacency(int site, int index) {
        if (site == 0 && index == 0) return " [Adjacency Table Reset]";
        if (site != 0 && index == 0) return " [Priority System Definition]";
        if (site == 0) return " [Adjacencies Table Length Definition]";
        return " [Adjacent System Definition]";
    }

    private static String enableQualifier(int q) {
        return switch (q) { case 0 -> "[Temporary Disable]"; case 1 -> "[Corrupt Personality]"; case 2 -> "[Revoke Logical ID]"; default -> "[Re-enable Unit]"; };
    }

    private static String planBank(int bank, int resident, int active) {
        return " :: Plan Bank [" + bank + "] Resident [" + plans(bank, resident) + "] Active [" + plans(bank, active) + "]";
    }

    private static String plans(int bank, int bits) {
        StringBuilder sb = new StringBuilder();
        for (int plan = bank * 8; bits != 0; bits >>>= 1, plan++)
            if ((bits & 1) != 0) sb.append(sb.length() == 0 ? "" : ", ").append(plan);
        return sb.toString();
    }

    static String mhz(long hz) { return hz <= 0 ? "" : f(" %.4f MHz", hz / 1e6); }
}
