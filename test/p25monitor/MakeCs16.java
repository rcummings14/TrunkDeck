package p25monitor;

import p25monitor.dsp.C4fmModulator;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Writes a CS16 (interleaved 16-bit I/Q, little-endian) file at any sample rate with a continuous P25 voice
 * transmission (real IMBE frames) at an offset from the centre, for the mock SoapySDR module (test/mocksoapy).
 * Args: out ldus offsetHz rate [dc] - dc adds a constant offset (HackRF-like DC spike, in full-scale units).
 */
public class MakeCs16 {
    public static void main(String[] a) throws Exception {
        Path out = Path.of(a[0]); int ldus = Integer.parseInt(a[1]); double off = Double.parseDouble(a[2]), rate = Double.parseDouble(a[3]);
        double dc = a.length > 4 ? Double.parseDouble(a[4]) : 0;
        List<byte[]> bank = BusySite.imbeFrames(); Random r = new Random(11);
        List<byte[]> sent = new ArrayList<>();
        int[] dib = DemodBench.voiceDibits(bank, ldus, sent, r);
        float[] bb = new C4fmModulator().modulate(dib);    // 48 kS/s complex
        try (ObjectOutputStream o = new ObjectOutputStream(new FileOutputStream(out + ".sent"))) { o.writeObject(new ArrayList<>(sent)); }
        int n48 = bb.length / 2;
        long n = (long) ((n48 - 2) * rate / 48000.0);
        double amp = 0.18, noise = 0.004;
        double ph = 0, w = 2 * Math.PI * off / rate;
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out), 1 << 20)) {
            ByteBuffer b = ByteBuffer.allocate(4 * 8192).order(ByteOrder.LITTLE_ENDIAN);
            for (long k = 0; k < n; k++) {
                double t = k * 48000.0 / rate; int j = (int) t; double f = t - j;
                double x = bb[2 * j] * (1 - f) + bb[2 * j + 2] * f, y = bb[2 * j + 1] * (1 - f) + bb[2 * j + 3] * f;
                double c = Math.cos(ph), s = Math.sin(ph); ph += w; if (ph > Math.PI) ph -= 2 * Math.PI;
                double ii = amp * (x * c - y * s) + noise * r.nextGaussian() + dc, qq = amp * (x * s + y * c) + noise * r.nextGaussian() - dc;
                b.putShort((short) Math.max(-32768, Math.min(32767, Math.round(ii * 32768))));
                b.putShort((short) Math.max(-32768, Math.min(32767, Math.round(qq * 32768))));
                if (!b.hasRemaining()) { os.write(b.array(), 0, b.position()); b.clear(); }
            }
            os.write(b.array(), 0, b.position());
        }
        System.out.printf(Locale.ROOT, "wrote %s: %d LDUs, %.1f s at %.2f MS/s%n", out, ldus, n / rate, rate / 1e6);
    }
}
