package p25monitor.dmr;

import java.util.*;

/**
 * Synthetic checks for the DMR decoder: FEC, AMBE rejection, CACH layout, and whole streams (voice on both slots, embedded
 * LC with talker alias, direct mode) pushed through the 4FSK demodulator at different gain, polarity and carrier offset.
 * Run: {@code java -cp out p25monitor.dmr.DmrSelfCheck}. These are made-up signals built with this program's own encoder,
 * so they find regressions and logic errors; they cannot show that the standard's tables are right. That needs real captures.
 */
public final class DmrSelfCheck {
    static int fails, checks;

    static void check(String name, boolean ok) {
        checks++;
        if (!ok) fails++;
        System.out.println((ok ? "  ok   " : "  FAIL ") + name);
    }

    public static void main(String[] a) throws Exception {
        fec();
        ambe();
        cach();
        connectPlus();
        stream("normal", 1f, 0, false, 0, false);
        stream("2x deviation", 2f, 0, false, 0, false);
        stream("0.7x deviation", 0.7f, 0, false, 0, false);
        stream("+500 Hz carrier offset", 1f, 500, false, 0, false);
        stream("inverted polarity", 1f, 0, true, 0, false);
        stream("inverted + 2x + sample offset 4", 2f, -300, true, 4, false);
        stream("with noise", 1f, 0, false, 3, true);
        radioSide();
        direct();
        System.out.println(fails == 0 ? "ALL " + checks + " CHECKS PASSED" : fails + " OF " + checks + " CHECKS FAILED");
        if (fails > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ FEC
    static void fec() {
        Random r = new Random(7);
        int single = 0, singleTotal = 0, dbl = 0, dblTotal = 0, rowTwo = 0;
        for (int t = 0; t < 30; t++) {
            boolean[] info = new boolean[96];
            for (int i = 0; i < 96; i++) info[i] = r.nextBoolean();
            boolean[] tx = DmrFec.bptcEncode(info);
            if (!Arrays.equals(info, DmrFec.bptcDecode(tx))) { check("bptc clean", false); return; }
            for (int p = 0; p < 196; p++) {
                boolean[] x = tx.clone(); x[p] = !x[p];
                singleTotal++; if (Arrays.equals(info, DmrFec.bptcDecode(x))) single++;
            }
            for (int k = 0; k < 100; k++) {
                boolean[] x = tx.clone();
                int p1 = r.nextInt(196), p2 = r.nextInt(196);
                if (p1 == p2) continue;
                x[p1] = !x[p1]; x[p2] = !x[p2];
                dblTotal++; if (Arrays.equals(info, DmrFec.bptcDecode(x))) dbl++;
            }
            // two errors in the same row of the 9x15 grid (the case the old row-only decoder got wrong)
            boolean[] x = tx.clone();
            int[] row = new int[15];
            // find tx positions of row 3: indexed idx = 1 + 3*15 + c, tx position = (idx*181)%196
            for (int c = 0; c < 15; c++) row[c] = ((1 + 3 * 15 + c) * 181) % 196;
            x[row[2]] = !x[row[2]]; x[row[9]] = !x[row[9]];
            if (Arrays.equals(info, DmrFec.bptcDecode(x))) rowTwo++;
        }
        check("BPTC fixes every single-bit error (" + single + "/" + singleTotal + ")", single == singleTotal);
        check("BPTC fixes random double errors (" + dbl + "/" + dblTotal + ")", dbl == dblTotal);
        check("BPTC fixes two errors in one row (" + rowTwo + "/30)", rowTwo == 30);

        boolean[] lc = DmrBurst.linkControl(0, 0, 9, 1473, false);
        check("LC header passes", DmrLc.parse(lc, DmrBurst.VOICE_LC).ok);
        int fixed = 0, total = 0;
        for (int octet = 0; octet < 12; octet++) {
            boolean[] x = lc.clone();
            for (int b = 0; b < 8; b += 2) x[octet * 8 + b] = !x[octet * 8 + b]; // up to 4 bad bits in one octet
            total++;
            DmrLc p = DmrLc.parse(x, DmrBurst.VOICE_LC);
            if (p.ok && p.target == 9 && p.source == 1473) fixed++;
        }
        check("LC header with one bad octet is repaired (" + fixed + "/" + total + ")", fixed == total);
        boolean[] two = lc.clone(); two[0] = !two[0]; two[30] = !two[30];
        check("LC header with two bad octets is refused", !DmrLc.parse(two, DmrBurst.VOICE_LC).ok);
    }

    // ------------------------------------------------------------------ AMBE
    @SuppressWarnings("unchecked")
    static void ambe() throws Exception {
        DmrAmbe.Voice v = DmrAmbe.decode(DmrAmbe.SILENCE);
        check("real P25 AMBE+2 frame decodes with 0 errors", v.ok() && v.errors == 0);
        check("encode(decode(x)) returns the same frame", Arrays.equals(DmrAmbe.encode(v.c0, v.c1, v.c2, v.c3), DmrAmbe.SILENCE));
        var W = f("W"); var X = f("X"); var Y = f("Y"); var Z = f("Z");
        // frame bit index of each C0 bit: c0 vector 0, index 0..23
        int[] pos = new int[24];
        for (int i = 0; i < 36; i++) {
            if (W[i] == 0) pos[X[i]] = 2 * i;
            if (Y[i] == 0) pos[Z[i]] = 2 * i + 1;
        }
        int[] expectOk = {1, 2, 3}, got = new int[5];
        Random r = new Random(3);
        for (int errs = 1; errs <= 5; errs++) {
            int acc = 0, n = 300;
            for (int t = 0; t < n; t++) {
                byte[] f = DmrAmbe.SILENCE.clone();
                List<Integer> bits = new ArrayList<>(); for (int i = 0; i < 24; i++) bits.add(i);
                Collections.shuffle(bits, r);
                for (int k = 0; k < errs; k++) { int p = pos[bits.get(k)]; f[p >> 3] ^= (byte) (0x80 >> (p & 7)); }
                if (DmrAmbe.decode(f).ok()) acc++;
            }
            got[errs - 1] = acc;
            System.out.println("       C0 with " + errs + " wrong bits: accepted " + acc + "/" + n);
        }
        check("1-3 wrong bits in C0 are corrected", got[0] == 300 && got[1] == 300 && got[2] == 300);
        check("4 wrong bits in C0 are rejected", got[3] == 0);
        DmrAmbe.Concealer c = new DmrAmbe.Concealer();
        byte[] good = c.process(DmrAmbe.SILENCE);
        byte[] junk = DmrAmbe.SILENCE.clone();
        for (int k = 0; k < 4; k++) { int p = pos[k * 5]; junk[p >> 3] ^= (byte) (0x80 >> (p & 7)); }
        byte[] first = c.process(junk), second = c.process(junk);
        check("first bad frame repeats the last good one", Arrays.equals(first, good));
        check("second bad frame is silence", Arrays.equals(second, DmrAmbe.SILENCE));
    }

    static int[] f(String name) throws Exception {
        var fld = DmrAmbe.class.getDeclaredField(name);
        fld.setAccessible(true);
        return (int[]) fld.get(null);
    }

    // ------------------------------------------------------------------ CACH
    static void cach() {
        boolean ok = true;
        for (int s = 1; s <= 2; s++) {
            boolean[] b = DmrCach.encode(s);
            DmrCach p = DmrCach.parse(b);
            ok &= p.ok && p.errors == 0 && p.nextSlot == s;
            b[DmrCach.TACT_POS[3]] = !b[DmrCach.TACT_POS[3]];
            DmrCach q = DmrCach.parse(b);
            ok &= q.ok && q.errors == 1 && q.nextSlot == s;
            b = DmrCach.encode(s);
            b[1] = !b[1]; b[2] = !b[2]; // payload bits: must not matter
            ok &= DmrCach.parse(b).errors == 0;
        }
        check("CACH TACT round trip, one-bit repair, payload bits ignored", ok);
    }

    static void connectPlus() {
        boolean ok = true;
        for (int lcn = 1; lcn <= 4; lcn++) for (int slot = 1; slot <= 2; slot++) {
            DmrCsbk c = DmrCsbk.parse(DmrBurst.csbk(DmrCsbk.CONNECT_VOICE, DmrCsbk.FID_CONNECT, DmrBurst.connectVoice(13813, 13750, lcn, slot)), true);
            ok &= c.connectVoice && c.lcn == lcn && c.slot == slot && c.target == 13750 && c.source == 13813;
        }
        check("Connect Plus grant: LCN 1-4 and both slots round trip", ok);
        // the two grants seen on a real system: LCN 2 (bit set) was on the air in slot 2, LCN 1 (bit set) in slot 2
        boolean[] g2 = new boolean[96], g1 = new boolean[96];
        DmrBits.write(g2, 2, 3, 6); DmrBits.write(g2, 8, 6, 8); DmrBits.write(g2, 16, 0x0035F5, 24); DmrBits.write(g2, 40, 0x0035B6, 24); DmrBits.write(g2, 64, 0x28, 8);
        DmrBits.write(g1, 2, 3, 6); DmrBits.write(g1, 8, 6, 8); DmrBits.write(g1, 16, 0x003A55, 24); DmrBits.write(g1, 40, 0x003053, 24); DmrBits.write(g1, 64, 0x18, 8);
        DmrCsbk a = DmrCsbk.parse(g2, true), b = DmrCsbk.parse(g1, true);
        check("real grants: LCN 2 and LCN 1 both parse as timeslot 2", a.lcn == 2 && a.slot == 2 && b.lcn == 1 && b.slot == 2);
    }

    // ------------------------------------------------------------------ streams
    static final int CC = 5, TG1 = 101, TG2 = 202, SRC1 = 1234, SRC2 = 5678;

    static boolean[] header(int tg, int src) { return DmrBurst.build(DmrSync.Kind.BS_DATA, CC, DmrBurst.VOICE_LC, DmrBurst.linkControl(0, 0, tg, src, false)); }
    static boolean[] term(int tg, int src) { return DmrBurst.build(DmrSync.Kind.BS_DATA, CC, DmrBurst.TERMINATOR, DmrBurst.terminator(0, 0, tg, src, false)); }
    static boolean[] idle() { return DmrBurst.build(DmrSync.Kind.BS_DATA, CC, DmrBurst.IDLE, null); }

    static boolean[] lc72(int flco, int tg, int src) {
        boolean[] full = DmrBurst.linkControl(flco, 0, tg, src, false);
        return Arrays.copyOf(full, 72);
    }

    /** Talker alias LCs (header, block 1) for a short 7-bit text. */
    static boolean[][] aliasLcs(String text) {
        boolean[] stream = new boolean[217];
        for (int i = 0; i < text.length(); i++) DmrBits.write(stream, i * 7, text.charAt(i), 7);
        boolean[] h = new boolean[72];
        DmrBits.write(h, 2, 4, 6);
        DmrBits.write(h, 16, 0, 2);
        DmrBits.write(h, 18, text.length(), 5);
        System.arraycopy(stream, 0, h, 23, 49);
        boolean[] b1 = new boolean[72];
        DmrBits.write(b1, 2, 5, 6);
        System.arraycopy(stream, 49, b1, 16, 56);
        return new boolean[][]{h, b1};
    }

    /** One superframe of a slot: A, B-E carrying the embedded LC, F. */
    static List<boolean[]> superframe(boolean[] embLc, byte[] frame) {
        List<boolean[]> l = new ArrayList<>();
        l.add(DmrVoice.burst(frame, frame, frame, true, DmrSync.Kind.BS_VOICE, 0, null));
        boolean[][] frag = DmrEmbedded.fragments(embLc);
        int[] lcss = {DmrEmbedded.LCSS_FIRST, DmrEmbedded.LCSS_CONT, DmrEmbedded.LCSS_CONT, DmrEmbedded.LCSS_LAST};
        for (int k = 0; k < 4; k++) l.add(DmrVoice.burst(frame, frame, frame, false, DmrSync.Kind.NONE, DmrEmbedded.emb(CC, 0, lcss[k]), frag[k]));
        l.add(DmrVoice.burst(frame, frame, frame, false, DmrSync.Kind.NONE, DmrEmbedded.emb(CC, 0, 0), null));
        return l;
    }

    static List<boolean[]> call(int tg, int src, int superframes, String alias) {
        List<boolean[]> l = new ArrayList<>();
        l.add(header(tg, src)); l.add(header(tg, src)); l.add(header(tg, src));
        boolean[][] al = alias == null ? null : aliasLcs(alias);
        for (int s = 0; s < superframes; s++) {
            boolean[] emb = lc72(0, tg, src);
            if (al != null && s % 3 == 1) emb = al[0];
            if (al != null && s % 3 == 2) emb = al[1];
            l.addAll(superframe(emb, DmrAmbe.SILENCE));
        }
        l.add(term(tg, src)); l.add(term(tg, src));
        return l;
    }

    static final class Rec implements DmrReceiver.Listener {
        final int[] frames = new int[3];
        final int[] groupSlotTg = new int[3];
        int wrongSlot, ends, alias, badFrames, units;
        String aliasText;
        public void group(long hz, int slot, int cc, int tg, int src, byte[][] ambe) {
            if (ambe.length == 0) return;
            int want = slot == 1 ? TG1 : TG2;
            if (tg != want) wrongSlot++;
            frames[slot] += ambe.length;
            for (byte[] fr : ambe) if (!DmrAmbe.decode(fr).ok()) badFrames++;
        }
        public void unit(long hz, int slot, int cc, int t, int s) { units++; }
        public void encrypted(long hz, int slot, int cc) {}
        public void end(long hz, int slot) { ends++; }
        public void grant(DmrTrunk.Grant g) {}
        public void rest(int r, int s, long h, boolean m) {}
        public void alias(int src, String text) { alias++; aliasText = src + ":" + text; }
    }

    static float[][] modulate(int[] dibits, float scale, float offsetHz, boolean invert, int skip, boolean noise) {
        int n = dibits.length * Fsk4Demod.SPS;
        float[] i = new float[n], q = new float[n];
        double phase = 0;
        Random r = new Random(11);
        int at = 0;
        for (int d : dibits) {
            double hz = DmrBits.levelOf(d) * Fsk4Demod.DEV * scale + offsetHz;
            if (invert) hz = -hz;
            double w = 2 * Math.PI * hz / Fsk4Demod.FS;
            for (int s = 0; s < Fsk4Demod.SPS; s++) {
                phase += w;
                i[at] = (float) Math.cos(phase) + (noise ? (float) (r.nextGaussian() * 0.04) : 0);
                q[at] = (float) Math.sin(phase) + (noise ? (float) (r.nextGaussian() * 0.04) : 0);
                at++;
            }
        }
        return new float[][]{Arrays.copyOfRange(i, skip, n), Arrays.copyOfRange(q, skip, n)};
    }

    static void stream(String name, float scale, float offsetHz, boolean invert, int skip, boolean noise) {
        System.out.println("stream: " + name);
        int sf = 9;
        List<boolean[]> s1 = call(TG1, SRC1, sf, "TESTALIAS"), s2 = call(TG2, SRC2, sf, null);
        List<Integer> dib = new ArrayList<>();
        Random r = new Random(5);
        for (int k = 0; k < 300; k++) dib.add(r.nextInt(4));
        int units = Math.max(s1.size(), s2.size()) * 2 + 6;
        int p1 = 0, p2 = 0;
        for (int u = 0; u < units; u++) {
            int slot = u % 2 == 0 ? 1 : 2;
            boolean[] burst;
            if (slot == 1) burst = p1 < s1.size() ? s1.get(p1++) : idle();
            else burst = p2 < s2.size() ? s2.get(p2++) : idle();
            for (int d : Fsk4Demod.dibitsOf(DmrCach.encode(slot))) dib.add(d);
            for (int d : Fsk4Demod.dibitsOf(burst)) dib.add(d);
        }
        int[] d = dib.stream().mapToInt(Integer::intValue).toArray();
        float[][] iq = modulate(d, scale, offsetHz, invert, skip, noise);
        Rec rec = new Rec();
        DmrReceiver rx = new DmrReceiver(1L, CC, 0, null, 0, null, rec);
        int pos = 0;
        while (pos < iq[0].length) {
            int len = Math.min(4096, iq[0].length - pos);
            rx.baseband(Arrays.copyOfRange(iq[0], pos, pos + len), Arrays.copyOfRange(iq[1], pos, pos + len), len);
            pos += len;
        }
        int want = sf * 6 * 3;
        System.out.println("       slot1 frames " + rec.frames[1] + "/" + want + ", slot2 frames " + rec.frames[2] + "/" + want
                + ", wrong-slot " + rec.wrongSlot + ", bad frames " + rec.badFrames + ", ends " + rec.ends + ", alias " + rec.alias + " " + rec.aliasText);
        boolean got = rec.frames[1] >= want * 0.85 && rec.frames[2] >= want * 0.85;
        check(name + ": audio on both slots", got);
        check(name + ": slots not mixed up", rec.wrongSlot == 0);
        check(name + ": no bad frames handed on", rec.badFrames == 0);
        check(name + ": both calls ended", rec.ends >= 2);
        check(name + ": talker alias assembled once", rec.alias == 1 && "1234:TESTALIAS".equals(rec.aliasText));
    }

    // ------------------------------------------------------------------ direct mode
    static void direct() {
        System.out.println("stream: direct mode, slot 2");
        List<Integer> dib = new ArrayList<>();
        Random r = new Random(9);
        for (int k = 0; k < 300; k++) dib.add(r.nextInt(4));
        boolean[] emb = lc72(0, TG2, SRC2);
        List<boolean[]> bursts = new ArrayList<>();
        for (int s = 0; s < 6; s++) {
            boolean[][] frag = DmrEmbedded.fragments(emb);
            int[] lcss = {DmrEmbedded.LCSS_FIRST, DmrEmbedded.LCSS_CONT, DmrEmbedded.LCSS_CONT, DmrEmbedded.LCSS_LAST};
            byte[] f = DmrAmbe.SILENCE;
            bursts.add(DmrVoice.burst(f, f, f, true, DmrSync.Kind.DM_VOICE_TS2, 0, null));
            for (int k = 0; k < 4; k++) bursts.add(DmrVoice.burst(f, f, f, false, DmrSync.Kind.NONE, DmrEmbedded.emb(CC, 0, lcss[k]), frag[k]));
            bursts.add(DmrVoice.burst(f, f, f, false, DmrSync.Kind.NONE, DmrEmbedded.emb(CC, 0, 0), null));
        }
        // a direct-mode slot repeats every 288 dibits: the burst, then the other slot's time (left empty)
        boolean[] hdr = DmrBurst.build(DmrSync.Kind.DM_DATA_TS2, CC, DmrBurst.VOICE_LC, DmrBurst.linkControl(0, 0, TG2, SRC2, false));
        List<boolean[]> all = new ArrayList<>(); all.add(hdr); all.add(hdr); all.add(hdr); all.addAll(bursts);
        for (boolean[] b : all) {
            for (int d : Fsk4Demod.dibitsOf(b)) dib.add(d);
            for (int k = 0; k < 12; k++) dib.add(r.nextInt(4));      // guard
            for (int k = 0; k < 144; k++) dib.add(r.nextInt(4));     // the other slot: nothing
        }
        int[] d = dib.stream().mapToInt(Integer::intValue).toArray();
        float[][] iq = modulate(d, 1f, 0, false, 0, false);
        Rec rec = new Rec();
        DmrReceiver rx = new DmrReceiver(1L, CC, 0, null, 0, null, rec);
        rx.baseband(iq[0], iq[1], iq[0].length);
        System.out.println("       slot1 frames " + rec.frames[1] + ", slot2 frames " + rec.frames[2]);
        check("direct mode: voice reported on slot 2, none on slot 1", rec.frames[2] >= 6 * 6 * 3 * 0.8 && rec.frames[1] == 0);
    }

    // ------------------------------------------------------------------ radio side
    /**
     * A radio (not a repeater): MS sync, one 30 ms burst every 60 ms and no carrier in between, only receiver noise, the
     * way an SDR records a handheld on simplex or on a repeater input (the SigIdWiki DMR sample). Measuring that noise
     * as deviation kept the demodulator from ever locking.
     */
    static void radioSide() {
        System.out.println("stream: radio side, noise between bursts");
        boolean[] emb = lc72(0, TG1, SRC1);
        byte[] f = DmrAmbe.SILENCE;
        boolean[] hdr = DmrBurst.build(DmrSync.Kind.MS_DATA, CC, DmrBurst.VOICE_LC, DmrBurst.linkControl(0, 0, TG1, SRC1, false));
        List<boolean[]> all = new ArrayList<>(List.of(hdr, hdr, hdr));
        int[] lcss = {DmrEmbedded.LCSS_FIRST, DmrEmbedded.LCSS_CONT, DmrEmbedded.LCSS_CONT, DmrEmbedded.LCSS_LAST};
        for (int s = 0; s < 8; s++) {
            boolean[][] frag = DmrEmbedded.fragments(emb);
            all.add(DmrVoice.burst(f, f, f, true, DmrSync.Kind.MS_VOICE, 0, null));
            for (int k = 0; k < 4; k++) all.add(DmrVoice.burst(f, f, f, false, DmrSync.Kind.NONE, DmrEmbedded.emb(CC, 0, lcss[k]), frag[k]));
            all.add(DmrVoice.burst(f, f, f, false, DmrSync.Kind.NONE, DmrEmbedded.emb(CC, 0, 0), null));
        }
        Random r = new Random(13);
        int sps = Fsk4Demod.SPS, gap = (144 + 12) * sps;
        List<float[]> iq = new ArrayList<>();
        double phase = 0;
        for (int k = 0; k < 2 * gap; k++) iq.add(new float[]{(float) r.nextGaussian(), (float) r.nextGaussian()});
        for (boolean[] b : all) {
            for (int d : Fsk4Demod.dibitsOf(b)) {
                double w = 2 * Math.PI * (-DmrBits.levelOf(d) * Fsk4Demod.DEV + 300) / Fsk4Demod.FS;   // inverted, 300 Hz off
                for (int s = 0; s < sps; s++) {
                    phase += w;
                    iq.add(new float[]{(float) Math.cos(phase) + (float) (r.nextGaussian() * 0.05), (float) Math.sin(phase) + (float) (r.nextGaussian() * 0.05)});
                }
            }
            for (int k = 0; k < gap; k++) iq.add(new float[]{(float) r.nextGaussian(), (float) r.nextGaussian()});   // transmitter off
        }
        float[] i = new float[iq.size()], q = new float[iq.size()];
        for (int k = 0; k < i.length; k++) { i[k] = iq.get(k)[0]; q[k] = iq.get(k)[1]; }
        Rec rec = new Rec();
        DmrReceiver rx = new DmrReceiver(1L, CC, 0, null, 0, null, rec);
        for (int pos = 0; pos < i.length; pos += 4096) {
            int len = Math.min(4096, i.length - pos);
            rx.baseband(Arrays.copyOfRange(i, pos, pos + len), Arrays.copyOfRange(q, pos, pos + len), len);
        }
        int got = rec.frames[1] + rec.frames[2], want = 8 * 6 * 3;
        System.out.println("       voice frames " + got + "/" + want + ", bad frames " + rec.badFrames);
        check("radio side with noise between bursts: voice decoded", got >= want * 0.6);
        check("radio side with noise between bursts: no bad frames handed on", rec.badFrames == 0);
    }
}
