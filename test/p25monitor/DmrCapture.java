package p25monitor;

import p25monitor.dmr.*;
import p25monitor.sdr.IqFileSource;

import java.nio.file.*;
import java.util.*;

/**
 * Offline run of real DMR recordings. Looks in {@code -Ddmr.captures} (default {@code /tmp/dmr-captures})
 * and does nothing when that folder or the files are absent, so the suite still passes on a machine
 * without the downloads. The recordings themselves are not part of the source tree.
 *
 * <p>What a good copy of these files produced here (not checked in):
 * the 50 kHz 146.52 MHz baseband gave colour code 1, group 10, radio 1473;
 * the 1.024 MHz 446.00625 MHz simplex baseband gave colour code 5, group 11, radio 3112287;
 * the SigIdWiki / SDR# 48 kHz file gave colour code 13, group 100, radio 22, plus CSBK opcode 0x3D.
 * The SigIdWiki Capacity Plus, Connect Plus, Tier III, XPT and encrypted-voice samples are MP3, not I/Q.
 */
public class DmrCapture {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(System.getProperty("dmr.captures", "/tmp/dmr-captures"));
        if (!Files.isDirectory(dir)) {
            System.out.println("DmrCapture: no recordings in " + dir + " - skipped");
            return;
        }
        List<Path> wavs = new ArrayList<>();
        try (var ds = Files.walk(dir)) {
            ds.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return Files.isRegularFile(p) && (n.endsWith(".wav"));
            }).forEach(wavs::add);
        }
        wavs.sort(Comparator.comparing(p -> p.getFileName().toString()));
        if (wavs.isEmpty()) { System.out.println("DmrCapture: no wav files - skipped"); return; }
        for (Path p : wavs) {
            try { report(p); }
            catch (Exception e) { System.out.println("FILE " + p.getFileName() + " ERROR " + e.getMessage()); }
        }
    }

    static void report(Path p) throws Exception {
        IqFileSource src = new IqFileSource(p, 0, 0);
        src.realtime = false;
        List<float[]> ii = new ArrayList<>(), qq = new ArrayList<>();
        src.readAll((i, q, n) -> { ii.add(Arrays.copyOf(i, n)); qq.add(Arrays.copyOf(q, n)); });
        int n = 0; for (float[] a : ii) n += a.length;
        float[] i = new float[n], q = new float[n];
        int at = 0;
        for (int k = 0; k < ii.size(); k++) {
            System.arraycopy(ii.get(k), 0, i, at, ii.get(k).length);
            System.arraycopy(qq.get(k), 0, q, at, qq.get(k).length);
            at += ii.get(k).length;
        }
        double sr = src.sampleRate();
        boolean audio = channelsMatch(i, q);
        System.out.printf("%nFILE %s  rate %.0f Hz  %.1f s  %s%n", p.getFileName(), sr, n / sr, audio ? "AUDIO (both channels the same, not I/Q)" : "I/Q");
        if (audio) return;
        if (sr > 200_000) { float[][] c = toChannel(i, q, sr); i = c[0]; q = c[1]; sr = 48000; }
        else if (Math.abs(sr - 48000) > 1) { float[][] c = resample(i, q, sr, 48000); i = c[0]; q = c[1]; }
        Stats best = null;
        for (boolean invert : new boolean[]{false, true}) {
            for (int phase = 0; phase < 10; phase++) {
                int n2 = i.length - phase;
                float[] si = new float[n2], sq = new float[n2];
                System.arraycopy(i, phase, si, 0, n2);
                System.arraycopy(q, phase, sq, 0, n2);
                Stats s = run(si, sq, invert);
                s.phase = phase;
                if (best == null || s.syncs > best.syncs) best = s;
            }
        }
        System.out.println(best);
    }

    /** True when the two channels are the same waveform, which is a mono recording stored as stereo. */
    static boolean channelsMatch(float[] i, float[] q) {
        int n = Math.min(i.length, q.length);
        for (int start = 0; start + 4000 < n; start += n / 8) {
            double dot = 0, ei = 0, eq = 0;
            for (int k = start; k < start + 4000; k++) { dot += i[k] * q[k]; ei += i[k] * i[k]; eq += q[k] * q[k]; }
            if (ei > 1e-4 && eq > 1e-4 && dot / Math.sqrt(ei * eq) > 0.98) return true;
        }
        return false;
    }

    /** Low-pass and resample a wideband capture whose DMR channel is centred. */
    static float[][] toChannel(float[] i, float[] q, double sr) {
        int box = Math.max(1, (int) Math.round(sr / 16000.0));
        int n = i.length / box;
        float[] ii = new float[n], qq = new float[n];
        for (int k = 0; k < n; k++) {
            float si = 0, sq = 0;
            int b = k * box;
            for (int j = 0; j < box; j++) { si += i[b + j]; sq += q[b + j]; }
            ii[k] = si / box; qq[k] = sq / box;
        }
        return resample(ii, qq, sr / box, 48000);
    }

    static float[][] resample(float[] i, float[] q, double from, double to) {
        if (Math.abs(from - to) < 1) return new float[][]{i, q};
        int n = (int) (i.length * to / from);
        float[] oi = new float[n], oq = new float[n];
        double step = from / to;
        for (int k = 0; k < n; k++) {
            double x = k * step;
            int a = (int) x;
            int b = Math.min(a + 1, i.length - 1);
            float f = (float) (x - a);
            oi[k] = i[a] * (1 - f) + i[b] * f;
            oq[k] = q[a] * (1 - f) + q[b] * f;
        }
        return new float[][]{oi, oq};
    }

    static Stats run(float[] i, float[] q, boolean invert) {
        Stats s = new Stats();
        s.invert = invert;
        float[] ii = i, qq = q;
        if (invert) { qq = new float[q.length]; for (int k = 0; k < q.length; k++) qq[k] = -q[k]; }
        DmrFramer fr = new DmrFramer((burst, cach) -> s.take(burst, cach));
        new Fsk4Demod(fr).process(ii, qq, ii.length);
        return s;
    }

    static final class Stats {
        boolean invert;
        int phase;
        int syncs, voiceSyncs, dataBursts, slotOk, lcOk, lcEnc, csbkOk, csbkFail, embOk, voiceFrames, voiceCorrected;
        final Map<Integer, Integer> colors = new TreeMap<>();
        final Map<String, Integer> kinds = new TreeMap<>();
        final Set<String> ids = new LinkedHashSet<>();
        final Set<String> trunks = new LinkedHashSet<>();

        void take(DmrBurst b, DmrCach cach) {
            if (b == null) return;
            syncs++;
            kinds.merge(b.sync == null ? "?" : b.sync.name(), 1, Integer::sum);
            if (b.voice) {
                voiceSyncs++;
                if (b.raw != null) {
                    for (byte[] fr : DmrVoice.frames(b.raw)) {
                        voiceFrames++;
                        DmrAmbe.Voice v = DmrAmbe.decode(fr);
                        if (v.ok() && v.errors <= 2) voiceCorrected++;
                    }
                }
                return;
            }
            dataBursts++;
            if (!b.slotOk) return;
            slotOk++;
            colors.merge(b.colorCode, 1, Integer::sum);
            if (b.dataType == DmrBurst.CSBK) {
                DmrCsbk c = DmrCsbk.parse(b.info);
                if (c.ok) {
                    csbkOk++;
                    ids.add("CSBK op " + Integer.toHexString(c.opcode) + " fid " + c.fid + " tg " + c.target + " radio " + c.source + " lcn " + c.lcn + " slot " + c.slot + " rep " + c.repeater);
                    if (c.grant()) trunks.add("grant tg " + c.target + " radio " + c.source + " lcn " + c.lcn + " slot " + c.slot);
                    if (c.rest()) trunks.add("rest rep " + c.repeater + " slot " + c.slot + " op " + Integer.toHexString(c.opcode));
                } else csbkFail++;
            } else if (b.dataType == DmrBurst.VOICE_LC || b.dataType == DmrBurst.TERMINATOR) {
                DmrLc lc = DmrLc.parse(b.info, b.dataType);
                if (lc.ok) {
                    lcOk++;
                    if (lc.encrypted) lcEnc++;
                    else ids.add((b.dataType == DmrBurst.TERMINATOR ? "TERM " : "LC ") + "flco " + lc.flco + " tg " + lc.target + " radio " + lc.source + " cc " + b.colorCode);
                }
            }
        }

        public String toString() {
            return "  polarity " + (invert ? "inverted" : "normal") + " phase " + phase
                    + "  syncs " + syncs + " " + kinds
                    + "  data " + dataBursts + " slot-ok " + slotOk + " (" + pct(slotOk, dataBursts) + ")"
                    + "  colours " + colors
                    + "  LC ok " + lcOk + " encrypted " + lcEnc
                    + "  CSBK ok " + csbkOk + " failed " + csbkFail
                    + "  voice frames " + voiceFrames + " fec-ok " + voiceCorrected
                    + "\n  ids " + trim(ids)
                    + "\n  trunk " + trim(trunks);
        }

        static String pct(int a, int b) { return b == 0 ? "-" : String.format(Locale.ROOT, "%.0f%%", 100.0 * a / b); }
        static String trim(Collection<String> s) {
            if (s.isEmpty()) return "(none)";
            List<String> l = new ArrayList<>(s);
            return l.size() <= 12 ? l.toString() : l.subList(0, 12) + " … +" + (l.size() - 12);
        }
    }
}
