package p25monitor.nativeengine;

import p25monitor.dsp.*;
import p25monitor.p25.*;
import java.util.*;

/** Fuzz / stress regression (1.4.3): every stage of the Airspy path with random block sizes / values; prints the first stack per site. */
public final class AirspyFuzz {
    static final Map<String, Integer> seen = new LinkedHashMap<>();
    static void report(String what, Throwable t) {
        String k = what + " " + t + " @ " + (t.getStackTrace().length > 0 ? t.getStackTrace()[0] : "?");
        if (seen.merge(k, 1, Integer::sum) == 1) { System.out.println("EXC " + what + ": " + t); for (int i = 0; i < Math.min(12, t.getStackTrace().length); i++) System.out.println("    at " + t.getStackTrace()[i]); }
    }
    static float val(Random r, int mode) {
        return switch (mode) { case 0 -> 0f; case 1 -> (float) r.nextGaussian(); case 2 -> (float) (r.nextGaussian() * 1e6); case 3 -> r.nextInt(50) == 0 ? Float.NaN : (float) r.nextGaussian();
            case 4 -> r.nextInt(50) == 0 ? Float.POSITIVE_INFINITY : (float) r.nextGaussian(); default -> (float) (1e-30 * r.nextGaussian()); };
    }

    static void setLong(Object o, String f, long v) { try { var fl = o.getClass().getDeclaredField(f); fl.setAccessible(true); fl.setLong(o, v); } catch (Exception e) { throw new RuntimeException(e); } }
    public static void main(String[] a) throws Exception {
        long secs = Long.parseLong(a.length > 0 ? a[0] : "60"), end = System.currentTimeMillis() + secs * 1000;
        Random r = new Random(Long.getLong("seed", 1));
        RealToComplex rc = new RealToComplex();
        PolyphaseBank bank = new PolyphaseBank(10e6);
        P25Framer.Listener nop = new P25Framer.Listener() {};
        List<ChannelDecoder> dec = new ArrayList<>();
        P2Framer.Listener p2l = new P2Framer.Listener() { public void voice(int c, byte[][] f, int d) {} public void mac(int c, P2Mac m) {} };
        for (int k = 0; k < 3; k++) dec.add(new ChannelDecoder(bank.outRate, 0, 10_000 * k, nop, 0, ChannelDecoder.Modulation.AUTO, false, 0x2D5));
        dec.add(new ChannelDecoder(bank.outRate, 0, 50_000, p2l, 0));
        long iter = 0;
        while (System.currentTimeMillis() < end) {
            iter++;
            int mode = r.nextInt(6);
            // RealToComplex: random even sizes (0 .. 2 blocks), pairs counter near overflow sometimes
            int n = r.nextInt(8) == 0 ? r.nextInt(4) * 2 : (r.nextInt(131072) & ~1);
            float[] x = new float[n]; for (int i = 0; i < n; i++) x[i] = val(r, mode);
            float[] oi = new float[n / 2 + 4], oq = new float[n / 2 + 4];
            if (r.nextInt(1000) == 0) setLong(rc, "pairs", Long.MAX_VALUE - r.nextInt(100000));
            int m = 0;
            try { m = rc.process(x, n, oi, oq); } catch (Throwable t) { report("RealToComplex n=" + n, t); }
            // filter bank: random subsets of bands, sometimes all, sometimes none
            boolean[] need = new boolean[bank.M];
            int pick = r.nextInt(4);
            for (int k = 0; k < bank.M; k++) need[k] = pick == 0 ? false : pick == 1 ? true : r.nextInt(6) == 0;
            if (r.nextInt(1000) == 0) setLong(bank, "frames", Long.MAX_VALUE - r.nextInt(1000));
            try { bank.process(oi, oq, m, need); } catch (Throwable t) { report("PolyphaseBank n=" + m, t); continue; }
            int on = bank.outN;
            for (int k = 0; k < bank.M; k++) if (need[k] && bank.outI[k].length < on) report("bank", new IllegalStateException("short output band " + k));
            // decoders on bank output (or random sub-band data), random block sizes
            for (ChannelDecoder d : dec) {
                int band = r.nextInt(bank.M);
                float[] bi, bq; int bn;
                if (need[band]) { bi = bank.outI[band]; bq = bank.outQ[band]; bn = on; }
                else { bn = r.nextInt(7000); bi = new float[bn]; bq = new float[bn]; for (int i = 0; i < bn; i++) { bi[i] = val(r, mode) * 0.01f; bq[i] = val(r, mode) * 0.01f; } }
                if (r.nextInt(200) == 0) d.samples = Long.MAX_VALUE - r.nextInt(1_000_000);
                try { d.process(bi, bq, bn); if (r.nextInt(100) == 0) d.gap(); if (r.nextInt(300) == 0) d.recenter(r.nextInt(2000) - 1000); }
                catch (Throwable t) { report("ChannelDecoder " + d.modulation + " n=" + bn + " mode=" + mode, t); }
            }
            // framers on random dibits
        }
        System.out.println("iterations " + iter + "; distinct exceptions " + seen.size());
        seen.forEach((k, v) -> System.out.println("  " + v + "x " + k));
        System.out.println(seen.isEmpty() ? "AirspyFuzz: PASS (no exception in any stage)" : "AirspyFuzz: FAIL");
        if (!seen.isEmpty()) System.exit(1);
    }
}
