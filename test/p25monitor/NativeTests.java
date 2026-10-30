package p25monitor;

import p25monitor.dsp.*;
import p25monitor.p25.*;
import java.util.*;

/** Unit + loopback tests for the TrunkDeck native P25 engine. Run: java -cp ... p25monitor.NativeTests */
public class NativeTests {
    static int pass, fail;
    static final Random R = new Random(25);

    static void check(boolean ok, String what) {
        if (ok) pass++; else { fail++; System.out.println("  FAIL: " + what); }
    }

    public static void main(String[] a) throws Exception {
        long t0 = System.currentTimeMillis();
        run("NID BCH(64,16,23)", NativeTests::nid);
        run("CRC-16 (TSBK)", NativeTests::crc);
        run("1/2-rate trellis", NativeTests::trellis);
        System.out.printf("    3-4 random bit errors: hard-decision failures %d/120; soft decision at level noise sigma 0.55: %d/300 failures%n", hardFail, softFail);
        check(hardFail <= 6 && softFail <= 6, "trellis margins");
        run("Hamming(10,6,3)", NativeTests::hamming);
        run("Reed-Solomon GF(64)", NativeTests::rs);
        run("Golay(24,12,8)", NativeTests::golay);
        run("TSBK field parsing", NativeTests::tsbkParse);
        run("C4FM modem loopback", NativeTests::modem);
        run("Framer: TSDU/LDU/TDU over C4FM with noise", NativeTests::frames);
        run("Full chain: 2.4 MS/s RTL-style u8 I/Q", NativeTests::fullChain);
        run("Voice channel FLL/AFC vs carrier offset", () -> { int[] r = p25monitor.nativeengine.VoicePathTests.fll(); pass += r[0]; fail += r[1]; });
        run("Audio jitter buffer (bursty P25 delivery)", () -> { int[] r = p25monitor.nativeengine.VoicePathTests.jitter(); pass += r[0]; fail += r[1]; });
        run("Audio jitter buffer at call start/end", () -> { int[] r = p25monitor.nativeengine.VoicePathTests.jitterEnds(); pass += r[0]; fail += r[1]; });
        run("LSM/CQPSK demodulator + Auto selection (simulcast)", () -> { int[] r = p25monitor.nativeengine.DemodTests.lsm(); pass += r[0]; fail += r[1]; });
        run("Public P25 recordings, every demodulator mode", () -> { int[] r = p25monitor.nativeengine.DemodTests.recordings(); pass += r[0]; fail += r[1]; });
        run("PPM sign (librtlsdr model, measure-apply-remeasure)", () -> { int[] r = p25monitor.nativeengine.DemodTests.ppm(); pass += r[0]; fail += r[1]; });
        run("Idle channel FLL anchored to the locked one", () -> { int[] r = p25monitor.nativeengine.DemodTests.anchor(); pass += r[0]; fail += r[1]; });
        run("Locked voice channel holds frequency between transmissions (1.3.0 frame-loss fix)", () -> { int[] r = p25monitor.nativeengine.FllHoldTests.run(); pass += r[0]; fail += r[1]; });
        run("Phase 2: RS/CRC/ISCH/scrambler, real capture (DSD-FME #304), two-slot H-DQPSK modem", () -> { try { int[][] rs = {p25monitor.nativeengine.P2Tests.fec(), p25monitor.nativeengine.P2Tests.realCapture(), p25monitor.nativeengine.P2Tests.twoSlots(false, 0, 0), p25monitor.nativeengine.P2Tests.twoSlots(true, 400, 0.2), p25monitor.nativeengine.P2Tests.twoSlots(true, -700, 0.3), p25monitor.nativeengine.P2Tests.cut()}; for (int[] r : rs) { pass += r[0]; fail += r[1]; } } catch (Exception x) { fail++; System.out.println("  FAIL: " + x); } });
        run("Phase 2: engine TDMA channel decoder (offset, gaps, both slots, encryption, wrong seed)", () -> { try { int[] r = p25monitor.nativeengine.P2Tests.channel(); pass += r[0]; fail += r[1]; } catch (Exception x) { fail++; System.out.println("  FAIL: " + x); } });
        run("Gain advice (overloaded RTL-SDR)", () -> { int[] r = p25monitor.nativeengine.DemodTests.gain(); pass += r[0]; fail += r[1]; });
        run("IMBE frame gating (BABA rules + noise mute)", () -> { int[] r = p25monitor.nativeengine.DemodTests.imbeGate(); pass += r[0]; fail += r[1]; });
        run("Lost samples: field signature, detector, gap handling", () -> { int[] r = p25monitor.nativeengine.LiveTests.lossTest(); pass += r[0]; fail += r[1]; });
        run("User recordings (private, 1.1.2 capture): breaks and clean spans", () -> { int[] r = p25monitor.nativeengine.LiveTests.recordingsTest(); pass += r[0]; fail += r[1]; });
        run("Live USB path: RtlSdr vs mock dongle (real FFM code)", () -> { int[] r = p25monitor.nativeengine.LiveTests.mockUsbTest(); pass += r[0]; fail += r[1]; });
        run("Display lit until the last audio is heard", () -> { int[] r = DisplayTests.lit(); pass += r[0]; fail += r[1]; });
        for (Runnable extra : EXTRA) extra.run();
        System.out.printf("%n%d checks passed, %d failed (%.1f s)%n", pass, fail, (System.currentTimeMillis() - t0) / 1000.0);
        if (fail > 0) System.exit(1);
    }

    static final List<Runnable> EXTRA = new ArrayList<>();

    interface T { void run() throws Exception; }
    static void run(String name, T t) {
        int f0 = fail, p0 = pass;
        try { t.run(); } catch (Throwable e) { fail++; System.out.println("  EXCEPTION " + e); e.printStackTrace(System.out); }
        System.out.printf("%-48s %s (%d checks)%n", name, fail == f0 ? "OK" : "FAILED", pass - p0 + fail - f0);
    }

    // ------------------------------------------------------------------ FEC
    static void nid() {
        // NID P bit, TIA-102.BAAC-A 2.11: DUIDs 5 and 10 have parity bit 1; 0,3,7,12,15 have 0
        for (int d : new int[]{0, 3, 7, 12, 15}) check((Fec.nidEncode((0x293 << 4) | d) & 1) == 0, "parity duid " + d);
        for (int d : new int[]{5, 10}) check((Fec.nidEncode((0x293 << 4) | d) & 1) == 1, "parity duid " + d);
        for (int i = 0; i < 300; i++) {
            int nac = R.nextInt(4096), duid = new int[]{0, 3, 5, 7, 10, 12, 15}[R.nextInt(7)];
            long w = Fec.nidEncode((nac << 4) | duid);
            int errs = R.nextInt(10);
            Set<Integer> pos = new HashSet<>();
            while (pos.size() < errs) pos.add(R.nextInt(64));
            for (int p : pos) w ^= 1L << p;
            int r = Fec.nidDecode(w, i % 2 == 0 ? nac : -1, 11);
            check(r >= 0 && (r >>> 8) == ((nac << 4) | duid) && (r & 0xff) == errs, "nid nac=" + nac + " errs=" + errs);
        }
        // 0x2D5: the (fictional) example NAC
        check((Fec.nidDecode(Fec.nidEncode((0x2D5 << 4) | 7), 0x2D5, 0) >>> 8) == 0x2D57, "example nac");
    }

    static void crc() {
        for (int i = 0; i < 200; i++) {
            byte[] b = new byte[12];
            R.nextBytes(b);
            Fec.crc16Fill(b);
            check(Fec.crc16Ok(b), "crc fill");
            b[R.nextInt(12)] ^= (byte) (1 << R.nextInt(8));
            check(!Fec.crc16Ok(b), "crc detects flip");
        }
    }

    static void trellis() {
        for (int i = 0; i < 300; i++) {
            byte[] d = new byte[12];
            R.nextBytes(d);
            byte[] enc = Fec.trellisEncode(d);
            int errs = i % 5; // up to 4 scattered errors
            Set<Integer> pos = new HashSet<>();
            while (pos.size() < errs) pos.add(R.nextInt(196));
            for (int p : pos) enc[p] ^= 1;
            byte[] dec = Fec.trellisDecode(enc, 0, new int[1]);
            boolean ok = dec != null && Arrays.equals(d, dec);
            if (errs <= 2) check(ok, "trellis errs=" + errs); else if (!ok) hardFail++;
            // soft decision on noisy levels
            float[] lv = new float[98];
            byte[] clean = Fec.trellisEncode(d);
            for (int k = 0; k < 98; k++) lv[k] = lvlOf((clean[2 * k] << 1) | clean[2 * k + 1]) + (float) (0.55 * R.nextGaussian());
            if (!Arrays.equals(Fec.trellisDecodeSoft(lv, 0), d)) softFail++;
        }
    }

    static int hardFail, softFail;
    static float lvlOf(int d) { return switch (d) { case 1 -> 3f; case 0 -> 1f; case 2 -> -1f; default -> -3f; }; }

    static void hamming() {
        for (int v = 0; v < 64; v++) {
            int cw = Fec.hamming10Encode(v);
            check(Fec.hamming10Decode(cw, null) == v, "ham clean " + v);
            for (int b = 0; b < 10; b++) check(Fec.hamming10Decode(cw ^ (1 << b), null) == v, "ham flip " + v + "/" + b);
        }
    }

    static void rs() {
        int[][] codes = {{24, 12}, {24, 16}, {36, 20}};
        for (int[] c : codes) {
            ReedSolomon rs = new ReedSolomon(c[0], c[1]);
            int t = (c[0] - c[1]) / 2;
            for (int i = 0; i < 200; i++) {
                int[] d = new int[c[1]];
                for (int j = 0; j < d.length; j++) d[j] = R.nextInt(64);
                int[] cw = rs.encode(d), rx = cw.clone();
                int errs = R.nextInt(t + 1);
                Set<Integer> pos = new HashSet<>();
                while (pos.size() < errs) pos.add(R.nextInt(c[0]));
                for (int p : pos) rx[p] ^= 1 + R.nextInt(63);
                check(rs.decode(rx) == errs && Arrays.equals(rx, cw), "rs(" + c[0] + "," + c[1] + ") errs=" + errs);
            }
        }
    }

    static void golay() {
        for (int i = 0; i < 300; i++) {
            int d = R.nextInt(4096), cw = Fec.golay24Encode(d), errs = R.nextInt(4);
            Set<Integer> pos = new HashSet<>();
            while (pos.size() < errs) pos.add(1 + R.nextInt(23)); // bit 0 is the overall parity bit
            for (int p : pos) cw ^= 1 << p;
            check(Fec.golay24Decode(cw, null) == d, "golay errs=" + errs);
        }
    }

    // ------------------------------------------------------------------ TSBK structures
    static byte[] grant(int so, int ch, int tg, int src) {
        return new byte[]{0x00, 0x00, (byte) so, (byte) (ch >> 8), (byte) ch, (byte) (tg >> 8), (byte) tg, (byte) (src >> 16), (byte) (src >> 8), (byte) src, 0, 0};
    }

    static byte[] idenVu(int id, long baseHz, int spacingHz, long txOffHz, boolean positive) {
        long base = baseHz / 5, sp = spacingHz / 125, off = Math.abs(txOffHz) / spacingHz;
        long v = ((long) id << 60) | (5L << 56) | ((positive ? 1L : 0L) << 55) | (off << 42) | (sp << 32) | base;
        byte[] b = new byte[12];
        b[0] = Tsbk.IDEN_UP_VU;
        for (int i = 0; i < 8; i++) b[2 + i] = (byte) (v >>> (56 - 8 * i));
        return b;
    }

    static byte[] idenTdma(int id, int type, long baseHz, int spacingHz) {
        long v = ((long) id << 60) | ((long) type << 56) | ((spacingHz / 125L) << 32) | (baseHz / 5);
        byte[] b = new byte[12];
        b[0] = Tsbk.IDEN_UP_TDMA;
        for (int i = 0; i < 8; i++) b[2 + i] = (byte) (v >>> (56 - 8 * i));
        return b;
    }

    static byte[] netStatus(int wacn, int sys, int ch) {
        long v = (0x01L << 56) | ((long) wacn << 36) | ((long) sys << 24) | ((long) ch << 8) | 0x70;
        byte[] b = new byte[12];
        b[0] = Tsbk.NET_STS_BCST;
        for (int i = 0; i < 8; i++) b[2 + i] = (byte) (v >>> (56 - 8 * i));
        return b;
    }

    static byte[] rfssStatus(int sys, int rfss, int site, int ch) {
        long v = (0x01L << 56) | (0x1L << 52) | ((long) sys << 40) | ((long) rfss << 32) | ((long) site << 24) | ((long) ch << 8) | 0x70;
        byte[] b = new byte[12];
        b[0] = Tsbk.RFSS_STS_BCST;
        for (int i = 0; i < 8; i++) b[2 + i] = (byte) (v >>> (56 - 8 * i));
        return b;
    }

    static void tsbkParse() {
        Tsbk g = new Tsbk(grant(0xC3, 0x1123, 51234, 1234567), 0x2D5);
        check(g.opcode == 0 && g.u16(5) == 51234 && g.u24(7) == 1234567 && g.u16(3) == 0x1123, "grant fields");
        check(Tsbk.emergency(g.u8(2)) && Tsbk.encrypted(g.u8(2)) && Tsbk.priority(g.u8(2)) == 3, "service options");
        ChannelMap map = new ChannelMap();
        Tsbk id = new Tsbk(idenVu(1, 411_425_000L, 12500, 5_000_000L, true), 0x2D5);
        ChannelMap.Band b = ChannelMap.Band.from(id);
        map.update(b);
        check(b.id == 1 && b.baseHz == 411_425_000L && b.spacingHz == 12500 && b.txOffsetHz == 5_000_000L && b.slots == 1, "iden vu " + b.baseHz + " " + b.txOffsetHz);
        check(map.frequency(0x1000 | 217) == 411_425_000L + 217 * 12500L, "channel freq");
        Tsbk td = new Tsbk(idenTdma(2, 3, 411_425_000L, 12500), 0x2D5);
        ChannelMap.Band b2 = ChannelMap.Band.from(td);
        map.update(b2);
        check(b2.slots == 2 && map.isTdma(0x2000 | 21) && map.timeslot(0x2000 | 21) == 1 && map.frequency(0x2000 | 21) == 411_425_000L + 10 * 12500L, "iden tdma");
        Tsbk ns = new Tsbk(netStatus(0xA4321, 0x2D5, 0x1000 | 217), 0x2D5);
        check(ns.bits(24, 20) == 0xA4321 && ns.bits(44, 12) == 0x2D5, "network status");
        Tsbk rf = new Tsbk(rfssStatus(0x2D5, 1, 1, 0x1000 | 217), 0x2D5);
        check(rf.bits(28, 12) == 0x2D5 && rf.u8(5) == 1 && rf.u8(6) == 1 && rf.u16(7) == (0x1000 | 217), "rfss status");
        check(g.describe(map).contains("TG:51234") && g.describe(map).contains("EMERGENCY"), "describe " + g.describe(map));
    }

    // ------------------------------------------------------------------ modem
    static float[] noisy(float[] iq, double snrDb, double freqOffHz, double fs) {
        double sigma = Math.sqrt(0.5 / Math.pow(10, snrDb / 10)); // signal power 1 (complex)
        float[] o = new float[iq.length];
        double ph = 0, w = 2 * Math.PI * freqOffHz / fs;
        for (int i = 0; i < iq.length / 2; i++) {
            double c = Math.cos(ph), s = Math.sin(ph);
            ph += w;
            double x = iq[2 * i], y = iq[2 * i + 1];
            o[2 * i] = (float) (x * c - y * s + sigma * R.nextGaussian());
            o[2 * i + 1] = (float) (x * s + y * c + sigma * R.nextGaussian());
        }
        return o;
    }

    /** Stretches time by 'ratio' (sample clock error) with linear interpolation. */
    static float[] stretch(float[] iq, double ratio) {
        int n = iq.length / 2, m = (int) (n / ratio) - 2;
        float[] o = new float[2 * m];
        for (int i = 0; i < m; i++) {
            double t = i * ratio; int k = (int) t; float f = (float) (t - k);
            o[2 * i] = iq[2 * k] + (iq[2 * k + 2] - iq[2 * k]) * f;
            o[2 * i + 1] = iq[2 * k + 1] + (iq[2 * k + 3] - iq[2 * k + 1]) * f;
        }
        return o;
    }

    static void demodulate(float[] iq, C4fmDemod d) {
        int n = iq.length / 2;
        float[] i = new float[n], q = new float[n];
        for (int k = 0; k < n; k++) { i[k] = iq[2 * k]; q[k] = iq[2 * k + 1]; }
        d.process(i, q, n);
    }

    static void modem() {
        double[][] cases = {{40, 0, 1.0}, {20, 700, 1.0}, {14, -900, 1.0001}, {12, 300, 0.9999}};
        for (double[] cs : cases) {
            int[] sync = new int[24];
            long fs = 0x5575F5FF77FFL;
            for (int i = 0; i < 24; i++) sync[i] = (int) ((fs >> (46 - 2 * i)) & 3);
            int[] data = new int[3000];
            for (int i = 0; i < data.length; i++) data[i] = R.nextInt(4);
            int[] all = new int[200 + 24 + data.length + 40];
            for (int i = 0; i < 200; i++) all[i] = R.nextInt(4);
            System.arraycopy(sync, 0, all, 200, 24);
            System.arraycopy(data, 0, all, 224, data.length);
            float[] iq = new C4fmModulator().modulate(all);
            iq = noisy(stretch(iq, cs[2]), cs[0], cs[1], 48000);
            List<Integer> got = new ArrayList<>();
            C4fmDemod d = new C4fmDemod(new C4fmDemod.Sink() {
                boolean on;
                public void sync(float q) { if (!on) { on = true; } }
                public void symbol(int dibit, float lvl) { if (on && got.size() < data.length) got.add(dibit); }
            });
            demodulate(iq, d);
            int errs = 0;
            for (int i = 0; i < Math.min(got.size(), data.length); i++) if (got.get(i) != data[i]) errs++;
            double ser = errs / (double) data.length;
            System.out.printf("    SNR %2.0f dB, offset %+5.0f Hz, clock %.4f: %d/%d symbols, SER %.4f, freq err est %+.0f Hz%n",
                    cs[0], cs[1], cs[2], got.size(), data.length, ser, d.freqErrorHz);
            check(got.size() == data.length && ser < (cs[0] >= 20 ? 0.001 : 0.03), "modem case snr=" + cs[0]);
            check(Math.abs(d.freqErrorHz - cs[1]) < 80, "freq estimate " + d.freqErrorHz);
        }
    }

    // ------------------------------------------------------------------ framer
    static class Collect implements P25Framer.Listener {
        final List<Tsbk> tsbks = new ArrayList<>();
        final List<byte[][]> ldus = new ArrayList<>();
        final List<P25Framer.LinkControl> lcs = new ArrayList<>();
        final List<P25Framer.EncryptionSync> ess = new ArrayList<>();
        int terms, nids;
        public void nid(int nac, int duid, int e) { nids++; }
        public void tsbk(Tsbk t) { tsbks.add(t); }
        public void ldu(int nac, int duid, byte[][] imbe, P25Framer.LinkControl lc, P25Framer.EncryptionSync e) {
            ldus.add(imbe); if (lc != null) lcs.add(lc); if (e != null) ess.add(e);
        }
        public void terminator(int nac, int duid) { terms++; }
    }

    static int[] concat(List<int[]> parts) {
        int n = 0; for (int[] p : parts) n += p.length;
        int[] r = new int[n]; int o = 0;
        for (int[] p : parts) { System.arraycopy(p, 0, r, o, p.length); o += p.length; }
        return r;
    }

    static byte[][] randomImbe() { byte[][] f = new byte[9][18]; for (byte[] x : f) R.nextBytes(x); return f; }

    static int[] lcHex(int tg, int src, int so) {
        byte[] lc = {0, 0, (byte) so, 0, (byte) (tg >> 8), (byte) tg, (byte) (src >> 16), (byte) (src >> 8), (byte) src};
        return P25Framer.hexbits(lc, 12);
    }

    static int[] essHex(int alg, int kid) {
        byte[] e = new byte[12];
        for (int i = 0; i < 9; i++) e[i] = (byte) R.nextInt(256);
        e[9] = (byte) alg; e[10] = (byte) (kid >> 8); e[11] = (byte) kid;
        return P25Framer.hexbits(e, 16);
    }

    static List<int[]> scenario(int nac, byte[][] imbeRef) {
        List<int[]> parts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            parts.add(P25Framer.buildTsdu(nac, netStatus(0xA4321, 0x2D5, 0x1000 | 217), rfssStatus(0x2D5, 1, 1, 0x1000 | 217), grant(0x02, 0x1000 | 100 + i, 2001 + i, 1000000 + i)));
        }
        parts.add(P25Framer.buildLdu(nac, P25Framer.DUID_LDU1, imbeRef, lcHex(2001, 1234567, 0)));
        parts.add(P25Framer.buildLdu(nac, P25Framer.DUID_LDU2, imbeRef, essHex(0x80, 0)));
        parts.add(P25Framer.buildLdu(nac, P25Framer.DUID_LDU1, imbeRef, lcHex(2001, 1234567, 0x40)));
        parts.add(P25Framer.buildLdu(nac, P25Framer.DUID_LDU2, imbeRef, essHex(0x84, 0x1234)));
        parts.add(P25Framer.buildTdu(nac));
        return parts;
    }

    static void frames() {
        for (double snr : new double[]{30, 14, 11}) {
            byte[][] imbe = randomImbe();
            List<int[]> parts = scenario(0x2D5, imbe);
            parts.add(0, randomDibits(300));
            parts.add(randomDibits(100));
            float[] iq = noisy(new C4fmModulator().modulate(concat(parts)), snr, 450, 48000);
            Collect c = new Collect();
            P25Framer fr = new P25Framer(c);
            fr.expectedNac = 0x2D5;
            demodulate(iq, new C4fmDemod(fr));
            long grants = c.tsbks.stream().filter(t -> t.opcode == 0).count();
            boolean imbeOk = !c.ldus.isEmpty() && Arrays.deepEquals(c.ldus.get(0), imbe);
            System.out.printf("    SNR %2.0f dB: TSBK ok %d (bad %d) of 60, grants %d/20, LDU %d/4, LC %d, ESS %d, TDU %d, IMBE bits exact: %s%n",
                    snr, fr.tsbkOk, fr.tsbkBad, grants, c.ldus.size(), c.lcs.size(), c.ess.size(), c.terms, imbeOk);
            if (snr >= 14) {
                check(fr.tsbkOk == 60 && grants == 20, "all tsbks at snr " + snr);
                check(c.ldus.size() == 4 && c.lcs.size() == 2 && c.ess.size() == 2 && c.terms == 1, "voice frames at snr " + snr);
                check(imbeOk, "imbe payload intact");
                check(c.lcs.get(0).talkgroup() == 2001 && c.lcs.get(0).source() == 1234567 && !c.lcs.get(0).encrypted(), "lc fields");
                check(c.lcs.get(1).encrypted(), "lc encrypted flag");
                check(!c.ess.get(0).encrypted() && c.ess.get(1).encrypted() && c.ess.get(1).algorithm() == 0x84 && c.ess.get(1).keyId() == 0x1234, "ess alg/key");
            } else check(fr.tsbkOk >= 45, "most tsbks at snr " + snr);
        }
    }

    static int[] randomDibits(int n) { int[] d = new int[n]; for (int i = 0; i < n; i++) d[i] = R.nextInt(4); return d; }

    // ------------------------------------------------------------------ full chain: simulated RTL-SDR
    static void fullChain() {
        // control channel 414.1375 MHz with the tuner centred at 413.9375 MHz (offset +200 kHz), 2.4 MS/s, 8-bit I/Q,
        // plus a strong adjacent-channel C4FM signal 25 kHz away and a 1.5 kHz tuning error
        List<int[]> parts = scenario(0x2D5, randomImbe());
        parts.add(0, randomDibits(200));
        parts.add(randomDibits(3000));
        int[] dib = concat(parts);
        float[] bb = new C4fmModulator().modulate(dib);
        float[] adj = new C4fmModulator().modulate(randomDibits(dib.length));
        int up = 50, n48 = bb.length / 2;
        byte[] u8 = SignalTools.upconvertU8(new float[][]{bb, adj}, new double[]{200_000 + 1500, 225_000}, new double[]{0.25, 0.35}, up, 0.02, R);
        Collect c = new Collect();
        P25Framer fr = new P25Framer(c);
        fr.expectedNac = 0x2D5;
        C4fmDemod demod = new C4fmDemod(fr);
        Channelizer ch = new Channelizer(2_400_000, 200_000);
        int block = 262144 / 2;
        float[] ii = new float[block], qq = new float[block], oi = new float[ch.maxOut(block)], oq = new float[ch.maxOut(block)];
        long t0 = System.nanoTime();
        for (int off = 0; off + 2 * block <= u8.length; off += 2 * block) {
            for (int k = 0; k < block; k++) { ii[k] = ((u8[off + 2 * k] & 0xff) - 127.5f) / 128f; qq[k] = ((u8[off + 2 * k + 1] & 0xff) - 127.5f) / 128f; }
            int m = ch.process(ii, qq, block, oi, oq);
            demod.process(oi, oq, m);
        }
        double secs = (System.nanoTime() - t0) / 1e9, audio = n48 / 48000.0;
        System.out.printf("    %.1f s of 2.4 MS/s I/Q in %.2f s CPU (%.0fx real time); TSBK ok %d bad %d, LDU %d, freq error est %+.0f Hz%n",
                audio, secs, audio / secs, fr.tsbkOk, fr.tsbkBad, c.ldus.size(), demod.freqErrorHz);
        check(fr.tsbkOk >= 58 && c.ldus.size() == 4 && c.terms == 1, "full chain decode");
        check(Math.abs(demod.freqErrorHz - 1500) < 100, "full chain freq error");
        check(audio / secs > 3, "real-time margin");
    }
}
