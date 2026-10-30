package p25monitor.nativeengine;

import p25monitor.BusySite;
import p25monitor.DemodBench;
import p25monitor.dsp.C4fmModulator;
import p25monitor.p25.P25Framer;
import p25monitor.sdr.IqFileSource;
import java.nio.file.*;
import java.util.*;

/** 1.1.3: the live-path sample loss found in the user's recordings, its detection, and the fixed USB streaming. */
public final class LiveTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("  FAIL: " + what); } }
    static int[] result() { int[] r = {pass, fail}; pass = 0; fail = 0; return r; }

    static Path root() { return Path.of(System.getProperty("trunkdeck.src", ".")); }
    /**
     * Private recordings of a real system are not part of the shared source. They are read from
     * -Dtrunkdeck.testdata=DIR (default private-testdata; same layout: DIR/test/data, DIR/real-tests)
     * and, if present, from test/data and real-tests in the source tree. With none, the tests that need them are skipped.
     */
    static Path privateDir() { return Path.of(System.getProperty("trunkdeck.testdata", "private-testdata")); }
    static final String SKIP = "no private recordings (looked in " + privateDir() + " and the source tree's test/data, real-tests; set -Dtrunkdeck.testdata=DIR) - skipped";
    static List<Path> recordings() {
        List<Path> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Path base : new Path[]{privateDir(), root()})
            for (String d : new String[]{"test/data", "real-tests"}) {
                Path p = base.resolve(d);
                if (Files.isDirectory(p)) try (DirectoryStream<Path> ds = Files.newDirectoryStream(p, "*.wav")) { for (Path f : ds) if (!f.getFileName().toString().contains("-audio-") && seen.add(d + "/" + f.getFileName())) out.add(f); } catch (Exception ignored) {}
            }
        out.sort(java.util.Comparator.comparing(f -> f.getParent().getFileName().toString().equals("data") ? "test/data/" + f.getFileName() : "real-tests/" + f.getFileName()));
        return out;
    }

    static boolean jmbe() {
        if (EngineLibs.jmbeReady()) return true;
        String j = System.getProperty("jmbe", "jmbe/jmbe-2.0.2.jar");
        return Files.exists(Path.of(j)) && EngineLibs.loadJmbe(Path.of(j)) && EngineLibs.newImbe() != null;
    }

    /** Runs a 48 kS/s signal through a ChannelDecoder; deletes `drop` samples at every 2621.44-sample block boundary
     *  (what 1.1.2's USB reads did) and optionally reports those gaps. Returns {exact frames, frames, phase jumps}. */
    static long[] run(float[] sig, Set<String> want, int drop, boolean report) {
        long[] r = new long[3];
        ChannelDecoder d = new ChannelDecoder(48000, 0, 0, new P25Framer.Listener() {
            public void ldu(int nac, int duid, byte[][] f, P25Framer.LinkControl lc, P25Framer.EncryptionSync e) { for (byte[] x : f) { r[1]++; if (want.contains(Arrays.toString(x))) r[0]++; } }
        }, 0, ChannelDecoder.Modulation.AUTO, false, 0x2D5);
        int n = sig.length / 2; double next = 2621.44;
        int s = 0, blk = 0;
        while (s < n) {
            int e = Math.min(n, (int) next);
            float[] i = new float[e - s], q = new float[e - s];
            for (int k = s; k < e; k++) { i[k - s] = sig[2 * k]; q[k - s] = sig[2 * k + 1]; }
            d.process(i, q, e - s);
            s = e; next += 2621.44;
            if (drop > 0 && s < n && (!report || ++blk % 6 == 0)) { s = Math.min(n, s + drop); next += drop; if (report) d.gap(); }
        }
        r[2] = d.phaseJumps;
        return r;
    }

    /** The user's recordings: stream breaks on the USB read grid, and clean frames up to the first break. */
    public static int[] recordingsTest() throws Exception {
        if (!jmbe()) { System.out.println("   JMBE not available - skipped"); return result(); }
        List<Path> files = recordings();
        if (files.isEmpty()) { System.out.println("   " + SKIP); return result(); }
        for (Path f : files) {
            RealFiles.Result r = RealFiles.analyze(f);
            System.out.println("   " + r.row().replace("\n", "\n   "));
            check(r.jumps >= 5, f.getFileName() + ": stream breaks detected (" + r.jumps + ")");
            check(Double.isNaN(r.gridOffset) || r.framesBeforeFirstBreak == 0 || r.cleanBeforeFirstBreak >= 0.9 * r.framesBeforeFirstBreak,
                    f.getFileName() + ": frames before the first break are clean (" + r.cleanBeforeFirstBreak + "/" + r.framesBeforeFirstBreak + ")");
            check(r.cleanAsIs[0] < 0.5 * r.frames, f.getFileName() + ": as recorded, most frames are damaged (documents the 1.1.2 capture)");
            // the live decoder's own break detector sees them too
            long[] jumps = {0};
            ChannelDecoder d = new ChannelDecoder(48000, 0, 0, new P25Framer.Listener() {}, 0);
            new IqFileSource(f, 0, 0).readAll(d::process);
            check(d.phaseJumps >= 3, f.getFileName() + ": ChannelDecoder counts the breaks (" + d.phaseJumps + ")");
        }
        return result();
    }

    /** Synthetic: no false breaks on a clean signal; 1.1.2-style block losses reproduce the field signature; reported gaps never give garbage. */
    public static int[] lossTest() throws Exception {
        List<byte[]> bank = BusySite.imbeFrames();
        Random rnd = new Random(21);
        List<byte[]> sent = new ArrayList<>();
        int[] dib = DemodBench.voiceDibits(bank, 40, sent, rnd);
        float[] y = DemodBench.channel(new C4fmModulator().modulate(dib), new double[][]{{0, 1, 0}}, -400, 30, 0, rnd);
        Set<String> want = new HashSet<>(); for (byte[] s : sent) want.add(Arrays.toString(s));
        long[] clean = run(y, want, 0, false), lossy = run(y, want, 80, false), reported = run(y, want, 80, true);
        System.out.printf(Locale.ROOT, "   clean stream: %d/%d frames exact, %d breaks;  80 samples lost per 55 ms block: %d/%d exact, %d breaks;  losses every 330 ms, reported as gaps: %d/%d exact (%.0f%% of delivered)%n",
                clean[0], clean[1], clean[2], lossy[0], lossy[1], lossy[2], reported[0], reported[1], 100.0 * reported[0] / Math.max(1, reported[1]));
        check(clean[2] <= 2, "(almost) no stream breaks on a clean 30 dB signal (" + clean[2] + ")");
        check(clean[0] >= sent.size() * 0.97, "clean stream decodes");
        check(lossy[0] < lossy[1] * 0.5, "losses at block boundaries garble most frames (the field signature)");
        check(lossy[2] >= 5 * clean[2] + 8, "breaks detected (" + lossy[2] + ")");
        for (String f : new String[]{"P25_C4FM-CC_BEE0014C_IF.wav", "P25_CQPSK-CC_920650D5_IF.wav"}) {   // real over-the-air recordings: no false breaks
            Path pf = Path.of("samples", f);
            if (!Files.exists(pf)) continue;
            IqFileSource src = new IqFileSource(pf, 0, 0);
            ChannelDecoder d = new ChannelDecoder(src.sampleRate(), 0, 0, new P25Framer.Listener() {}, 0, ChannelDecoder.Modulation.AUTO, false, -1);
            src.readAll(d::process);
            System.out.printf(Locale.ROOT, "   %s: %d breaks in %.1f s checked%n", f, d.phaseJumps, d.jumpSamples / 48000.0);
            check(d.phaseJumps == 0, f + ": no false stream breaks on a real recording");
        }
        check(reported[0] >= reported[1] * 0.9, "with reported gaps, the delivered frames are clean (frames in progress are dropped, not garbled)");
        return result();
    }

    /** The real RtlSdr class against a mock librtlsdr that loses samples when no USB transfer is queued. */
    public static int[] mockUsbTest() throws Exception {
        Path gcc = Path.of("/usr/bin/gcc"), src = root().resolve("test/mockrtl/mock_rtlsdr.c");
        if (!Files.exists(gcc) || !Files.exists(src)) { System.out.println("   gcc or test/mockrtl missing - skipped"); return result(); }
        Path dir = Files.createTempDirectory("mockrtl"), lib = dir.resolve("libmockrtlsdr.so"), cu8 = dir.resolve("voice.cu8");
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString(), cp = System.getProperty("java.class.path");
        check(new ProcessBuilder(gcc.toString(), "-O2", "-shared", "-fPIC", "-o", lib.toString(), src.toString()).inheritIO().start().waitFor() == 0, "mock library builds");
        check(new ProcessBuilder(javaBin, "-cp", cp, "p25monitor.MakeCu8", cu8.toString(), "50", "149600").redirectErrorStream(true).start().waitFor() == 0, "test signal written");
        for (String mode : new String[]{"async", "sync"}) {
            ProcessBuilder pb = new ProcessBuilder(javaBin, "--enable-native-access=ALL-UNNAMED", "-Dtrunkdeck.librtlsdr=" + lib, "-Dsecs=6", "-Dtap=false", "-Dlabel=" + mode,
                    "-Dtrunkdeck.rtlSync=" + mode.equals("sync"), "-Dtrunkdeck.home=" + System.getProperty("trunkdeck.home", "."), "-Djmbe=" + System.getProperty("jmbe", ""), "-cp", cp, "p25monitor.nativeengine.MockLive");
            pb.environment().put("MOCK_RTL_FILE", cu8.toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            String line = out.lines().filter(l -> l.startsWith(mode + ":") || l.startsWith("mock:")).reduce("", (x, z) -> x + "   " + z + "\n");
            System.out.print(line);
            double exact = Double.NaN;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9.]+)% bit-exact").matcher(out);
            if (m.find()) exact = Double.parseDouble(m.group(1));
            if (mode.equals("async")) {
                check(exact >= 99, "async streaming: voice frames bit-exact (" + exact + "%)");
                check(out.contains(" 0 bytes LOST"), "async streaming: no samples lost in the mock dongle");
            } else check(exact >= 90, "fallback reader thread: voice frames mostly bit-exact (" + exact + "%)");
        }
        return result();
    }
}
