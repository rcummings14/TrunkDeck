package p25monitor;

import p25monitor.dsp.C4fmModulator;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Writes a 2.4 MS/s RTL-style .cu8 file with a continuous P25 voice transmission (real IMBE frames) for the mock dongle. */
public class MakeCu8 {
    public static void main(String[] a) throws Exception {
        Path out = Path.of(a[0]); int ldus = Integer.parseInt(a[1]); double off = Double.parseDouble(a[2]);
        List<byte[]> bank = BusySite.imbeFrames(); Random r = new Random(11);
        List<byte[]> sent = new ArrayList<>();
        int[] dib = DemodBench.voiceDibits(bank, ldus, sent, r);
        float[] bb = new C4fmModulator().modulate(dib);    // 48 kS/s complex
        try (ObjectOutputStream o = new ObjectOutputStream(new FileOutputStream(out + ".sent"))) { o.writeObject(new ArrayList<>(sent)); }
        int n48 = bb.length / 2, up = 50;
        double amp = 0.18, noise = 0.004;                  // ~ -15 dBFS carrier, ~33 dB SNR in the channel
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out), 1 << 20)) {
            byte[] blk = new byte[2 * up];
            double ph = 0, w = 2 * Math.PI * off / 2.4e6;
            for (int k = 0; k < n48 - 1; k++) {
                for (int j = 0; j < up; j++) {
                    double f = j / (double) up;
                    double x = bb[2 * k] * (1 - f) + bb[2 * k + 2] * f, y = bb[2 * k + 1] * (1 - f) + bb[2 * k + 3] * f;
                    double c = Math.cos(ph), s = Math.sin(ph); ph += w; if (ph > Math.PI) ph -= 2 * Math.PI;
                    double ii = amp * (x * c - y * s) + noise * r.nextGaussian(), qq = amp * (x * s + y * c) + noise * r.nextGaussian();
                    blk[2 * j] = (byte) Math.max(0, Math.min(255, Math.round(ii * 128 + 127.4)));
                    blk[2 * j + 1] = (byte) Math.max(0, Math.min(255, Math.round(qq * 128 + 127.4)));
                }
                os.write(blk);
            }
        }
        System.out.println("wrote " + out + ": " + ldus + " LDUs, " + Files.size(out) / 4.8e6 + " s");
    }
}
