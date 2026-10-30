package p25monitor.nativeengine;

import p25monitor.p25.P25Framer;
import p25monitor.sdr.IqSource;
import p25monitor.sdr.RtlSdr;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * End-to-end live-path test with a mock librtlsdr (test/mockrtl) that drops samples the way the dongle does when no
 * USB transfer is queued. Runs the real RtlSdr class -> ChannelDecoder at 2.4 MS/s for a few seconds and scores the
 * IMBE voice frames against what was transmitted. Run with -Dtrunkdeck.librtlsdr=/path/libmockrtlsdr.so and MOCK_RTL_FILE.
 */
public class MockLive {
    @SuppressWarnings("unchecked")
    public static void main(String[] a) throws Exception {
        double secs = Double.parseDouble(System.getProperty("secs", "12"));
        List<byte[]> sent = (List<byte[]>) new ObjectInputStream(new FileInputStream(System.getenv("MOCK_RTL_FILE") + ".sent")).readObject();
        Set<String> want = new HashSet<>(); for (byte[] s : sent) want.add(Arrays.toString(s));
        String jm = System.getProperty("jmbe"); if (jm != null) EngineLibs.loadJmbe(Path.of(jm));
        EngineLibs.Imbe im = EngineLibs.jmbeReady() ? EngineLibs.newImbe() : null;
        long[] st = new long[6]; // frames, exact, eT sum, muted, ldus, nidBad
        RtlSdr d = new RtlSdr(RtlSdr.list().get(0));
        d.open(); d.configure(0, 297, false);
        long center = 420_400_000L; d.tune(center);
        ChannelDecoder voice = new ChannelDecoder(2.4e6, center, center + 150_000, new P25Framer.Listener() {
            public void ldu(int nac, int duid, byte[][] f, P25Framer.LinkControl lc, P25Framer.EncryptionSync e) {
                st[4]++;
                for (byte[] x : f) {
                    st[0]++; if (want.contains(Arrays.toString(x))) st[1]++;
                    if (im != null) { int[] s = im.stats(x); if (s != null) { st[2] += s[0]; if (s[0] >= 12) st[3]++; } }
                }
            }
        }, 0);
        // the rest of a typical voice-dongle load: a second decoder and the I/Q recorder
        ChannelDecoder other = new ChannelDecoder(2.4e6, center, center - 300_000, new P25Framer.Listener() {}, 0);
        if (Boolean.parseBoolean(System.getProperty("tap", "true"))) voice.tap = new ChannelDecoder.IqTap(Path.of("/tmp/mock/tap.wav"), 2.4e6, 150_000, 30);
        d.start(new IqSource.Listener() {
            public void samples(float[] i, float[] q, int n) { voice.process(i, q, n); other.process(i, q, n); }
            public void gap() { voice.gap(); other.gap(); }
        });
        Thread.sleep((long) (secs * 1000));
        d.stop();
        if (voice.tap != null) voice.tap.close();
        System.out.printf(Locale.ROOT, "%s: %d LDUs, %d IMBE frames, %.1f%% bit-exact, avg corrected bits %.2f, %.1f%% would be muted; usb %s, dropped blocks %s, queue peak %s%n",
                System.getProperty("label", "run"), st[4], st[0], 100.0 * st[1] / Math.max(1, st[0]), (double) st[2] / Math.max(1, st[0]), 100.0 * st[3] / Math.max(1, st[0]),
                fieldOr(d, "usbMode"), fieldOr(d, "droppedBlocks"), fieldOr(d, "queuePeak"));
        System.exit(0);
    }
    static Object fieldOr(Object o, String f) { try { return o.getClass().getField(f).get(o); } catch (Exception e) { return "n/a (1.1.2)"; } }
}
