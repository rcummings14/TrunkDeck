package p25monitor.nativeengine;

import p25monitor.dsp.*;
import p25monitor.p25.*;
import java.nio.file.*;
import java.util.*;

/** 1.3.0 Phase 2 (TDMA): FEC, scrambling, framing, MAC, modem; real captures where available. Each returns {pass, fail}. */
public final class P2Tests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("  FAIL: " + what); } }
    static int[] result() { int[] r = {pass, fail}; pass = 0; fail = 0; return r; }
    public static final Path CAPTURE = Path.of(System.getProperty("p2capture", "samples/p25p2/p25p1andp2.bin"));

    /** RS(63,35,29) with the FACCH/SACCH puncturing, CRC-12 against independent table values, I-ISCH, DUID. */
    public static int[] fec() {
        Random r = new Random(3);
        Rs63 rs = new Rs63(28);
        int okF = 0, okS = 0, tooMany = 0;
        for (int trial = 0; trial < 300; trial++) {
            boolean facch = trial % 2 == 0;
            int top = facch ? 53 : 57, eras = facch ? 9 : 6, t = (28 - eras) / 2;
            int[] c = new int[63];
            for (int i = 28; i <= top; i++) c[i] = r.nextInt(64);
            rs.encode(c);
            int[] rx = c.clone();
            int[] ep = new int[eras]; for (int i = 0; i < eras; i++) { ep[i] = i; rx[i] = r.nextInt(64); }
            Set<Integer> hit = new HashSet<>();
            while (hit.size() < t) hit.add(eras + r.nextInt(top - eras + 1));
            for (int p : hit) rx[p] ^= 1 + r.nextInt(63);
            int fixed = rs.decode(rx, ep, top);
            if (fixed >= 0 && Arrays.equals(rx, c)) { if (facch) okF++; else okS++; }
            // one error more than the code can fix must not be accepted as a different valid codeword silently often
            int[] rx2 = c.clone(); for (int i = 0; i < eras; i++) rx2[i] = r.nextInt(64);
            Set<Integer> h2 = new HashSet<>(); while (h2.size() < t + 2) h2.add(eras + r.nextInt(top - eras + 1));
            for (int p : h2) rx2[p] ^= 1 + r.nextInt(63);
            if (rs.decode(rx2, ep, top) < 0) tooMany++;
        }
        System.out.printf(Locale.ROOT, "   RS(63,35,29): FACCH 9 erasures + 9 errors fixed %d/150, SACCH 6 erasures + 11 errors fixed %d/150; beyond capacity rejected %d/300%n", okF, okS, tooMany);
        check(okF == 150 && okS == 150, "RS corrects up to its capacity with the punctured symbols as erasures");
        check(tooMany >= 285, "RS rejects most words beyond its capacity (" + tooMany + "/300)");
        // CRC-12: contribution of single message bits (affine code): the first bit gives 0x256, the last the polynomial itself
        byte[] z = new byte[144]; int c0 = P2Mac.crc12(z, 144);
        byte[] b0 = z.clone(); b0[0] = 1; byte[] b143 = z.clone(); b143[143] = 1;
        check((P2Mac.crc12(b0, 144) ^ c0) == 0x256 && (P2Mac.crc12(b143, 144) ^ c0) == 0x897, "CRC-12 polynomial x^12+x^11+x^7+x^4+x^2+x+1");
        int isch = 0; for (int x = 0; x < 512; x++) { if ((x >> 3 & 3) == 3) continue; long w = P2Isch.encode(x) ^ (1L << r.nextInt(40)) ^ (1L << r.nextInt(40)); if (P2Isch.decode(w)[0] == x) isch++; }
        check(isch == 384, "I-ISCH decodes every codeword with 2 bit errors (" + isch + "/384)");
        return result();
    }

    /** A real Phase 2 dibit capture (DSD-FME issue 304, WACN 92715 SYS 1F6 NAC 01A, TG 2222): MAC PDUs, voice frames, AMBE FEC. */
    public static int[] realCapture() throws Exception {
        if (!Files.exists(CAPTURE)) { System.out.println("   (skipped: " + CAPTURE + " not present)"); return result(); }
        List<byte[]> v1 = new ArrayList<>(); int[] v2 = {0}; List<P2Mac> ptt = new ArrayList<>(); Set<Integer> groups = new TreeSet<>();
        P2Framer f = new P2Framer(new P2Framer.Listener() {
            public void voice(int ch, byte[][] fr, int duid) { if (ch == 0) v1.addAll(Arrays.asList(fr)); else v2[0] += fr.length; }
            public void mac(int ch, P2Mac m) { if (m.type == P2Mac.PTT) ptt.add(m); if (m.crcOk && m.group >= 0) groups.add(m.group); }
        });
        f.scrambler = new P2Scrambler(0x92715, 0x1F6, 0x01A);
        for (byte b : Files.readAllBytes(CAPTURE)) f.symbol(b & 3);
        int clean = -1;
        EngineLibs.VoiceCodec amb = EngineLibs.newAmbe();
        if (amb != null) { clean = 0; for (byte[] x : v1) { int[] st = amb.stats(x); if (st != null && st[0] == 0) clean++; } }
        System.out.printf(Locale.ROOT, "   %d fragments, I-ISCH %d ok / %d bad, MAC CRC ok %d (scrambled %d) / failed %d (scrambled %d); TS1 voice frames %d (%s with no FEC errors), TS2 %d; groups %s; PTT %s%n",
                f.fragments, f.ischOk, f.ischBad, f.macOk, f.scrambledMacOk, f.macBad, f.scrambledMacBad, v1.size(), clean < 0 ? "JMBE not loaded:" : String.valueOf(clean), v2[0], groups, ptt);
        check(f.scrambledMacOk >= 540 && f.scrambledMacBad == 0, "descrambled FACCH/SACCH pass RS + CRC (scrambling seed, burst order, layout)");
        check(v1.size() >= 1368, "TS1 voice frames: at least the 1,368 clean codewords other decoders find (" + v1.size() + ")");
        if (clean >= 0) check(clean >= 1368, "1,368 AMBE+2 frames with zero Golay errors in JMBE (" + clean + ")");
        check(groups.equals(Set.of(2222)), "talkgroup 2222 from MAC_PTT / MAC_ACTIVE (the DSD-FME log's grant)");
        check(!ptt.isEmpty() && ptt.stream().allMatch(m -> m.alg == 0x80 && !m.encrypted()), "clear call (ALG 80)");
        // wrong scrambling seed: nothing scrambled may pass
        P2Framer g = new P2Framer(new P2Framer.Listener() {});
        g.scrambler = new P2Scrambler(0xA4321, 0x2D5, 0x2D5);
        for (byte b : Files.readAllBytes(CAPTURE)) g.symbol(b & 3);
        check(g.scrambledMacOk == 0, "with another system's seed (A4321/2D5/2D5) nothing descrambles (" + g.scrambledMacOk + ")");
        return result();
    }

    public static List<byte[]> bank() throws Exception {
        List<byte[]> b = new ArrayList<>();
        if (Files.exists(CAPTURE)) {
            P2Framer f = new P2Framer(new P2Framer.Listener() { public void voice(int ch, byte[][] fr, int duid) { if (ch == 0) b.addAll(Arrays.asList(fr)); } });
            f.scrambler = new P2Scrambler(0x92715, 0x1F6, 0x01A);
            for (byte x : Files.readAllBytes(CAPTURE)) f.symbol(x & 3);
        }
        if (b.size() < 100) { Random r = new Random(9); for (int i = 0; i < 1400; i++) { byte[] x = new byte[9]; r.nextBytes(x); b.add(x); } }
        Set<String> seen = new HashSet<>(); List<byte[]> u = new ArrayList<>(); for (byte[] x : b) if (!Arrays.equals(x, P2Builder.SILENCE) && seen.add(HexFormat.of().formatHex(x))) u.add(x);
        return u;
    }

    /** Two talkgroups on one TDMA channel (example system scrambling): encoder -> optional H-DQPSK modem -> framer, bit-exact per slot. */
    public static int[] twoSlots(boolean modem, double offsetHz, double noise) throws Exception {
        List<byte[]> bank = bank();
        P2Scrambler sc = new P2Scrambler(0xA4321, 0x2D5, 0x2D5);
        P2Builder.CallSlot s1 = new P2Builder.CallSlot(), s2 = new P2Builder.CallSlot();
        s1.startPtt(311, 1001, false); s2.startPtt(312, 1002, true);
        Set<String> a = new HashSet<>(), b = new HashSet<>();
        for (int i = 0; i < 360; i++) { byte[] x = bank.get(2 * i), y = bank.get(2 * i + 1); s1.add(x); s2.add(y); a.add(HexFormat.of().formatHex(x)); b.add(HexFormat.of().formatHex(y)); }
        s1.finish(); s2.finish();
        P2Builder.Stream st = new P2Builder.Stream(sc, s1, s2);
        List<Integer> dib = new ArrayList<>(); Random r = new Random(4);
        for (int k = 0; k < 150; k++) dib.add(r.nextInt(4));
        for (int k = 0; k < 3 * 26; k++) for (int d : st.nextFragment()) dib.add(d);
        int[] ok = new int[2], bad = new int[2], cross = {0}; Map<Integer, String> mac = new TreeMap<>();
        boolean[] enc = new boolean[2]; int[] tg = {-1, -1};
        P2Framer f = new P2Framer(new P2Framer.Listener() {
            public void voice(int ch, byte[][] fr, int duid) {
                for (byte[] x : fr) { String h = HexFormat.of().formatHex(x); if (Arrays.equals(x, P2Builder.SILENCE)) continue;
                    if ((ch == 0 ? a : b).contains(h)) ok[ch]++; else if ((ch == 0 ? b : a).contains(h)) cross[0]++; else bad[ch]++; }
            }
            public void mac(int ch, P2Mac m) { if (m.crcOk && m.group > 0) tg[ch] = m.group; if (m.crcOk && m.encrypted()) enc[ch] = true; mac.merge(ch, m.typeName() + " ", (x, y) -> x.contains(y) ? x : x + y); }
        });
        f.scrambler = sc;
        if (!modem) for (int d : dib) f.symbol(d);
        else {
            int[] dd = dib.stream().mapToInt(Integer::intValue).toArray();
            float[] x = new CqpskModulator(8).modulate(dd);
            CqpskDemod dm = CqpskDemod.phase2(new C4fmDemod.Sink() { public void sync(float q) { f.syncEvent(); } public void symbol(int d, float l) { f.symbol(d); } });
            double ph = 0, w = 2 * Math.PI * offsetHz / 48000; int n = x.length / 2;
            float[] xi = new float[n], xq = new float[n];
            for (int k = 0; k < n; k++) { double c = Math.cos(ph), s = Math.sin(ph); ph += w;
                xi[k] = (float) (x[2 * k] * c - x[2 * k + 1] * s + noise * r.nextGaussian()); xq[k] = (float) (x[2 * k] * s + x[2 * k + 1] * c + noise * r.nextGaussian()); }
            dm.process(xi, xq, n);
        }
        String what = modem ? String.format(Locale.ROOT, "modem %+.0f Hz, noise %.2f", offsetHz, noise) : "dibits";
        System.out.printf(Locale.ROOT, "   %s: TS1 TG %d %d/360 exact %d damaged | TS2 TG %d %d/360 exact %d damaged, encrypted=%s | cross-slot %d | MAC %s%n",
                what, tg[0], ok[0], bad[0], tg[1], ok[1], bad[1], enc[1], cross[0], mac);
        check(cross[0] == 0, what + ": no frame of one slot on the other");
        check(tg[0] == 311 && tg[1] == 312, what + ": talkgroups per slot from MAC");
        check(!enc[0] && enc[1], what + ": encryption seen on slot 2 only");
        check(ok[0] >= (modem ? 350 : 360) && ok[1] >= (modem ? 350 : 360) && bad[0] + bad[1] <= (modem ? 4 : 0), what + ": voice frames bit-exact");
        return result();
    }

    /**
     * The engine's TDMA channel decoder (channelizer + FLL/AFC + H-DQPSK + framer) on a carrier 900 Hz off: slot 1 and
     * slot 2 carry different talkgroups with gaps (idle slot = MAC_IDLE), the second slot-2 transmission is encrypted,
     * and the carrier goes off for a second between calls. Per-slot frames must be bit-exact; MAC PDUs must show the
     * talkgroups, PTT / END_PTT and the encryption. With a wrong WACN/system seed the scrambling check must fail.
     */
    public static int[] channel() throws Exception {
        int pass = 0, fail = 0;
        List<byte[]> bank = bank();
        for (int wrongSeed = 0; wrongSeed < 2; wrongSeed++) {
            P2Scrambler sc = new P2Scrambler(0xA4321, 0x2D5, 0x2D5);
            P2Builder.CallSlot s1 = new P2Builder.CallSlot(), s2 = new P2Builder.CallSlot();
            s1.nac = 0x2D5; s2.nac = 0x2D5;
            Map<String, Integer> owner = new HashMap<>();
            Random r = new Random(77);
            List<Integer> dib = new ArrayList<>();
            int sent1 = 0, sent2 = 0, bi = 0, phase = 0;
            Map<Integer, int[]> perPhase = new TreeMap<>();
            // phases of 120 ms fragments: {slot-1 transmission?, slot-2 transmission (0 none, 1 clear, 2 encrypted)?, carrier on?}
            int[][] plan = {{1, 0, 1}, {1, 1, 1}, {0, 1, 1}, {0, 0, 1}, {0, 0, 0}, {1, 2, 1}, {1, 0, 1}, {0, 0, 1}};
            P2Builder.Stream st = new P2Builder.Stream(sc, s1, s2);
            for (int[] ph : plan) {
                if (ph[0] == 1) s1.startPtt(311, 1001, false); if (ph[1] > 0) s2.startPtt(312, 1002, ph[1] == 2);
                for (int k = 0; k < 20; k++) {   // 2.4 s per phase
                    if (ph[2] == 0) { for (int z = 0; z < 720; z++) dib.add(-1); continue; }
                    for (int q = 0; q < 6; q++) {   // 6 frames per 120 ms per slot
                        if (ph[0] == 1 && k < 18) { byte[] x = bank.get(bi++ % bank.size()); owner.put(HexFormat.of().formatHex(x), 1 + 10 * phase); s1.add(x); sent1++; perPhase.computeIfAbsent(10 * phase + 1, z -> new int[2])[0]++; }
                        if (ph[1] > 0 && k < 18) { byte[] x = bank.get(bi++ % bank.size()); owner.put(HexFormat.of().formatHex(x), 2 + 10 * phase); s2.add(x); sent2++; perPhase.computeIfAbsent(10 * phase + 2, z -> new int[2])[0]++; }
                    }
                    if (k == 18) { if (ph[0] == 1) s1.finish(); if (ph[1] > 0) s2.finish(); }
                    for (int d : st.nextFragment()) dib.add(d);
                }
                phase++;
            }
            // modulate (carrier off = zeros), +900 Hz, noise
            CqpskModulator mod = new CqpskModulator(8);
            int n = dib.size() * 8;
            float[] xi = new float[n], xq = new float[n];
            double phs = 0, w = 2 * Math.PI * 900 / 48000;
            for (int k = 0; k < dib.size(); k++) {
                float[] y = mod.modulate(new int[]{Math.max(0, dib.get(k))});
                for (int j = 0; j < 8; j++) {
                    int o = k * 8 + j; double c = Math.cos(phs), sn = Math.sin(phs); phs += w;
                    float a = dib.get(k) < 0 ? 0 : y[2 * j], b = dib.get(k) < 0 ? 0 : y[2 * j + 1];
                    xi[o] = (float) (0.2 * (a * c - b * sn) + 0.01 * r.nextGaussian()); xq[o] = (float) (0.2 * (a * sn + b * c) + 0.01 * r.nextGaussian());
                }
            }
            int[] ok = new int[3], bad = {0}, cross = {0}; int[] tg = {-1, -1}; boolean[] enc = new boolean[2]; int[] ends = new int[2];
            ChannelDecoder d = new ChannelDecoder(48000, 0, 0, new P2Framer.Listener() {
                public void voice(int ch, byte[][] fr, int duid) {
                    for (byte[] x : fr) { if (Arrays.equals(x, P2Builder.SILENCE)) continue; Integer o = owner.get(HexFormat.of().formatHex(x));
                        if (o == null) bad[0]++; else if (o % 10 != ch + 1) cross[0]++; else { ok[ch + 1]++; perPhase.get(o)[1]++; } }
                }
                public void mac(int ch, P2Mac m) { if (!m.crcOk) return; if (m.group > 0) tg[ch] = m.group; if (m.encrypted()) enc[ch] = true; if (m.type == P2Mac.END_PTT) ends[ch]++; }
            }, 0);
            d.p2.scrambler = wrongSeed == 1 ? new P2Scrambler(0xA4321, 0x2D6, 0x2D5) : sc;
            for (int s0 = 0; s0 < n; s0 += 4800) { int m = Math.min(4800, n - s0); d.process(Arrays.copyOfRange(xi, s0, s0 + m), Arrays.copyOfRange(xq, s0, s0 + m), m); }
            String summ = d.demodSummary();
            if (wrongSeed == 0) {
                System.out.printf(Locale.ROOT, "   slot 1: %d/%d frames exact, slot 2: %d/%d (incl. encrypted), damaged %d, crossed %d; TGs %d/%d, encrypted seen %s/%s, END_PTT %d/%d; offset %+.0f Hz; %s%n",
                        ok[1], sent1, ok[2], sent2, bad[0], cross[0], tg[0], tg[1], enc[0], enc[1], ends[0], ends[1], d.frequencyError(), summ);
                StringBuilder pp = new StringBuilder("   per transmission (phase*10+slot: received/sent):"); perPhase.forEach((k, v) -> pp.append(" ").append(k).append("=").append(v[1]).append("/").append(v[0])); System.out.println(pp);
                // the first transmission includes the cold pull-in from +900 Hz (54 degrees per symbol, more than the
                // pi/4-DQPSK decision margin, so nothing decodes until the FLL has moved the filter): allow 0.6 s of it
                boolean all = true; for (var en : perPhase.entrySet()) { int[] v = en.getValue(); if (v[1] < (en.getKey() == 1 ? v[0] - 30 : v[0])) all = false; }
                if (all) pass++; else { fail++; System.out.println("  FAIL: frames per slot (every transmission after the first must be complete)"); }
                if (bad[0] == 0 && cross[0] == 0) pass++; else { fail++; System.out.println("  FAIL: damaged or crossed frames"); }
                if (tg[0] == 311 && tg[1] == 312) pass++; else { fail++; System.out.println("  FAIL: talkgroups from MAC"); }
                if (!enc[0] && enc[1]) pass++; else { fail++; System.out.println("  FAIL: encryption detection"); }
                if (ends[0] >= 2 && ends[1] >= 2) pass++; else { fail++; System.out.println("  FAIL: MAC_END_PTT per slot"); }
                if (summ.contains("scrambling OK")) pass++; else { fail++; System.out.println("  FAIL: scrambling summary"); }
                if (Math.abs(d.frequencyError() - 900) < 150) pass++; else { fail++; System.out.println("  FAIL: frequency"); }
            } else {
                System.out.println("   wrong system ID in the seed: " + summ + ", exact frames " + (ok[1] + ok[2]));
                if (summ.contains("FAILING") && ok[1] + ok[2] == 0) pass++; else { fail++; System.out.println("  FAIL: wrong seed must fail the scrambling check"); }
            }
        }
        return new int[]{pass, fail};
    }

    /**
     * Carrier stops (or starts) in the middle of a fragment, noise around it: no damaged voice frame may come out.
     * (MultiSite seed 7 showed 2 damaged frames from the noise after a Phase 2 channel's carrier dropped.)
     */
    public static int[] cut() throws Exception {
        List<byte[]> bank = bank();
        P2Scrambler sc = new P2Scrambler(0xA4321, 0x2D5, 0x2D5);
        Random r = new Random(31);
        long damaged = 0, good = 0, gated = 0; int runs = 300;
        for (int run = 0; run < runs; run++) {
            P2Builder.CallSlot s1 = new P2Builder.CallSlot(), s2 = new P2Builder.CallSlot();
            s1.startPtt(311, 1001, false); s2.startPtt(312, 1002, false);
            Set<String> mine = new HashSet<>();
            for (int i = 0; i < 60; i++) { byte[] x = bank.get(r.nextInt(bank.size())), y = bank.get(r.nextInt(bank.size())); s1.add(x); s2.add(y); mine.add(HexFormat.of().formatHex(x)); mine.add(HexFormat.of().formatHex(y)); }
            P2Builder.Stream st = new P2Builder.Stream(sc, s1, s2);
            List<Integer> dib = new ArrayList<>();
            int startCut = run % 2 == 1 ? r.nextInt(720) : 0;              // odd runs: carrier starts mid-fragment too
            for (int k = 0; k < 300 + r.nextInt(300); k++) dib.add(r.nextInt(4));
            int f0 = dib.size();
            for (int k = 0; k < 6; k++) for (int d : st.nextFragment()) dib.add(d);
            for (int k = 0; k < startCut; k++) dib.set(f0 + k, r.nextInt(4));
            int cutAt = f0 + 3 * 720 + r.nextInt(3 * 720);                  // carrier stops somewhere in fragments 4-6
            while (dib.size() > cutAt) dib.remove(dib.size() - 1);
            for (int k = 0; k < 3000; k++) dib.add(r.nextInt(4));
            long[] cnt = new long[2];
            P2Framer f = new P2Framer(new P2Framer.Listener() {
                public void voice(int ch, byte[][] fr, int duid) { for (byte[] x : fr) { if (Arrays.equals(x, P2Builder.SILENCE)) continue; if (mine.contains(HexFormat.of().formatHex(x))) cnt[0]++; else cnt[1]++; } }
            });
            f.scrambler = sc;
            StringBuilder trace = new StringBuilder();
            f.burstTap = x -> trace.append(String.format(" [sf%d ch%d duid%d @%d]", x >> 8, x >> 5 & 7, x & 31, cnt[0] * 1000 + cnt[1]));
            for (int d : dib) f.symbol(d);
            if (cnt[1] > 0) System.out.println("   trace f0=" + f0 + " len=" + dib.size() + ":" + trace);
            good += cnt[0]; damaged += cnt[1]; gated += f.gatedBursts;
            if (cnt[1] > 0) System.out.println("   (damaged " + cnt[1] + ": start cut " + startCut + ", stop at fragment " + (cutAt - f0) / 720 + " dibit " + (cutAt - f0) % 720 + ")");
        }
        System.out.printf(Locale.ROOT, "   %d runs, carrier cut (and in half the runs started) mid-fragment: %d exact frames, %d damaged, %d noise bursts dropped%n", runs, good, damaged, gated);
        int pass = 0, fail = 0;
        if (damaged == 0) pass++; else { fail++; System.out.println("  FAIL: damaged frames from the noise around the carrier"); }
        if (good > runs * 30L) pass++; else { fail++; System.out.println("  FAIL: too few frames"); }
        return new int[]{pass, fail};
    }
}
