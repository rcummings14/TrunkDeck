package p25monitor.nativeengine;

import p25monitor.dsp.C4fmDemod;
import p25monitor.p25.P25Framer;
import p25monitor.sdr.IqFileSource;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/**
 * Analysis of real voice-channel I/Q recordings (48 kS/s .wav from "Record voice-channel I/Q"): finds stream breaks
 * (carrier phase jumps that a P25 signal cannot make), measures IMBE errors per frame, and - for recordings made by
 * 1.1.2 and older, which lost samples at every 262144-byte USB read - repairs the bit stream by re-inserting the
 * missing symbols at each read boundary (the number is found by trying 0-48 and keeping the one the FEC accepts).
 * The repair is an analysis tool to show what the signal itself contained; 1.1.3 does not lose these samples.
 * Usage: RealFiles out-dir file.wav...   (needs JMBE: -Djmbe=... -Dtrunkdeck.home=...)
 */
public class RealFiles {
    static final double BLOCK = 131072 / 50.0;   // one 262144-byte RTL read at 2.4 MS/s, in 48 kS/s samples

    public static final class Result {
        public String name; public double seconds; public long jumps; public double gridOffset = Double.NaN;
        public int ldus, frames; public int[] cleanAsIs = new int[3], cleanRepaired = new int[3];   // {eT<=3 frames, muted, sum eT}
        public int[] histAsIs = new int[17], histRepaired = new int[17], e0AsIs = new int[5], e0Repaired = new int[5];
        public int cleanBeforeFirstBreak, framesBeforeFirstBreak;
        public Map<Integer, Integer> insertions = new TreeMap<>();
        public float[][] audio = new float[3][];   // before (1.1.2 rules), 1.1.3 rules as received, repaired
        public String row() {
            return String.format(Locale.ROOT, "%s: %.1f s, %d LDUs / %d IMBE frames, %d stream breaks (phase jumps) on a %.2f-sample grid%n"
                    + "    as received : %5.1f%% clean (0-3 corrected bits), avg %.2f bits/frame, %5.1f%% muted; e0 hist %s, eT hist %s%n"
                    + "    frames ending before the first break after a sync: %d of %d clean%n"
                    + "    repaired    : %5.1f%% clean, avg %.2f bits/frame, %5.1f%% muted; e0 hist %s, eT hist %s; symbols re-inserted per break %s",
                    name, seconds, ldus, frames, jumps, BLOCK, 100.0 * cleanAsIs[0] / Math.max(1, frames), cleanAsIs[2] / (double) Math.max(1, frames), 100.0 * cleanAsIs[1] / Math.max(1, frames),
                    Arrays.toString(e0AsIs), Arrays.toString(histAsIs), cleanBeforeFirstBreak, framesBeforeFirstBreak,
                    100.0 * cleanRepaired[0] / Math.max(1, frames), cleanRepaired[2] / (double) Math.max(1, frames), 100.0 * cleanRepaired[1] / Math.max(1, frames),
                    Arrays.toString(e0Repaired), Arrays.toString(histRepaired), insertions);
        }
    }

    static byte[][] decodeLdu(List<Integer> dib, int[] duid) {
        byte[][][] out = new byte[1][][];
        P25Framer f = new P25Framer(new P25Framer.Listener() {
            public void ldu(int n, int d, byte[][] x, P25Framer.LinkControl lc, P25Framer.EncryptionSync e) { out[0] = x; duid[0] = d; }
        });
        f.enforceNac = false;
        f.sync(1);
        for (int d : dib) f.symbol(d, 0);
        return out[0];
    }

    public static Result analyze(Path wav) throws Exception {
        Result res = new Result(); res.name = wav.getFileName().toString();
        IqFileSource src = new IqFileSource(wav, 0, 0);
        if (src.sampleRate() != 48000) throw new IllegalArgumentException("expects the 48 kS/s recordings TrunkDeck writes");
        // 1) symbols with their sample times, segmented at each frame sync; carrier phase jumps
        List<List<long[]>> segs = new ArrayList<>(); List<Long> segStart = new ArrayList<>();
        long[] pos = {0}; List<Long> jumps = new ArrayList<>();
        C4fmDemod d = new C4fmDemod(new C4fmDemod.Sink() {
            public void sync(float q) { segs.add(new ArrayList<>()); segStart.add(pos[0]); }
            public void symbol(int dibit, float level) { if (!segs.isEmpty()) segs.get(segs.size() - 1).add(new long[]{dibit, pos[0]}); }
        });
        float[] prev = {0, 0}; double[] pw = {0, 0};
        long[] cur = {0};
        src.readAll((ii, qq, n) -> {
            float[] x = new float[1], y = new float[1];
            for (int k = 0; k < n; k++) {
                float a = ii[k], b = qq[k];
                pw[0] = 0.999 * pw[0] + 0.001 * (a * a + b * b);
                if (pw[0] > pw[1]) pw[1] = pw[0];
                float re = a * prev[0] + b * prev[1], im = b * prev[0] - a * prev[1];
                // only while the carrier is at full strength (not the noise after it drops)
                if (pw[0] > 0.5 * pw[1] && re < 0 && Math.abs(im) < -0.6f * re && a * a + b * b > 0.25 * pw[0] && prev[0] * prev[0] + prev[1] * prev[1] > 0.25 * pw[0]) jumps.add(cur[0] + k);
                prev[0] = a; prev[1] = b;
                pos[0] = cur[0] + k; x[0] = a; y[0] = b; d.process(x, y, 1);
            }
            cur[0] += n;
        });
        res.seconds = cur[0] / 48000.0; res.jumps = jumps.size();
        // 2) the USB read grid (the phase of the boundaries), from the detected jumps
        if (jumps.size() >= 3) {
            int[] hist = new int[(int) BLOCK + 1];
            for (long j : jumps) hist[(int) Math.floor(j % BLOCK)]++;
            int best = 0, bestN = 0, L = hist.length;
            for (int k = 0; k < L; k++) { int c = hist[(k + L - 1) % L] + hist[k] + hist[(k + 1) % L]; if (c > bestN) { bestN = c; best = k; } }
            if (bestN >= Math.max(4, jumps.size() * 0.2)) res.gridOffset = best + 0.5;
        }
        // 3) decode every LDU as received and repaired; render audio with each rule set
        EngineLibs.Imbe[] codec = {EngineLibs.newImbe(), EngineLibs.newImbe(), EngineLibs.newImbe()};
        EngineLibs.Imbe stat = EngineLibs.newImbe();
        int total = (int) (res.seconds * 8000) + 1600;
        for (int k = 0; k < 3; k++) res.audio[k] = new float[total];
        for (int s = 0; s < segs.size(); s++) {
            List<long[]> seg = segs.get(s);
            if (seg.size() < 800) continue;
            List<Integer> dib = new ArrayList<>(); for (long[] x : seg) dib.add((int) x[0]);
            int[] duid = {0};
            byte[][] asIs = decodeLdu(dib, duid);
            if (asIs == null) continue;
            res.ldus++;
            // breaks inside this LDU, as dibit indexes
            List<Integer> brk = new ArrayList<>();
            if (!Double.isNaN(res.gridOffset)) {
                long t0 = segStart.get(s) - 240, t1 = seg.get(seg.size() - 1)[1];
                for (long kb = (long) Math.ceil((t0 - res.gridOffset) / BLOCK); res.gridOffset + kb * BLOCK < t1; kb++) {
                    long bt = Math.round(res.gridOffset + kb * BLOCK);
                    int b = 0; while (b < seg.size() && seg.get(b)[1] < bt) b++;
                    brk.add(b);
                }
            }
            List<Integer> fixed = new ArrayList<>(dib); int shift = 0;
            for (int b : brk) {
                int bestD = 0, bestGood = -1;
                for (int ins = 0; ins <= 60; ins++) {
                    List<Integer> t = new ArrayList<>(fixed.subList(0, Math.min(fixed.size(), b + shift)));
                    for (int k = 0; k < ins; k++) t.add(0);
                    t.addAll(fixed.subList(Math.min(fixed.size(), b + shift), fixed.size()));
                    byte[][] f = decodeLdu(t, new int[1]); if (f == null) continue;
                    int good = 0; for (byte[] x : f) good += Math.max(0, 6 - stat.stats(x)[0]);   // soft FEC score
                    if (good > bestGood) { bestGood = good; bestD = ins; }
                }
                List<Integer> t = new ArrayList<>(fixed.subList(0, Math.min(fixed.size(), b + shift)));
                for (int k = 0; k < bestD; k++) t.add(0);
                t.addAll(fixed.subList(Math.min(fixed.size(), b + shift), fixed.size()));
                fixed = t; shift += bestD;
                res.insertions.merge(bestD, 1, Integer::sum);
            }
            byte[][] rep = decodeLdu(fixed, new int[1]);
            if (rep == null) rep = asIs;
            int[] OFF = {112, 256, 440, 624, 808, 992, 1176, 1360, 1536};
            int at = (int) Math.round(segStart.get(s) / 6.0);   // 48 kS/s -> 8 kS/s position of this LDU
            for (int f = 0; f < 9; f++) {
                res.frames++;
                int[] a = stat.stats(asIs[f]), r = stat.stats(rep[f]);
                res.histAsIs[Math.min(16, a[0])]++; res.histRepaired[Math.min(16, r[0])]++;
                res.e0AsIs[Math.min(4, Math.max(0, a[1]))]++; res.e0Repaired[Math.min(4, Math.max(0, r[1]))]++;
                res.cleanAsIs[2] += a[0]; res.cleanRepaired[2] += r[0];
                if (a[0] <= 3) res.cleanAsIs[0]++; if (r[0] <= 3) res.cleanRepaired[0]++;
                int endDibit = (int) (((OFF[f] + 144) / 2.0) * 36 / 35) - 24;
                if (brk.isEmpty() || endDibit < brk.get(0)) { res.framesBeforeFirstBreak++; if (a[0] <= 3) res.cleanBeforeFirstBreak++; }
                // audio: 1.1.2 rules (stale model-parameter counts, >= 12 or JMBE mute), 1.1.3 rules (this frame's counts)
                int[] old = codec[0].stats(asIs[f]);
                float[] p0 = codec[0].decode(asIs[f]);
                int oldErr = codec[0].errorsOfLastModel();
                boolean mute0 = old != null && (oldErr >= 12 || old[3] != 0);
                int[] n1 = codec[1].stats(asIs[f]); float[] p1 = codec[1].decode(asIs[f]);
                boolean mute1 = n1 != null && (n1[0] >= NativeEngine.IMBE_NOISE || n1[3] != 0);
                int[] n2 = codec[2].stats(rep[f]); float[] p2 = codec[2].decode(rep[f]);
                boolean mute2 = n2 != null && (n2[0] >= NativeEngine.IMBE_NOISE || n2[3] != 0);
                if (mute1) res.cleanAsIs[1]++; if (mute2) res.cleanRepaired[1]++;
                int o = at + f * 160 + 1440 / 9 * 0;
                for (int k = 0; k < 160 && o + k < total; k++) {
                    res.audio[0][o + k] = mute0 ? 0 : p0[k]; res.audio[1][o + k] = mute1 ? 0 : p1[k]; res.audio[2][o + k] = mute2 ? 0 : p2[k];
                }
            }
        }
        return res;
    }

    public static void writeWav(Path p, float[] a) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(44 + a.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + a.length * 2).put("WAVE".getBytes()).put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(a.length * 2);
        for (float v : a) b.putShort((short) Math.max(-32767, Math.min(32767, Math.round(v * 32767 * 0.9))));
        Files.write(p, b.array());
    }

    public static void main(String[] a) throws Exception {
        String jm = System.getProperty("jmbe", "jmbe/jmbe-2.0.2.jar");
        if (!EngineLibs.loadJmbe(Path.of(jm))) throw new IllegalStateException(EngineLibs.jmbeStatus());
        Path out = Path.of(a[0]); Files.createDirectories(out);
        for (int i = 1; i < a.length; i++) {
            Result r = analyze(Path.of(a[i]));
            System.out.println(r.row());
            String base = r.name.replace(".wav", "");
            writeWav(out.resolve(base + "-audio-before-1.1.2.wav"), r.audio[0]);
            writeWav(out.resolve(base + "-audio-1.1.3-as-recorded.wav"), r.audio[1]);
            writeWav(out.resolve(base + "-audio-repaired.wav"), r.audio[2]);
        }
    }
}
