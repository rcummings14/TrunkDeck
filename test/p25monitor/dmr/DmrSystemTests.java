package p25monitor.dmr;

import java.util.*;

/**
 * DMR (1.7.6): Tier III channel grants in the TS 102 361-4 layout, private / data grants, two-block MBC grants with
 * absolute channel parameters (and the LPCN they teach), P_CLEAR, the privacy (PI) header and the emergency bit.
 * Every stream goes through the 4FSK modulator and the real receiver. Untested on air.
 */
public final class DmrSystemTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    static final int CC = DmrSelfCheck.CC;
    static final long BASE = 450_000_000L, CONTROL = 450_012_500L;

    public static void main(String[] a) {
        try {
            layout();
            controlChannel();
            absoluteRefused();
            privacyAndEmergency();
            clear();
        } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("DmrSystemTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ helpers
    static final class Rec implements DmrReceiver.Listener {
        final List<DmrTrunk.Grant> grants = new ArrayList<>();
        final List<String> control = new ArrayList<>();
        int encrypted, emergency, ends, audio, controlErrors;
        public void group(long hz, int slot, int cc, int tg, int src, byte[][] ambe) { audio += ambe.length; }
        public void unit(long hz, int slot, int cc, int t, int s) {}
        public void encrypted(long hz, int slot, int cc) { encrypted++; }
        public void end(long hz, int slot) { ends++; }
        public void grant(DmrTrunk.Grant g) { grants.add(g); }
        public void rest(int r, int s, long h, boolean m) {}
        public void alias(int src, String text) {}
        public void control(String text) { control.add(text); }
        public void controlError() { controlErrors++; }
        public void emergency(long hz, int slot) { emergency++; }
    }

    static boolean[] data(int type, boolean[] info) { return DmrBurst.build(DmrSync.Kind.BS_DATA, CC, type, info); }
    static boolean[] idle() { return data(DmrBurst.IDLE, null); }
    static boolean[] csbk(int opcode, boolean[] d) { return data(DmrBurst.CSBK, DmrBurst.csbk(opcode, 0, d)); }

    /** Slot 1 carries {@code s1}, slot 2 {@code s2} (idle when shorter), each burst after its CACH, through the 4FSK modulator. */
    static Rec run(DmrReceiver.Listener extra, DmrTrunk trunk, long hz, List<boolean[]> s1, List<boolean[]> s2) {
        Rec rec = extra instanceof Rec r ? r : new Rec();
        List<Integer> dib = new ArrayList<>();
        Random r = new Random(9);
        for (int k = 0; k < 300; k++) dib.add(r.nextInt(4));
        List<boolean[]> a = new ArrayList<>(), b = new ArrayList<>();
        for (int k = 0; k < 4; k++) { a.add(idle()); b.add(idle()); }   // let the framer lock first
        a.addAll(s1); b.addAll(s2);
        int units = Math.max(a.size(), b.size()) * 2 + 8;
        int p1 = 0, p2 = 0;
        for (int u = 0; u < units; u++) {
            int slot = u % 2 == 0 ? 1 : 2;
            boolean[] burst = slot == 1 ? (p1 < a.size() ? a.get(p1++) : idle()) : (p2 < b.size() ? b.get(p2++) : idle());
            for (int d : Fsk4Demod.dibitsOf(DmrCach.encode(slot))) dib.add(d);
            for (int d : Fsk4Demod.dibitsOf(burst)) dib.add(d);
        }
        int[] d = dib.stream().mapToInt(Integer::intValue).toArray();
        float[][] iq = DmrSelfCheck.modulate(d, 1f, 0f, false, 0, false);
        DmrReceiver rx = new DmrReceiver(hz, CC, 0, trunk, BASE, List.of(CONTROL), rec);
        for (int pos = 0; pos < iq[0].length; pos += 4096) {
            int len = Math.min(4096, iq[0].length - pos);
            rx.baseband(Arrays.copyOfRange(iq[0], pos, pos + len), Arrays.copyOfRange(iq[1], pos, pos + len), len);
        }
        return rec;
    }

    // ------------------------------------------------------------------ tests
    /** The grant fields are read from the TS 102 361-4 positions, written here bit by bit (not with the builder). */
    static void layout() {
        boolean[] body = new boolean[80];
        body[0] = true;
        DmrBits.write(body, 2, DmrCsbk.TV_GRANT, 6);
        DmrBits.write(body, 16, 0x123, 12);   // LPCN
        body[28] = true;                       // logical slot: timeslot 2
        body[30] = true;                       // emergency
        DmrBits.write(body, 32, 4501, 24);     // target
        DmrBits.write(body, 56, 77, 24);       // source
        DmrCsbk g = DmrCsbk.parse(DmrFec.csbk96(body));
        check(g.ok && g.grant() && g.groupCall() && g.lcn == 0x123 && g.slot == 2 && g.emergency && g.target == 4501 && g.source == 77,
                "Tier III talkgroup voice grant fields from their standard positions");
        check(g.text.equals("TIER III TALKGROUP VOICE GRANT TG:4501 SRC:77 LPCN:291 TS2 EMERGENCY"), "grant text: " + g.text);

        DmrBits.write(body, 2, DmrCsbk.PV_GRANT, 6);
        DmrCsbk pv = DmrCsbk.parse(DmrFec.csbk96(body));
        check(pv.grant() && !pv.groupCall() && pv.text.startsWith("TIER III PRIVATE VOICE GRANT TO:4501"), "private voice grant");
        DmrBits.write(body, 2, DmrCsbk.TD_GRANT, 6);
        DmrCsbk td = DmrCsbk.parse(DmrFec.csbk96(body));
        check(!td.grant() && td.dataGrant(), "talkgroup data grant is not followed");
        DmrBits.write(body, 2, DmrCsbk.TV_GRANT, 6);
        body[1] = true;
        check(!DmrCsbk.parse(DmrFec.csbk96(body)).grant(), "a protected grant is not followed");

        boolean[] abs = DmrBurst.mbcAbsolute(0x0A5, 455_012_500L, 450_212_500L);
        long[] p = DmrCsbk.absolute(abs);
        check(p != null && p[0] == 0x0A5 && p[1] == 455_012_500L && p[2] == 450_212_500L, "absolute channel parameters (MHz + 125 Hz steps)");
        boolean[] junk = DmrBurst.mbcAbsolute(5, 455_000_000L, 450_000_000L);
        DmrBits.write(junk, 22, 2, 10);       // 2 MHz: impossible
        check(DmrCsbk.absolute(junk) == null, "an impossible frequency is refused");
    }

    /** A Tier III control channel over the air: plain grant, absolute (MBC) grant, then a plain grant for the learned LPCN. */
    static void controlChannel() {
        DmrTrunk trunk = new DmrTrunk(DmrTrunk.Flavor.TIER3);
        List<boolean[]> s1 = new ArrayList<>();
        s1.add(csbk(DmrCsbk.TV_GRANT, DmrBurst.grantData(4501, 77, 3, 2, true)));
        s1.add(data(DmrBurst.MBC_HEADER, DmrBurst.mbcGrantHeader(DmrCsbk.PV_GRANT, 9001, 9002, 1)));
        s1.add(data(DmrBurst.MBC_CONTINUATION, DmrBurst.mbcAbsolute(40, 455_512_500L, 450_512_500L)));
        s1.add(csbk(DmrCsbk.TV_GRANT, DmrBurst.grantData(300, 5, 40, 1)));
        s1.add(csbk(DmrCsbk.TD_GRANT, DmrBurst.grantData(300, 5, 7, 1)));
        Rec r = run(null, trunk, CONTROL, s1, List.of());
        check(r.grants.size() == 3, "three voice grants (the data grant is not followed): " + r.grants.size());
        if (r.grants.size() < 3) return;
        DmrTrunk.Grant g0 = r.grants.get(0), g1 = r.grants.get(1), g2 = r.grants.get(2);
        check(g0.talkgroup == 4501 && g0.source == 77 && g0.slot == 2 && g0.group && g0.emergency && g0.hz == BASE + 3 * 12_500L,
                "plain grant: TG, source, slot 2, emergency, LPCN 3 from the base");
        check(!g1.group && g1.talkgroup == 9001 && g1.source == 9002 && g1.slot == 1 && g1.hz == 450_512_500L && g1.lcn == 40,
                "MBC private grant: downlink is the frequency nearer the control channel (" + g1.hz + ")");
        check(g2.hz == 450_512_500L, "later grant for LPCN 40 uses the learned frequency");
        check(r.control.stream().anyMatch(t -> t.startsWith("TIER III ABSOLUTE CHANNEL LPCN:40 TX:455.51250 RX:450.51250")), "absolute channel logged");
        check(r.control.stream().anyMatch(t -> t.startsWith("TIER III TALKGROUP DATA GRANT TG:300")), "data grant logged");
    }

    /** A last MBC block whose CRC fails gives no grant. */
    static void absoluteRefused() {
        DmrTrunk trunk = new DmrTrunk(DmrTrunk.Flavor.TIER3);
        boolean[] bad = DmrBurst.mbcAbsolute(40, 455_512_500L, 450_512_500L);
        bad[85] = !bad[85];
        List<boolean[]> s1 = List.of(data(DmrBurst.MBC_HEADER, DmrBurst.mbcGrantHeader(DmrCsbk.TV_GRANT, 1, 2, 1)), data(DmrBurst.MBC_CONTINUATION, bad));
        Rec r = run(null, trunk, CONTROL, s1, List.of());
        check(r.grants.isEmpty() && r.controlErrors >= 1, "MBC block with a bad CRC is dropped");
        check(trunk.tier3Hz(40, BASE) == BASE + 40 * 12_500L, "nothing learned from it");
    }

    /** PI header on slot 1 stops that call's audio; an emergency LC on slot 2 is reported and its audio still plays. */
    static void privacyAndEmergency() {
        List<boolean[]> enc = new ArrayList<>(), emg = new ArrayList<>();
        enc.add(data(DmrBurst.VOICE_LC, DmrBurst.linkControl(0, 0, 101, 11, false)));
        enc.add(data(DmrBurst.PI_HEADER, DmrBurst.piHeader(1, 0x22, 101)));
        boolean[] lc = DmrBurst.linkControl(0, 0, 202, 22, false);
        lc[16] = true;
        DmrBurst.sealLc(lc, DmrFec.MASK_VOICE_LC);
        emg.add(data(DmrBurst.VOICE_LC, lc));
        emg.add(data(DmrBurst.VOICE_LC, lc));
        for (int s = 0; s < 3; s++) {
            boolean[] e72 = Arrays.copyOf(DmrBurst.linkControl(0, 0, 101, 11, false), 72);
            enc.addAll(DmrSelfCheck.superframe(e72, DmrAmbe.SILENCE));
            boolean[] g72 = Arrays.copyOf(lc, 72);
            emg.addAll(DmrSelfCheck.superframe(g72, DmrAmbe.SILENCE));
        }
        Rec r = run(null, null, CONTROL, enc, emg);
        check(r.encrypted >= 1, "PI header marks the slot 1 call encrypted");
        check(r.emergency >= 1, "emergency bit in the slot 2 voice LC is reported");
        check(r.audio >= 3 * 6 * 3 * 0.85 && r.audio <= 3 * 6 * 3 + 3, "slot 2 audio plays, slot 1 (encrypted) gives none: " + r.audio + " frames");
    }

    /** Tier III P_CLEAR on a payload channel ends the call on that slot. */
    static void clear() {
        List<boolean[]> s1 = new ArrayList<>();
        s1.add(data(DmrBurst.VOICE_LC, DmrBurst.linkControl(0, 0, 101, 11, false)));
        s1.add(csbk(DmrCsbk.P_CLEAR, new boolean[64]));
        Rec r = run(null, null, CONTROL, s1, List.of());
        check(r.ends == 1, "P_CLEAR ends the call (" + r.ends + ")");
    }
}
