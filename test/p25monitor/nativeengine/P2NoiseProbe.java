package p25monitor.nativeengine;

import java.nio.file.*;
import java.util.*;

/** Dev probe: AMBE+2 error statistics of random frames (noise) vs real frames, to set the noise-mute threshold. */
public final class P2NoiseProbe {
    public static void main(String[] a) throws Exception {
        System.setProperty("trunkdeck.lib", a.length > 1 ? a[1] : "lib");
        if (!EngineLibs.loadJmbe(Path.of(a[0]))) { System.out.println("JMBE: " + EngineLibs.jmbeStatus()); return; }
        EngineLibs.Ambe c = EngineLibs.newAmbe();
        for (int src = 0; src < 2; src++) {
            List<byte[]> fr = new ArrayList<>();
            if (src == 0) { Random r = new Random(1); for (int i = 0; i < 20000; i++) { byte[] x = new byte[9]; r.nextBytes(x); fr.add(x); } }
            else fr = P2Tests.bank();
            int[] tot = new int[8]; int mute = 0;
            for (byte[] x : fr) { int[] s = c.stats(x); tot[Math.min(7, s[0])]++; if (s[3] != 0) mute++; }
            if (src == 1) { EngineLibs.Imbe im = EngineLibs.newImbe(); for (int rep = 0; rep < 2; rep++) { long t0 = System.nanoTime(); int k = 0; for (int i = 0; i < 20; i++) for (byte[] x : fr) { c.decode(x); k++; } double us = (System.nanoTime() - t0) / 1e3 / k;
                System.out.printf(java.util.Locale.ROOT, "AMBE+2 decode: %.1f us per 20 ms frame = %.2f%% of one core per talking slot%n", us, us / 20000 * 100);
                if (im != null) { byte[] f = new byte[18]; long t1 = System.nanoTime(); for (int i = 0; i < k; i++) im.decode(f); double u2 = (System.nanoTime() - t1) / 1e3 / k; System.out.printf(java.util.Locale.ROOT, "IMBE decode:   %.1f us per 20 ms frame = %.2f%% of one core per call%n", u2, u2 / 20000 * 100); } } }
            System.out.println((src == 0 ? "random" : "real  ") + " n=" + fr.size() + "  e0+e1 histogram 0..7+ " + Arrays.toString(tot) + "  c0 uncorrectable " + mute);
        }
    }
}
