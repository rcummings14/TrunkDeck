package p25monitor.dmr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The DMR decoder on signals built by the test transmitter (DmrSignal): two group calls on the two timeslots of a
 * repeater (voice LC headers, voice superframes with embedded LC, a talker alias, terminators), sent as plain 4FSK and
 * as root raised cosine shaped 4FSK (the way a real radio transmits), with a carrier offset and with noise. Also the
 * colour code filter, a Tier III grant, and AMBE+2 frame correction / concealment.
 */
public final class DmrDecoderTests {
    static int pass, fail;

    static void check(String what, boolean ok) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "  ok    " : "  FAIL  ") + what);
    }

    public static void main(String[] a) {
        repeater("plain 4FSK", false, 0, 0);
        repeater("RRC shaped 4FSK", true, 0, 0);
        repeater("RRC shaped, carrier +400 Hz", true, 400, 0);
        repeater("RRC shaped, noise", true, 0, 0.10);
        repeater("RRC shaped, heavy noise (about 5 dB SNR)", true, 150, 0.40);
        radioSide("radio side (MS sync, no carrier between bursts)", 0.10);
        radioSide("radio side, heavy noise", 0.35);
        colourCodeFilter();
        grantTier3();
        ambe();
        System.out.println("DmrDecoderTests: " + pass + " passed, " + fail + " failed");
        if (fail > 0) System.exit(1);
    }

    /**
     * A CACH as a repeater sends it: the TACT names the next timeslot and its LCSS steps through the four fragments of a
     * short LC (first, continuation, continuation, last). The decoder refuses LCSS 0 (single fragment) in a CACH, which a
     * repeater never sends there. The 17 short LC payload bits are left at 0.
     */
    static boolean[] cach(int nextSlot, int burstIndex) {
        int[] lcss = {1, 3, 3, 2};
        int info = ((nextSlot == 2 ? 1 : 0) << 2) | lcss[burstIndex % 4];
        int word = DmrFec.hamming74Encode(info);
        boolean[] b = new boolean[24];
        for (int i = 0; i < 7; i++) b[DmrCach.TACT_POS[i]] = ((word >> (6 - i)) & 1) == 1;
        return b;
    }

    /** Both slots of a repeater, interleaved with CACH, padded with idle bursts; random dibits before. */
    static int[] repeaterDibits(int tg1, int src1, String alias1, int tg2, int src2) {
        int sf = 9;
        List<boolean[]> s1 = DmrSignal.call(tg1, src1, sf, alias1), s2 = DmrSignal.call(tg2, src2, sf, null);
        List<Integer> dib = new ArrayList<>();
        Random r = new Random(5);
        for (int k = 0; k < 300; k++) dib.add(r.nextInt(4));
        int units = Math.max(s1.size(), s2.size()) * 2 + 6;
        int p1 = 0, p2 = 0;
        for (int u = 0; u < units; u++) {
            int slot = u % 2 == 0 ? 1 : 2;
            boolean[] burst;
            if (slot == 1) burst = p1 < s1.size() ? s1.get(p1++) : DmrSignal.idle();
            else burst = p2 < s2.size() ? s2.get(p2++) : DmrSignal.idle();
            for (int d : DmrSignal.dibitsOf(cach(slot, u))) dib.add(d);
            for (int d : DmrSignal.dibitsOf(burst)) dib.add(d);
        }
        for (int k = 0; k < 300; k++) dib.add(r.nextInt(4));
        return dib.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * 4FSK at 48 kHz, 10 samples per symbol, +/-1944 Hz outer deviation. {@code rrc}: the symbol levels go through a
     * root raised cosine filter (alpha 0.2) before the FM modulator, as in a real DMR transmitter.
     */
    static float[][] modulate(int[] dibits, boolean rrc, double offsetHz, double noise) {
        int sps = DmrSignal.SPS, n = dibits.length * sps;
        double[] level = new double[n];
        if (rrc) {
            double[] h = rrcTaps(sps, 0.2, 8);
            double[] impulses = new double[n + h.length];
            for (int k = 0; k < dibits.length; k++) impulses[k * sps] = DmrBits.levelOf(dibits[k]);
            // gain: a long run of one symbol reaches its full level
            double g = 0;
            for (int k = 0; k < h.length; k += sps) g += h[k];
            int delay = h.length / 2;
            for (int t = 0; t < n; t++) {
                double acc = 0;
                for (int k = 0; k < h.length; k++) { int s = t + delay - k; if (s >= 0 && s < impulses.length) acc += impulses[s] * h[k]; }
                level[t] = acc / g;
            }
        } else {
            for (int k = 0; k < dibits.length; k++) Arrays.fill(level, k * sps, (k + 1) * sps, DmrBits.levelOf(dibits[k]));
        }
        float[] i = new float[n], q = new float[n];
        Random r = new Random(11);
        double phase = 0;
        for (int t = 0; t < n; t++) {
            double hz = level[t] * DmrSignal.DEV + offsetHz;
            phase += 2 * Math.PI * hz / DmrSignal.FS;
            i[t] = (float) (Math.cos(phase) + r.nextGaussian() * noise);
            q[t] = (float) (Math.sin(phase) + r.nextGaussian() * noise);
        }
        return new float[][]{i, q};
    }

    static double[] rrcTaps(int sps, double alpha, int spanSymbols) {
        int len = sps * spanSymbols + 1;
        double[] h = new double[len];
        for (int k = 0; k < len; k++) {
            double t = (k - len / 2) / (double) sps;
            double v;
            if (Math.abs(t) < 1e-9) v = 1 - alpha + 4 * alpha / Math.PI;
            else if (Math.abs(Math.abs(t) - 1 / (4 * alpha)) < 1e-9)
                v = alpha / Math.sqrt(2) * ((1 + 2 / Math.PI) * Math.sin(Math.PI / (4 * alpha)) + (1 - 2 / Math.PI) * Math.cos(Math.PI / (4 * alpha)));
            else v = (Math.sin(Math.PI * t * (1 - alpha)) + 4 * alpha * t * Math.cos(Math.PI * t * (1 + alpha))) / (Math.PI * t * (1 - Math.pow(4 * alpha * t, 2)));
            h[k] = v;
        }
        return h;
    }

    static void feed(DmrDecoder rx, float[][] iq) {
        // odd block sizes on purpose: the decoder must carry samples between blocks
        int pos = 0, block = 4095;
        while (pos < iq[0].length) {
            int len = Math.min(block, iq[0].length - pos);
            rx.baseband(Arrays.copyOfRange(iq[0], pos, pos + len), Arrays.copyOfRange(iq[1], pos, pos + len), len);
            pos += len;
            block = block == 4095 ? 1023 : 4095;
        }
    }

    static void repeater(String name, boolean rrc, double offsetHz, double noise) {
        System.out.println("stream: " + name);
        float[][] iq = modulate(repeaterDibits(DmrSignal.TG1, DmrSignal.SRC1, "TESTALIAS", DmrSignal.TG2, DmrSignal.SRC2), rrc, offsetHz, noise);
        int want = 9 * 6 * 3;

        DmrSignal.Rec rec = new DmrSignal.Rec();
        DmrDecoder rx = new DmrDecoder(1L, DmrSignal.CC, 0, null, 0, null, rec, 48000);
        feed(rx, iq);
        System.out.println("       slot1 frames " + rec.frames[1] + "/" + want + ", slot2 frames " + rec.frames[2] + "/" + want
                + ", wrong-slot " + rec.wrongSlot + ", bad frames " + rec.badFrames + ", ends " + rec.ends + ", alias " + rec.alias + " " + rec.aliasText);
        System.out.println("       " + rx.status());
        check(name + ": audio on both slots", rec.frames[1] >= want * 0.85 && rec.frames[2] >= want * 0.85);
        check(name + ": slots not mixed up", rec.wrongSlot == 0);
        check(name + ": no bad frames handed on", rec.badFrames == 0);
        check(name + ": both calls ended", rec.ends >= 2);
        check(name + ": talker alias assembled once", rec.alias == 1 && "1234:TESTALIAS".equals(rec.aliasText));
        check(name + ": synchronised", rx.synced() || rec.frames[1] > 0);
    }

    /**
     * A handheld's own transmission (simplex or a repeater input): MS syncs, no CACH, one 30 ms burst every 60 ms and no
     * carrier in between (only noise). The old decoder needed a special fix for this; the decoder must lock on it.
     */
    static void radioSide(String name, double noise) {
        System.out.println("stream: " + name);
        int sf = 9, tg = DmrSignal.TG1, src = DmrSignal.SRC1;
        List<boolean[]> bursts = new ArrayList<>();
        boolean[] lc = DmrSignal.lc72(0, tg, src);
        boolean[] hdr = DmrBurst.build(DmrSync.Kind.MS_DATA, DmrSignal.CC, DmrBurst.VOICE_LC, DmrBurst.linkControl(0, 0, tg, src, false));
        bursts.add(hdr); bursts.add(hdr); bursts.add(hdr);
        boolean[][] frag = DmrEmbedded.fragments(lc);
        int[] lcss = {DmrEmbedded.LCSS_FIRST, DmrEmbedded.LCSS_CONT, DmrEmbedded.LCSS_CONT, DmrEmbedded.LCSS_LAST};
        byte[] f = DmrAmbe.SILENCE;
        for (int s = 0; s < sf; s++) {
            bursts.add(DmrVoice.burst(f, f, f, true, DmrSync.Kind.MS_VOICE, 0, null));
            for (int k = 0; k < 4; k++) bursts.add(DmrVoice.burst(f, f, f, false, DmrSync.Kind.NONE, DmrEmbedded.emb(DmrSignal.CC, 0, lcss[k]), frag[k]));
            bursts.add(DmrVoice.burst(f, f, f, false, DmrSync.Kind.NONE, DmrEmbedded.emb(DmrSignal.CC, 0, 0), null));
        }
        boolean[] term = DmrBurst.build(DmrSync.Kind.MS_DATA, DmrSignal.CC, DmrBurst.TERMINATOR, DmrBurst.terminator(0, 0, tg, src, false));
        bursts.add(term); bursts.add(term);
        // 60 ms per burst at 4800 symbols/s = 288 symbols: 132 of burst, 156 of no carrier
        int gap = 288 * DmrSignal.SPS - 132 * DmrSignal.SPS;
        Random r = new Random(17);
        List<float[]> partsI = new ArrayList<>(), partsQ = new ArrayList<>();
        partsI.add(noiseOnly(r, 20000, noise)); partsQ.add(noiseOnly(r, 20000, noise));
        for (boolean[] b : bursts) {
            float[][] iq = modulate(DmrSignal.dibitsOf(b), true, 120, noise);
            partsI.add(iq[0]); partsQ.add(iq[1]);
            partsI.add(noiseOnly(r, gap, noise)); partsQ.add(noiseOnly(r, gap, noise));
        }
        partsI.add(noiseOnly(r, 20000, noise)); partsQ.add(noiseOnly(r, 20000, noise));
        float[][] all = {concat(partsI), concat(partsQ)};
        DmrSignal.Rec rec = new DmrSignal.Rec();
        DmrDecoder rx = new DmrDecoder(1L, DmrSignal.CC, 0, null, 0, null, rec, 48000);
        feed(rx, all);
        int want = sf * 6 * 3, got = rec.frames[0] + rec.frames[1] + rec.frames[2];
        int maxSlot = Math.max(rec.frames[0], Math.max(rec.frames[1], rec.frames[2]));
        System.out.println("       frames " + got + "/" + want + " (slots " + Arrays.toString(rec.frames) + "), bad " + rec.badFrames + ", ends " + rec.ends);
        System.out.println("       " + rx.status());
        check(name + ": voice decoded (" + got + " of " + want + ")", got >= want * 0.85);
        check(name + ": one talker stays on one slot", maxSlot == got);
        check(name + ": no bad frames handed on", rec.badFrames == 0);
        check(name + ": call ended", rec.ends >= 1);
    }

    /** {@code n} samples of receiver noise with no carrier. */
    static float[] noiseOnly(Random r, int n, double noise) {
        float[] v = new float[n];
        double sd = Math.max(noise, 0.05);
        for (int k = 0; k < n; k++) v[k] = (float) (r.nextGaussian() * sd);
        return v;
    }

    static float[] concat(List<float[]> parts) {
        int n = 0;
        for (float[] p : parts) n += p.length;
        float[] out = new float[n];
        int at = 0;
        for (float[] p : parts) { System.arraycopy(p, 0, out, at, p.length); at += p.length; }
        return out;
    }

    /** A decoder set to another colour code hears nothing. */
    static void colourCodeFilter() {
        System.out.println("stream: wrong colour code");
        float[][] iq = modulate(repeaterDibits(DmrSignal.TG1, DmrSignal.SRC1, null, DmrSignal.TG2, DmrSignal.SRC2), true, 0, 0);
        DmrSignal.Rec rec = new DmrSignal.Rec();
        DmrDecoder rx = new DmrDecoder(1L, (DmrSignal.CC + 1) % 16, 0, null, 0, null, rec, 48000);
        feed(rx, iq);
        check("colour code " + ((DmrSignal.CC + 1) % 16) + " ignores a colour code " + DmrSignal.CC + " repeater", rec.frames[1] == 0 && rec.frames[2] == 0);
    }

    /** A Tier III talkgroup voice channel grant CSBK on a control channel becomes a grant with base + LPCN x 12.5 kHz. */
    static void grantTier3() {
        System.out.println("stream: Tier III grant");
        int lpcn = 7, tg = 4321, src = 99;
        // TS 102 361-4 TV_GRANT: LPCN(12) LCN/timeslot(1) reserved emergency offset, target(24), source(24)
        boolean[] data = new boolean[64];
        DmrBits.write(data, 0, lpcn, 12);
        data[12] = true;                         // timeslot 2
        DmrBits.write(data, 16, tg, 24);
        DmrBits.write(data, 40, src, 24);
        boolean[] csbk = DmrBurst.build(DmrSync.Kind.BS_DATA, DmrSignal.CC, DmrBurst.CSBK, DmrBurst.csbk(DmrCsbk.TV_GRANT, 0, data));
        List<Integer> dib = new ArrayList<>();
        Random r = new Random(3);
        for (int k = 0; k < 300; k++) dib.add(r.nextInt(4));
        for (int u = 0; u < 40; u++) {
            int slot = u % 2 == 0 ? 1 : 2;
            for (int d : DmrSignal.dibitsOf(cach(slot, u))) dib.add(d);
            for (int d : DmrSignal.dibitsOf(slot == 1 ? csbk : DmrSignal.idle())) dib.add(d);
        }
        for (int k = 0; k < 300; k++) dib.add(r.nextInt(4));
        float[][] iq = modulate(dib.stream().mapToInt(Integer::intValue).toArray(), true, 0, 0);
        List<DmrTrunk.Grant> grants = new ArrayList<>();
        List<String> control = new ArrayList<>();
        DmrDecoder.Listener l = new DmrDecoder.Listener() {
            public void group(long hz, int slot, int cc, int t, int s, byte[][] ambe) {}
            public void unit(long hz, int slot, int cc, int t, int s) {}
            public void encrypted(long hz, int slot, int cc) {}
            public void end(long hz, int slot) {}
            public void grant(DmrTrunk.Grant g) { grants.add(g); }
            public void rest(int r, int s, long h, boolean m) {}
            public void alias(int s, String text) {}
            public void control(String text) { control.add(text); }
        };
        long base = 450_000_000L;
        DmrDecoder rx = new DmrDecoder(base, -1, 0, new DmrTrunk(DmrTrunk.Flavor.TIER3), base, List.of(base), l, 48000);
        feed(rx, iq);
        System.out.println("       grants " + grants.size() + (control.isEmpty() ? "" : ", first control block: " + control.get(0)));
        DmrTrunk.Grant g = grants.isEmpty() ? null : grants.get(0);
        check("Tier III grant decoded", g != null);
        check("grant TG " + tg + " from " + src + " on LPCN " + lpcn + " timeslot 2 at base + 7 x 12.5 kHz",
                g != null && g.talkgroup == tg && g.source == src && g.lcn == lpcn && g.slot == 2 && g.hz == base + lpcn * 12_500L && g.group);
    }

    /** AMBE+2 frame correction (Golay on C0) and concealment of frames that cannot be corrected. */
    static void ambe() {
        System.out.println("AMBE+2 frames");
        DmrAmbe.Voice v = DmrAmbe.decode(DmrAmbe.SILENCE);
        check("a real AMBE+2 frame decodes with 0 errors", v.ok() && v.errors == 0);
        check("encode(decode(x)) returns the same frame", Arrays.equals(DmrAmbe.encode(v.c0, v.c1, v.c2, v.c3), DmrAmbe.SILENCE));
        int[] W = field("W"), X = field("X"), Y = field("Y"), Z = field("Z");
        int[] pos = new int[24];   // frame bit index of each C0 bit
        for (int i = 0; i < 36; i++) {
            if (W[i] == 0) pos[X[i]] = 2 * i;
            if (Y[i] == 0) pos[Z[i]] = 2 * i + 1;
        }
        int[] got = new int[5];
        Random r = new Random(3);
        for (int errs = 1; errs <= 5; errs++) {
            for (int t = 0; t < 300; t++) {
                byte[] f = DmrAmbe.SILENCE.clone();
                List<Integer> bits = new ArrayList<>();
                for (int i = 0; i < 24; i++) bits.add(i);
                java.util.Collections.shuffle(bits, r);
                for (int k = 0; k < errs; k++) { int p = pos[bits.get(k)]; f[p >> 3] ^= (byte) (0x80 >> (p & 7)); }
                if (DmrAmbe.decode(f).ok()) got[errs - 1]++;
            }
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

    static int[] field(String name) {
        try {
            var f = DmrAmbe.class.getDeclaredField(name);
            f.setAccessible(true);
            return (int[]) f.get(null);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
}
