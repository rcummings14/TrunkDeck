package p25monitor;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Measures a feed's real playback speed from what listeners receive: records N seconds of a stream URL (Icecast MP3 or
 * an HLS .m3u8 the user can open) or reads a recorded file, decodes it with ffmpeg, and looks for TrunkDeck's
 * calibration signal (Streams -> Send test tone: 2 s of 1 kHz, then 5 beeps 1 s apart).
 *
 *   java -cp app/trunkdeck.jar p25monitor.FeedCheck URL-or-file [seconds]      (needs ffmpeg and ffprobe on the PATH)
 *
 * Prints the delivered format (ffprobe), the tone frequency and the beep spacing, and the speed factor
 * (1.00 = right speed, 2.00 = twice too fast, 0.73 = too slow). Only reads what it is given.
 */
public final class FeedCheck {
    public static void main(String[] a) throws Exception {
        if (a.length < 1) { System.out.println("usage: FeedCheck URL-or-file [seconds, default 60]"); System.exit(2); }
        int secs = a.length > 1 ? Integer.parseInt(a[1]) : 60;
        Path src = Paths.get(a[0]);
        Path file;
        if (Files.exists(src)) file = src;
        else {
            file = Files.createTempFile("feedcheck", ".mp3");
            System.out.println("Recording " + secs + " s from " + a[0] + " ...");
            run("ffmpeg", "-v", "error", "-y", "-t", String.valueOf(secs), "-i", a[0], "-c", "copy", "-f", "mp3", file.toString());
        }
        System.out.println("Delivered stream: " + run("ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_name,sample_rate,channels,bit_rate", "-of", "compact=p=0", file.toString()).trim());
        int sr = 16000;
        short[] s = decode(file, sr);
        System.out.println(String.format(Locale.ROOT, "Decoded %.1f s of audio", s.length / (double) sr));
        System.out.println(analyse(s, sr));
    }

    /** Finds the calibration signal and reports pitch, beep spacing and the speed factor. */
    public static String analyse(short[] s, int sr) {
        List<int[]> parts = new ArrayList<>();
        int blk = sr / 100, start = -1, last = -1;
        double peak = 1; for (short v : s) peak = Math.max(peak, Math.abs(v));
        double level = peak * 0.25;
        for (int i = 0; i + blk <= s.length; i += blk) {
            double e = 0; for (int k = i; k < i + blk; k++) e += (double) s[k] * s[k];
            boolean loud = Math.sqrt(e / blk) > level / 1.414;
            if (loud) { if (start < 0) start = i; last = i + blk; }
            else if (start >= 0 && i - last > sr * 0.05) { parts.add(new int[]{start, last}); start = -1; }
        }
        if (start >= 0) parts.add(new int[]{start, last});
        // the tone: a sound of 1-4 s followed by five short ones
        for (int i = 0; i + 5 < parts.size(); i++) {
            double tl = (parts.get(i)[1] - parts.get(i)[0]) / (double) sr;
            if (tl < 0.7 || tl > 6) continue;
            boolean beeps = true;
            for (int k = 1; k <= 5; k++) { double bl = (parts.get(i + k)[1] - parts.get(i + k)[0]) / (double) sr; if (bl < 0.05 || bl > 0.6) beeps = false; }
            if (!beeps) continue;
            int t0 = parts.get(i)[0], tn = parts.get(i)[1] - parts.get(i)[0];
            double hz = peakHz(s, t0 + tn / 4, Math.min(tn / 2, sr), sr);
            double span = (parts.get(i + 5)[0] - parts.get(i + 1)[0]) / (double) sr;
            double speed = 4.0 / span;
            return String.format(Locale.ROOT, "Test tone found: tone %.0f Hz (sent 1000 Hz, pitch factor %.2f), %.2f s long (sent 2.00 s); first to last beep %.2f s (sent 4.00 s)%n"
                    + "Speed: %.2fx  ->  %s", hz, hz / 1000, tl, span, speed,
                    Math.abs(speed - 1) < 0.03 ? "plays at the right speed" : speed > 1 ? "plays too FAST (choose a higher MP3 sample rate)" : "plays too SLOW (choose a lower MP3 sample rate)");
        }
        // no calibration signal: report the strongest steady tone, if any
        return "No test tone found (send it with Streams -> Send test tone and record again). " + parts.size() + " sounds detected.";
    }

    static double peakHz(short[] s, int from, int len, int sr) {
        double best = 0, bf = 0;
        for (double f = 100; f < sr / 2.0 - 50; f += 5) { double p = goertzel(s, from, len, sr, f); if (p > best) { best = p; bf = f; } }
        return bf;
    }
    static double goertzel(short[] s, int from, int len, int sr, double f) {
        double w = 2 * Math.PI * f / sr, k = 2 * Math.cos(w), a = 0, b = 0;
        for (int i = from; i < from + len && i < s.length; i++) { double x = s[i] + k * a - b; b = a; a = x; }
        return a * a + b * b - k * a * b;
    }

    static short[] decode(Path f, int sr) throws Exception {
        Process p = new ProcessBuilder("ffmpeg", "-v", "error", "-i", f.toString(), "-f", "s16le", "-ac", "1", "-ar", String.valueOf(sr), "-").start();
        byte[] b = p.getInputStream().readAllBytes(); p.getErrorStream().readAllBytes(); p.waitFor();
        short[] s = new short[b.length / 2];
        for (int i = 0; i < s.length; i++) s[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8));
        return s;
    }

    static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IOException(String.join(" ", cmd) + " failed: " + out.trim());
        return out;
    }
}
