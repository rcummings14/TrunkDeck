package p25monitor.ysf;

import p25monitor.dmr.DmrAmbe;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Test transmitter for Yaesu System Fusion: header, voice frames (DN, VW or V/D mode 1) and terminator, built with
 * {@link YsfFrame}'s encoders, then C4FM-modulated as a radio sends it (root raised cosine alpha 0.2, +/-2.7 kHz outer
 * deviation, 4800 baud) at 48 kHz.
 */
public final class YsfSignal {
    private YsfSignal() {}

    /** One transmission. {@code voice[k]} holds frame k's five AMBE+2 vectors {c0, c1, c2, c3} (DN and V/D mode 1). */
    public static final class Tx {
        public String source = "KD8ABC", dest = "ALL", uplink = "", downlink = "";
        public int dgid = 0, dt = YsfFrame.DT_VD2, frames = 21;
        public int[][][] voice;
        /** VW: the IMBE frames sent, in order. */
        public final List<byte[]> imbe = new ArrayList<>();
        public boolean header = true, terminator = true;
    }

    public static Tx tx(int frames, int dt, long seed) {
        Tx t = new Tx();
        t.frames = frames; t.dt = dt;
        Random r = new Random(seed);
        t.voice = new int[frames][5][];
        for (int k = 0; k < frames; k++) for (int j = 0; j < 5; j++)
            t.voice[k][j] = new int[]{r.nextInt(4096), r.nextInt(4096), r.nextInt(2048), r.nextInt(16384)};
        return t;
    }

    /** The frames as bits (0/1), 960 each. */
    public static List<byte[]> frames(Tx t) {
        List<byte[]> out = new ArrayList<>();
        byte[] csd = new byte[20];
        System.arraycopy(YsfFrame.callsignBytes(t.dest), 0, csd, 0, 10);
        System.arraycopy(YsfFrame.callsignBytes(t.source), 0, csd, 10, 10);
        int ft = 6;
        if (t.header) out.add(control(YsfFrame.FI_HEADER, t, csd));
        Random r = new Random(t.frames * 31L + t.dt);
        for (int k = 0; k < t.frames; k++) {
            int fn = k % (ft + 1);
            byte[] f = new byte[YsfFrame.BITS];
            YsfFrame.putFich(f, new YsfFrame.Fich(YsfFrame.FI_COMMS, 0, 0, 0, fn, ft, t.dt, 2, false, t.dgid));
            switch (t.dt) {
                case YsfFrame.DT_VD2 -> {
                    String text = switch (fn) { case 0 -> t.dest; case 1 -> t.source; case 2 -> t.downlink; case 3 -> t.uplink; default -> ""; };
                    YsfFrame.putDch(f, YsfFrame.callsignBytes(text), true);
                    YsfFrame.putVd2Voice(f, t.voice[k]);
                }
                case YsfFrame.DT_VD1 -> {
                    YsfFrame.putDch(f, fn == 0 ? csd : new byte[20], true);
                    for (int j = 0; j < 5; j++) {
                        int[] v = t.voice[k][j];
                        byte[] bits = YsfFrame.bitsOf(DmrAmbe.encode(v[0], v[1], v[2], v[3]));
                        System.arraycopy(bits, 0, f, YsfFrame.PAYLOAD + 72 + j * 144, 72);
                    }
                }
                case YsfFrame.DT_VOICE_FR -> {
                    for (int j = fn == 0 ? 3 : 0; j < 5; j++) {
                        byte[] imbe = new byte[18];
                        r.nextBytes(imbe);
                        t.imbe.add(imbe);
                        System.arraycopy(YsfFrame.bitsOf(imbe), 0, f, YsfFrame.PAYLOAD + j * 144, 144);
                    }
                }
                default -> {}
            }
            out.add(f);
        }
        if (t.terminator) out.add(control(YsfFrame.FI_TERMINATOR, t, csd));
        return out;
    }

    private static byte[] control(int fi, Tx t, byte[] csd) {
        byte[] f = new byte[YsfFrame.BITS];
        YsfFrame.putFich(f, new YsfFrame.Fich(fi, 0, 0, 0, 0, 6, t.dt, 2, false, t.dgid));
        YsfFrame.putDch(f, csd, true);
        return f;
    }

    /** All frames as dibits, with {@code quietSymbols} of random noise-like dibits before and after. */
    public static int[] dibits(List<byte[]> frames, int quietSymbols, long seed) {
        Random r = new Random(seed);
        List<Integer> d = new ArrayList<>();
        for (int k = 0; k < quietSymbols; k++) d.add(r.nextInt(4));
        for (byte[] f : frames) for (int x : YsfFrame.dibitsOfBits(f)) d.add(x);
        for (int k = 0; k < quietSymbols; k++) d.add(r.nextInt(4));
        return d.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Root raised cosine C4FM at 48 kHz: {i, q}. {@code hzPerLevel} 900 gives +/-2.7 kHz outer deviation.
     * {@code offsetHz}: carrier offset. {@code noise}: Gaussian noise per component (signal amplitude 1).
     */
    public static float[][] modulate(int[] dibits, double hzPerLevel, double offsetHz, double noise, long seed) {
        final int sps = 10, span = 8 * sps + 1, mid = span / 2;
        double a = 0.2;
        double[] h = new double[span];
        double sum = 0;
        for (int k = 0; k < span; k++) {
            double t = (k - mid) / (double) sps, v;
            if (t == 0) v = 1 - a + 4 * a / Math.PI;
            else if (Math.abs(Math.abs(4 * a * t) - 1) < 1e-9) v = a / Math.sqrt(2) * ((1 + 2 / Math.PI) * Math.sin(Math.PI / (4 * a)) + (1 - 2 / Math.PI) * Math.cos(Math.PI / (4 * a)));
            else v = (Math.sin(Math.PI * t * (1 - a)) + 4 * a * t * Math.cos(Math.PI * t * (1 + a))) / (Math.PI * t * (1 - 16 * a * a * t * t));
            h[k] = v; sum += v;
        }
        for (int k = 0; k < span; k++) h[k] *= sps / sum;
        int n = dibits.length * sps;
        double[] imp = new double[n + span];
        for (int k = 0; k < dibits.length; k++) imp[k * sps] = switch (dibits[k] & 3) { case 1 -> 3; case 0 -> 1; case 2 -> -1; default -> -3; };
        float[] i = new float[n], q = new float[n];
        Random r = new Random(seed);
        double ph = 0;
        for (int s = 0; s < n; s++) {
            double f = 0;
            for (int j = 0; j < span; j++) { int x = s - j + mid; if (x >= 0 && x < imp.length) f += h[j] * imp[x]; }
            ph += 2 * Math.PI * (f * hzPerLevel + offsetHz) / 48000;
            if (ph > Math.PI) ph -= 2 * Math.PI; else if (ph < -Math.PI) ph += 2 * Math.PI;
            i[s] = (float) (Math.cos(ph) + noise * r.nextGaussian());
            q[s] = (float) (Math.sin(ph) + noise * r.nextGaussian());
        }
        return new float[][]{i, q};
    }
}
