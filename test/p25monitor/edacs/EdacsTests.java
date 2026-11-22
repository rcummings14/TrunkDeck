package p25monitor.edacs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** EDACS control channel (BCH, framing, 9600 bit/s receiver, messages) and ProVoice voice frames, on synthetic signals. */
public final class EdacsTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("EdacsTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** Two-level FSK at 9600 bit/s, 48 kHz complex, lightly smoothed (GFSK-like). */
    public static float[][] fsk(List<Boolean> bits, double devHz, double offsetHz, double noise, boolean invert, long seed) {
        Random r = new Random(seed);
        double sps = 48000.0 / 9600;
        int n = (int) (bits.size() * sps) + 48;
        float[] i = new float[n], q = new float[n];
        double[] f = new double[n];
        for (int k = 0; k < n; k++) { int b = (int) (k / sps); boolean v = b < bits.size() && bits.get(b); f[k] = v ^ invert ? devHz : -devHz; }
        double ph = 0;
        for (int k = 0; k < n; k++) {
            double g = (f[k] + (k > 0 ? f[k - 1] : f[k])) / 2;
            ph += 2 * Math.PI * (g + offsetHz) / 48000;
            i[k] = (float) (Math.cos(ph) + noise * r.nextGaussian());
            q[k] = (float) (Math.sin(ph) + noise * r.nextGaussian());
        }
        return new float[][]{i, q};
    }

    static void feed(float[][] s, java.util.function.BiConsumer<float[], float[]> d) {
        for (int k = 0; k < s[0].length; k += 960) {
            int m = Math.min(960, s[0].length - k);
            d.accept(Arrays.copyOfRange(s[0], k, k + m), Arrays.copyOfRange(s[1], k, k + m));
        }
    }

    static void add(List<Boolean> l, boolean[] b) { for (boolean x : b) l.add(x); }

    static void run() throws Exception {
        Random r = new Random(7);
        // ---------------- BCH(40,28)
        check(Long.numberOfTrailingZeros(Long.highestOneBit(EdacsBch.GENERATOR)) == 12 && (EdacsBch.GENERATOR & 1) == 1, "BCH generator has degree 12");
        boolean all = true, one = true, two = true;
        int caught = 0;
        for (int t = 0; t < 2000; t++) {
            int d = r.nextInt(1 << 28);
            long w = EdacsBch.encode(d);
            if (EdacsBch.remainder(w) != 0 || EdacsBch.decode(w, null) != d) all = false;
            int b1 = r.nextInt(40), b2 = (b1 + 1 + r.nextInt(39)) % 40, b3;
            do b3 = r.nextInt(40); while (b3 == b1 || b3 == b2);
            if (EdacsBch.decode(w ^ 1L << b1, null) != d) one = false;
            if (EdacsBch.decode(w ^ 1L << b1 ^ 1L << b2, null) != d) two = false;
            if (EdacsBch.decode(w ^ 1L << b1 ^ 1L << b2 ^ 1L << b3, null) != d) caught++;
        }
        check(all, "BCH: every encoded message checks and decodes");
        check(one, "BCH: one wrong bit is corrected");
        check(two, "BCH: two wrong bits are corrected");
        check(caught == 2000, "BCH: three wrong bits never decode to the sent data (" + caught + " of 2000)");

        // ---------------- framing, bit level
        List<int[]> got = new ArrayList<>();
        EdacsDemod dm = new EdacsDemod((m1, m2) -> got.add(new int[]{m1, m2}));
        List<Boolean> bits = new ArrayList<>();
        for (int k = 0; k < 37; k++) bits.add(r.nextBoolean());
        int[][] sent = new int[10][];
        for (int k = 0; k < 10; k++) { sent[k] = new int[]{r.nextInt(1 << 28), r.nextInt(1 << 28)}; add(bits, EdacsDemod.encode(sent[k][0], sent[k][1])); }
        for (boolean b : bits) dm.bit(!b);              // inverted slicer
        boolean match = got.size() == 9;
        for (int k = 0; k < got.size() && match; k++) match = got.get(k)[0] == sent[k + 1][0] && got.get(k)[1] == sent[k + 1][1];
        check(match, "frames found in an inverted bit stream after junk (" + got.size() + " of 9 after lock)");
        // one copy of each message damaged: the vote fixes it
        got.clear();
        dm.reset();
        bits.clear();
        for (int k = 0; k < 6; k++) {
            boolean[] f = EdacsDemod.encode(sent[k][0], sent[k][1]);
            for (int j = 0; j < 40; j++) if (r.nextInt(4) == 0) f[48 + 40 + j] ^= true;   // middle copy of message 1
            f[48 + 120 + 5] ^= true; f[48 + 200 + 9] ^= true;                          // two copies of message 2, different bits
            add(bits, f);
        }
        for (boolean b : bits) dm.bit(b);
        match = got.size() == 5;
        for (int k = 0; k < got.size() && match; k++) match = got.get(k)[0] == sent[k + 1][0] && got.get(k)[1] == sent[k + 1][1];
        check(match, "damaged copies are outvoted (" + got.size() + " of 5)");

        // ---------------- 9600 bit/s FSK receiver
        for (boolean inv : new boolean[]{false, true}) {
            got.clear();
            EdacsDemod d2 = new EdacsDemod((m1, m2) -> got.add(new int[]{m1, m2}));
            bits.clear();
            for (int k = 0; k < 40; k++) bits.add(r.nextBoolean());
            for (int k = 0; k < 40; k++) add(bits, EdacsDemod.encode(sent[k % 10][0], sent[k % 10][1]));
            feed(fsk(bits, 2800, 900, 0.25, inv, 3), (i, q) -> d2.baseband(i, q, i.length));
            int ok = 0;
            for (int[] g : got) for (int[] s : sent) if (g[0] == s[0] && g[1] == s[1]) { ok++; break; }
            check(got.size() >= 37 && ok == got.size(), "FSK receiver" + (inv ? " (inverted)" : "") + ", 900 Hz off, noisy: " + ok + " of 40 frames");
        }

        // ---------------- messages
        List<String> grants = new ArrayList<>(), msgs = new ArrayList<>();
        java.util.function.IntToLongFunction lcns = l -> l >= 1 && l <= 5 ? 851_012_500L + 250_000L * (l - 1) : 0;
        EdacsSite.Listener sl = new EdacsSite.Listener() {
            public void grant(int g, int src, int lcn, long hz, boolean dig, boolean em, boolean upd) { grants.add(g + "/" + src + "/" + lcn + "/" + hz + "/" + dig + "/" + em + "/" + upd); }
            public void message(String t) { msgs.add(t); }
        };
        EdacsSite std = new EdacsSite(EdacsSite.Mode.STANDARD, lcns, sl);
        int[] g = EdacsMessages.standardGrant(0x2A5, 9876, 3, true, false);
        std.frame(g[0], g[1]);
        check(grants.equals(List.of("677/9876/3/851512500/true/false/false")), "standard ProVoice group grant: group, radio, LCN, frequency (" + grants + ")");
        grants.clear();
        g = EdacsMessages.standardGrant(17, 12, 2, false, true);
        std.frame(g[0], g[1]);
        check(grants.equals(List.of("17/12/2/851262500/false/true/false")), "standard analog emergency grant (" + grants + ")");
        grants.clear();
        std.frame(EdacsMessages.standardCc(4, 9), 0);
        check(std.ccLcn == 4 && std.siteId == 9 && msgs.get(msgs.size() - 1).contains("851.7625"), "control channel LCN and site ID (" + msgs.get(msgs.size() - 1) + ")");
        g = EdacsMessages.standardGrant(17, 12, 30, false, false);
        std.frame(g[0], g[1]);
        check(grants.isEmpty() && msgs.get(msgs.size() - 1).contains("[System Busy]"), "LCN 30 is 'system busy', not a call");
        g = EdacsMessages.standardGrant(17, 12, 9, false, false);
        std.frame(g[0], g[1]);
        check(grants.size() == 1 && grants.get(0).contains("/9/0/") && msgs.get(msgs.size() - 1).contains("not in the channel list"), "an LCN missing from the list is reported");
        grants.clear();
        EdacsSite ea = new EdacsSite(EdacsSite.Mode.EXTENDED, lcns, sl);
        g = EdacsMessages.extendedGrant(40123, 1234567 & 0xFFFFF, 5, true, true, false);
        ea.frame(g[0], g[1]);
        check(grants.equals(List.of("40123/" + (1234567 & 0xFFFFF) + "/5/852012500/true/true/false")), "EA ProVoice emergency grant (" + grants + ")");
        grants.clear();
        EdacsSite esk = new EdacsSite(EdacsSite.Mode.STANDARD_ESK, lcns, sl);
        g = EdacsMessages.standardGrant(100, 200, 1, false, false);
        esk.frame(g[0] ^ EdacsSite.ESK, g[1] ^ EdacsSite.ESK);
        check(grants.equals(List.of("100/200/1/851012500/false/false/false")), "ESK: messages are unmasked before reading (" + grants + ")");
        // the rest of the message set (DSD-FME's EDACS parsing)
        std.frame(EdacsMessages.standardCc(7, 9, true), 0);
        check(std.ccLcn == 4 && msgs.get(msgs.size() - 1).contains("[Auxiliary]"), "an auxiliary control channel does not replace the main one");
        grants.clear();
        std.frame(EdacsMessages.standardUpdate(0x2A5, 3, true, true), -1);
        check(grants.equals(List.of("677/-1/3/851512500/true/true/true")), "standard group update, second word bad (" + grants + ")");
        check(msgs.get(msgs.size() - 1).contains("AFS [05-045]"), "group shown as agency-fleet/subfleet (" + msgs.get(msgs.size() - 1) + ")");
        grants.clear();
        g = EdacsMessages.standardGrant(0x280, 55, 2, false, false);
        std.frame(g[0], g[1]);
        check(msgs.get(msgs.size() - 2).contains("[Agency]") || msgs.get(msgs.size() - 1).contains("[Agency]"), "agency call (fleet and subfleet 0)");
        grants.clear();
        g = EdacsMessages.standardIndividual(1234, 4321, 2, true);
        std.frame(g[0], g[1]);
        check(grants.isEmpty() && msgs.get(msgs.size() - 1).equals("Voice Individual Channel Assignment :: Digital Callee [01234] Caller [04321] LCN [02]"),
                "standard individual call (" + msgs.get(msgs.size() - 1) + ")");
        int before = msgs.size();
        std.frame(g[0], -1);
        check(msgs.size() == before, "a message that needs the second word is dropped when it failed its check");
        g = EdacsMessages.extendedSystem(0x1A2B, 0);
        ea.frame(g[0], g[1]);
        check(ea.ccLcn == -1 && ea.systemId == -1, "EA system information with LCN 0 is ignored");
        g = EdacsMessages.extendedSystem(0x1A2B, 3);
        ea.frame(g[0], g[1]);
        check(ea.ccLcn == 3 && ea.systemId == 0x1A2B, "EA system ID and control channel LCN");
        ea.frame(EdacsMessages.extendedSite(177, 21), 0);
        check(ea.siteId == 177 && ea.areaCode == 21, "EA site ID and area (" + ea.siteId + ", " + ea.areaCode + ")");
        ea.frame(0x1F << 23 | 0xD << 19, 0);
        check(msgs.get(msgs.size() - 1).equals("Serial Number Request"), "EA serial number request");
        ea.frame(0x0F << 23, 0x123);
        check(msgs.get(msgs.size() - 1).startsWith("Unknown Command"), "EA unknown command shown raw");
        check(EdacsSite.Mode.of("ea-esk") == EdacsSite.Mode.EXTENDED_ESK && EdacsSite.Mode.of("?") == EdacsSite.Mode.STANDARD, "addressing modes by key");

        // ---------------- ProVoice
        int[] perFrame = new int[2];
        for (int t : ProVoice.BLOCK) if (t >= 0) perFrame[t / 1000]++;
        check(perFrame[0] == 142 && perFrame[1] == 142 && ProVoice.W.length == 142 && ProVoice.X.length == 142, "ProVoice block: 142 coded bits per voice frame");
        boolean[][] used = new boolean[7][24];
        boolean dup = false;
        for (int k = 0; k < 142; k++) { if (used[ProVoice.W[k]][ProVoice.X[k]]) dup = true; used[ProVoice.W[k]][ProVoice.X[k]] = true; }
        check(!dup, "ProVoice interleave: every coded bit has its own place");
        boolean order = true;
        for (int t = 0; t < 500; t++) {
            int[] u = voiceParams(r);
            if (!Arrays.equals(ProVoice.toP25Order(ProVoice.fromP25Order(u)), u)) order = false;
        }
        check(order, "ProVoice <-> P25 parameter order round trip");
        int[][] u4 = {voiceParams(r), voiceParams(r), voiceParams(r), voiceParams(r)};
        boolean[] pv = ProVoice.encode(u4, false);
        ProVoice.Frame[] dec = ProVoice.decode(Arrays.copyOfRange(pv, ProVoice.SYNC_BITS, ProVoice.FRAME_BITS));
        boolean same = true;
        for (int k = 0; k < 4; k++) same &= dec[k].ok() && dec[k].errors() == 0 && Arrays.equals(dec[k].p25(), ProVoice.toP25Frame(u4[k]));
        check(same, "ProVoice frame decodes to the P25 frames of the same voice parameters");
        // three wrong bits in each Golay word region and one in each Hamming word: still the same
        boolean[] dmg = pv.clone();
        int flips = 0;
        for (int blk = 0; blk < 2; blk++) {
            int base = ProVoice.SYNC_BITS + (blk == 0 ? ProVoice.BLOCK_A : ProVoice.BLOCK_B);
            Set<String> hit = new HashSet<>();
            for (int k = 0; k < 286; k++) {
                int t = ProVoice.BLOCK[k];
                if (t < 0) continue;
                int i = t % 1000, w = ProVoice.W[i];
                String key = (t / 1000) + ":" + w;
                int limit = w <= 3 ? 1 : w <= 5 ? 1 : 0;
                if (!hit.contains(key) && limit > 0 && r.nextInt(3) == 0) { hit.add(key); dmg[base + k] ^= true; flips++; }
            }
        }
        dec = ProVoice.decode(Arrays.copyOfRange(dmg, ProVoice.SYNC_BITS, ProVoice.FRAME_BITS));
        same = true;
        for (int k = 0; k < 4; k++) same &= dec[k].ok() && Arrays.equals(dec[k].p25(), ProVoice.toP25Frame(u4[k]));
        check(same && flips > 5, "ProVoice error correction: " + flips + " wrong bits corrected");

        // ---------------- ProVoice receiver over FSK, ending with dotting
        List<byte[]> voice = new ArrayList<>();
        int[] ends = {0};
        ProVoiceReceiver pr = new ProVoiceReceiver(new ProVoiceReceiver.Listener() {
            public void voice(byte[][] f, int e) { voice.addAll(Arrays.asList(f)); }
            public void end() { ends[0]++; }
        });
        bits.clear();
        for (int k = 0; k < 53; k++) bits.add(r.nextBoolean());
        List<int[]> params = new ArrayList<>();
        for (int k = 0; k < 6; k++) {
            int[][] q = {voiceParams(r), voiceParams(r), voiceParams(r), voiceParams(r)};
            params.addAll(Arrays.asList(q));
            add(bits, ProVoice.encode(q, k % 2 == 1));
        }
        for (int k = 0; k < 96; k++) bits.add(k % 2 == 0);
        for (int k = 0; k < 40; k++) bits.add(r.nextBoolean());
        feed(fsk(bits, 2800, -700, 0.2, true, 11), (i, q) -> pr.baseband(i, q, i.length));
        int right = 0;
        for (int k = 0; k < voice.size() && k < params.size(); k++) if (Arrays.equals(voice.get(k), ProVoice.toP25Frame(params.get(k)))) right++;
        check(voice.size() == 24 && right == 24, "ProVoice receiver: " + right + " of 24 voice frames right (inverted, 700 Hz off, noisy)");
        check(ends[0] == 1, "dotting ends the transmission");

        // ---------------- JMBE reads the converted frames (when the codec is available)
        String jar = System.getProperty("trunkdeck.jmbe", "");
        if (!jar.isBlank() && Files.exists(Path.of(jar)) && p25monitor.nativeengine.EngineLibs.loadJmbe(Path.of(jar))) {
            var codec = p25monitor.nativeengine.EngineLibs.newImbe();
            int clean = 0, audio = 0;
            for (int t = 0; t < 200; t++) {
                byte[] f = ProVoice.toP25Frame(voiceParams(r));
                int[] st = codec.stats(f);
                if (st != null && st[0] == 0) clean++;
                float[] pcm = codec.decode(f);
                if (pcm != null && pcm.length == 160) audio++;
            }
            check(clean == 200 && audio == 200, "JMBE decodes the P25 frames with no corrections (" + clean + " of 200 clean)");
        } else System.out.println("  skip  JMBE check (run with -Dtrunkdeck.jmbe=<jmbe jar>)");
    }

    /** 88 random IMBE parameter bits (P25 order) with a valid pitch code b0 (0-207). */
    static int[] voiceParams(Random r) {
        int[] u = new int[88];
        for (int k = 0; k < 88; k++) u[k] = r.nextInt(2);
        int b0 = r.nextInt(208);
        for (int j = 0; j < 6; j++) u[j] = b0 >> (7 - j) & 1;
        u[85] = b0 >> 1 & 1; u[86] = b0 & 1;
        return u;
    }
}
