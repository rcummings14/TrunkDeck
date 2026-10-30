package p25monitor.nativeengine;

import p25monitor.RadioSystem;
import p25monitor.Talkgroup;
import p25monitor.dsp.C4fmModulator;
import p25monitor.dsp.CqpskModulator;
import p25monitor.p25.*;
import p25monitor.sdr.IqSource;
import java.util.*;

/**
 * Built-in test signal: a simulated P25 trunked site (control channel + voice channels) generated with the
 * same C4FM modulator and FEC encoders used by the unit tests, at 480 kS/s. It exercises the whole TrunkDeck engine
 * (control channel decode, grants, call following, encryption flags, Phase 1 and Phase 2 TDMA voice) without a radio.
 * Voice frames carry silence-pattern IMBE / AMBE+2 data, so there is no speech to hear. Every sixth call is a Phase 2
 * call (H-DQPSK, time slot 2, scrambled with the site's WACN / system ID / NAC like a real Phase 2 channel).
 */
public final class SyntheticSource implements IqSource {
    static final double RATE = 480_000;
    final long cc, center, base;
    final int nac;
    final List<Integer> tgs = new ArrayList<>();
    final Random r = new Random(7);
    volatile boolean run;
    Thread th;

    final class Lane {
        final C4fmModulator mod = new C4fmModulator();
        final ArrayDeque<Integer> q = new ArrayDeque<>();
        double ph, offset;
        boolean on;
        float[] prev = {1, 0};
        // Phase 2 call: TDMA stream (slot 2 carries the call, slot 1 idle), 6000 symbols/s
        P2Builder.Stream p2; P2Builder.CallSlot slot; final CqpskModulator p2mod = new CqpskModulator(8); long p2StopAt;
    }

    final Lane ccLane = new Lane(), vLane = new Lane();
    // scenario
    long t;                    // simulated time in ms
    int callNo, callTg, callSrc, callCh, callSo, lduCount;
    long callEnd, nextCall = 2500;
    boolean phase2Call;
    int cycle;

    public SyntheticSource(RadioSystem sys, int nac) {
        this.nac = nac;
        cc = sys.controlChannelsMHz.isEmpty() ? 414_137_500L : Math.round(sys.controlChannelsMHz.get(0) * 1e6);
        center = cc - 200_000;
        base = center - 200_000;
        for (Talkgroup tg : sys.talkgroups) if (tg.decimal > 0 && !tg.muted && tgs.size() < 8) tgs.add(tg.decimal);
        if (tgs.isEmpty()) tgs.addAll(List.of(2001, 2002, 2003, 2004));
        ccLane.on = true;
        ccLane.offset = cc - center;
    }

    public long ccHz() { return cc; }
    @Override public double sampleRate() { return RATE; }
    @Override public long centerHz() { return center; }
    @Override public boolean tune(long hz) { return true; }
    @Override public String name() { return "Built-in test signal"; }
    @Override public boolean running() { return run; }

    byte[] tsbk(int op, long payload64) {
        byte[] b = new byte[12];
        b[0] = (byte) op;
        for (int i = 0; i < 8; i++) b[2 + i] = (byte) (payload64 >>> (56 - 8 * i));
        return b;
    }

    int chan(long hz) { return (1 << 12) | (int) ((hz - base) / 12500); }

    byte[] next() {
        long ccCh = chan(cc);
        switch (cycle++ % 6) {
            case 0: return tsbk(Tsbk.NET_STS_BCST, (0x01L << 56) | (0xA4321L << 36) | (0x2D5L << 24) | (ccCh << 8) | 0x70);
            case 1: return tsbk(Tsbk.RFSS_STS_BCST, (0x01L << 56) | (0x1L << 52) | (0x2D5L << 40) | (1L << 32) | (1L << 24) | (ccCh << 8) | 0x70);
            case 2: return tsbk(Tsbk.IDEN_UP_VU, (1L << 60) | (5L << 56) | (1L << 55) | ((5_000_000L / 12500) << 42) | (100L << 32) | (base / 5));
            case 3: return tsbk(Tsbk.IDEN_UP_TDMA, (2L << 60) | (3L << 56) | (1L << 55) | ((5_000_000L / 12500) << 42) | (100L << 32) | (base / 5));
            case 4: return tsbk(Tsbk.ADJ_STS_BCST, (0x01L << 56) | (0x1L << 52) | (0x2D5L << 40) | (1L << 32) | (5L << 24) | ((long) chan(cc - 12500 * 7) << 8) | 0x70);
            default: { int radio = 1000000 + r.nextInt(900000); return tsbk(Tsbk.GRP_AFF_RSP, ((long) tgs.get(r.nextInt(tgs.size())) << 24) | radio); }
        }
    }

    void refillCc() {
        List<byte[]> blocks = new ArrayList<>();
        if (t >= nextCall && callEnd == 0) startCall();
        if (callEnd > 0 && t < callEnd) {
            byte[] g = new byte[12];
            if (lduCount < 2 && !phase2Call || phase2Call && lduCount++ < 2) {
                g[0] = Tsbk.GRP_V_CH_GRANT; g[2] = (byte) callSo; g[3] = (byte) (callCh >> 8); g[4] = (byte) callCh;
                g[5] = (byte) (callTg >> 8); g[6] = (byte) callTg; g[7] = (byte) (callSrc >> 16); g[8] = (byte) (callSrc >> 8); g[9] = (byte) callSrc;
            } else {
                g[0] = Tsbk.GRP_V_CH_GRANT_UPDT; g[2] = (byte) (callCh >> 8); g[3] = (byte) callCh; g[4] = (byte) (callTg >> 8); g[5] = (byte) callTg;
                g[6] = g[2]; g[7] = g[3]; g[8] = g[4]; g[9] = g[5];
            }
            blocks.add(g);
        } else if (callEnd > 0 && t >= callEnd) { endCall(); }
        while (blocks.size() < 3) blocks.add(next());
        for (int d : P25Framer.buildTsdu(nac, blocks.toArray(new byte[0][]))) ccLane.q.add(d);
    }

    void startCall() {
        callNo++;
        callTg = tgs.get(callNo % tgs.size());
        callSrc = 1_000_000 + r.nextInt(8_000_000);
        phase2Call = callNo % 6 == 5;
        boolean enc = callNo % 5 == 3;
        callSo = (enc ? 0x40 : 0) | 4;
        long[] vf = {center - 150_000, center - 100_000, center - 50_000, center + 62_500};
        long f = vf[callNo % vf.length];
        callCh = phase2Call ? (2 << 12) | (int) ((f - base) / 12500) * 2 + 1 : chan(f);
        callEnd = t + 4000 + r.nextInt(3000);
        lduCount = 0;
        vLane.on = true;
        vLane.offset = f - center;
        vLane.q.clear();
        vLane.p2 = null;
        if (phase2Call) {
            vLane.slot = new P2Builder.CallSlot(); vLane.slot.nac = nac;
            vLane.slot.startPtt(callTg, callSrc, enc);
            vLane.p2 = new P2Builder.Stream(new P2Scrambler(0xA4321, 0x2D5, nac), new P2Builder.CallSlot(), vLane.slot);
            vLane.p2StopAt = 0;
        }
        encrypted = enc;
    }

    boolean encrypted;

    void endCall() {
        callEnd = 0;
        nextCall = t + 3000 + r.nextInt(4000);
        if (vLane.p2 != null) { vLane.slot.finish(); vLane.p2StopAt = t + 400; return; }   // MAC_END_PTT, then carrier off
        if (vLane.on) for (int d : P25Framer.buildTdu(nac)) vLane.q.add(d);
        vLane.on = false;
    }

    void refillVoice() {
        byte[][] imbe = new byte[9][18];
        byte[] lc = {0, 0, (byte) (encrypted ? 0x40 : 0), 0, (byte) (callTg >> 8), (byte) callTg, (byte) (callSrc >> 16), (byte) (callSrc >> 8), (byte) callSrc};
        byte[] ess = new byte[12];
        ess[9] = (byte) (encrypted ? 0x84 : 0x80);
        if (encrypted) { ess[10] = 0x12; ess[11] = 0x34; for (int i = 0; i < 9; i++) ess[i] = (byte) r.nextInt(); }
        int[] a = lduCount++ % 2 == 0 ? P25Framer.buildLdu(nac, P25Framer.DUID_LDU1, imbe, P25Framer.hexbits(lc, 12))
                : P25Framer.buildLdu(nac, P25Framer.DUID_LDU2, imbe, P25Framer.hexbits(ess, 16));
        for (int d : a) vLane.q.add(d);
    }

    @Override public void start(Listener l) {
        run = true;
        th = new Thread(() -> {
            final int dibitsPerBlock = 480; // 100 ms
            final int up = 10, n48 = dibitsPerBlock * 10, n = n48 * up;
            float[] oi = new float[n], oq = new float[n];
            long t0 = System.nanoTime(), blocks = 0;
            while (run) {
                Arrays.fill(oi, 0); Arrays.fill(oq, 0);
                for (Lane lane : new Lane[]{ccLane, vLane}) {
                    boolean active = lane == ccLane || lane.on || !lane.q.isEmpty();
                    if (!active) continue;
                    float[] bb;
                    if (lane.p2 != null) {
                        if (lane.p2StopAt > 0 && t >= lane.p2StopAt) { lane.p2 = null; lane.on = false; lane.q.clear(); continue; }
                        while (lane.q.size() < 600) for (int x : lane.p2.nextFragment()) lane.q.add(x);
                        int[] d = new int[600];
                        for (int k = 0; k < 600; k++) d[k] = lane.q.poll();
                        bb = lane.p2mod.modulate(d);
                    } else {
                        int[] d = new int[dibitsPerBlock];
                        for (int k = 0; k < dibitsPerBlock; k++) {
                            if (lane.q.isEmpty()) { if (lane == ccLane) refillCc(); else if (lane.on) refillVoice(); }
                            Integer v = lane.q.poll();
                            d[k] = v == null ? r.nextInt(4) : v;
                        }
                        bb = lane.mod.modulate(d);
                    }
                    double w = 2 * Math.PI * lane.offset / RATE, cr = Math.cos(lane.ph), ci = Math.sin(lane.ph), sr = Math.cos(w), si = Math.sin(w);
                    float pi = lane.prev[0], pq = lane.prev[1];
                    float amp = lane == ccLane ? 0.3f : 0.25f;
                    for (int k = 0; k < n48; k++) {
                        float xi = bb[2 * k], xq = bb[2 * k + 1];
                        for (int u = 0; u < up; u++) {
                            float f = (u + 1) / (float) up;
                            float re = pi + (xi - pi) * f, im = pq + (xq - pq) * f;
                            int o = k * up + u;
                            oi[o] += amp * (float) (re * cr - im * ci);
                            oq[o] += amp * (float) (re * ci + im * cr);
                            double nr = cr * sr - ci * si; ci = cr * si + ci * sr; cr = nr;
                        }
                        pi = xi; pq = xq;
                    }
                    lane.prev[0] = pi; lane.prev[1] = pq;
                    lane.ph = Math.atan2(ci, cr);
                }
                for (int k = 0; k < n; k++) { oi[k] += (float) (0.03 * r.nextGaussian()); oq[k] += (float) (0.03 * r.nextGaussian()); }
                t += 100;
                l.samples(oi, oq, n);
                blocks++;
                long due = t0 + blocks * 100_000_000L, now = System.nanoTime();
                if (due > now) try { Thread.sleep((due - now) / 1_000_000); } catch (InterruptedException e) { break; }
            }
            run = false;
        }, "test-signal");
        th.setDaemon(true);
        th.start();
    }

    @Override public void stop() { run = false; if (th != null) th.interrupt(); }
}
