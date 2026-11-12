package p25monitor.nativeengine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * A short recording of one receiver's raw complex samples, 16-bit I then 16-bit Q, little-endian ({@code .cs16}).
 * It stops by itself after the chosen number of minutes. The file name carries the sample rate and centre frequency
 * so TrunkDeck's I/Q file input can open it again.
 */
public final class RawIqCapture {
    public final Path file;
    public final double sampleRate;
    public final int minutes;
    private final FileChannel out;
    private final long maxSamples;
    private final ByteBuffer buf;
    private long samples;
    private boolean closed;

    public RawIqCapture(Path file, double sampleRate, int minutes) throws IOException {
        this.file = file;
        this.sampleRate = sampleRate;
        this.minutes = Math.max(1, minutes);
        this.maxSamples = (long) (sampleRate * 60.0 * this.minutes);
        Files.createDirectories(file.getParent());
        out = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        buf = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);
    }

    /** Bytes of a .cs16 file at this rate for this many minutes (4 bytes per complex sample). */
    public static long bytes(double sampleRate, int minutes) {
        if (sampleRate <= 0 || minutes <= 0) return 0;
        return (long) Math.ceil(sampleRate * 4.0 * 60.0 * minutes);
    }

    /** Plain-language size, for the warning shown before a recording starts. */
    public static String sizeText(long bytes) {
        if (bytes >= 1_000_000_000L) return String.format(Locale.ROOT, "%.1f GB", bytes / 1e9);
        if (bytes >= 1_000_000L) return String.format(Locale.ROOT, "%.0f MB", bytes / 1e6);
        return String.format(Locale.ROOT, "%.0f KB", bytes / 1e3);
    }

    public static String warning(double sampleRate, int minutes) {
        long one = bytes(sampleRate, 1);
        long all = bytes(sampleRate, minutes);
        return String.format(Locale.ROOT,
                "About %s per minute at %,.0f samples/s, %s for %d minute%s. 16-bit I/Q (.cs16). It stops by itself.",
                sizeText(one), sampleRate, sizeText(all), minutes, minutes == 1 ? "" : "s");
    }

    /** @return true when the time limit has been reached and the file is closed */
    public synchronized boolean write(float[] i, float[] q, int n) {
        if (closed || n <= 0) return closed;
        int take = (int) Math.min(n, Math.max(0, maxSamples - samples));
        try {
            int at = 0;
            while (at < take) {
                int room = buf.remaining() / 4;
                int m = Math.min(room, take - at);
                for (int k = 0; k < m; k++) {
                    buf.putShort(q15(i[at + k]));
                    buf.putShort(q15(q[at + k]));
                }
                at += m;
                samples += m;
                if (buf.remaining() < 4) flush();
            }
            if (samples >= maxSamples) { close(); return true; }
        } catch (IOException e) {
            closed = true;
        }
        return closed;
    }

    public synchronized void close() {
        if (closed && !out.isOpen()) return;
        closed = true;
        try { flush(); out.close(); } catch (IOException ignored) {}
    }

    public synchronized long samples() { return samples; }

    private void flush() throws IOException {
        buf.flip();
        while (buf.hasRemaining()) out.write(buf);
        buf.clear();
    }

    static short q15(float s) {
        if (s > 1f) s = 1f;
        else if (s < -1f) s = -1f;
        int v = Math.round(s * 32767f);
        if (v > 32767) v = 32767;
        else if (v < -32768) v = -32768;
        return (short) v;
    }
}
