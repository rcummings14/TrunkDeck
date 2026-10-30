package p25monitor.sdr;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.channels.SeekableByteChannel;

/**
 * Reads I/Q recordings for testing: WAV (2-channel I/Q, 8/16/24/32-bit PCM or 32-bit float; e.g. baseband
 * recordings, SDR#, sigidwiki IF samples) and raw files: .cu8/.bin (rtl_sdr output), .cs8, .cs16, .cf32/.raw.
 * Raw files need the sample rate (and optionally centre frequency) given by the caller or in the file name
 * (e.g. "..._2400000Hz_..." / "..._453537500Hz_..." / SDR# style "_453.537MHz_2400kHz_").
 */
public final class IqFileSource implements IqSource {
    public enum Format { WAV, CU8, CS8, CS16, CF32 }

    final Path path;
    Format format;
    double rate;
    long center;
    int channels = 2, bits = 16;
    boolean floatWav;
    long dataOffset, dataLength;
    public volatile boolean realtime = true;  // pace playback like a live radio
    public volatile boolean loop = false;
    volatile boolean run;
    Thread thread;
    volatile String error;
    volatile double progress;

    public IqFileSource(Path p, double rateHint, long centerHint) throws IOException {
        path = p;
        String n = p.getFileName().toString().toLowerCase();
        rate = rateHint; center = centerHint;
        if (n.endsWith(".wav")) { format = Format.WAV; parseWav(); }
        else {
            format = n.endsWith(".cs16") || n.endsWith(".s16") ? Format.CS16 : n.endsWith(".cf32") || n.endsWith(".raw") || n.endsWith(".fc32") ? Format.CF32
                    : n.endsWith(".cs8") || n.endsWith(".s8") ? Format.CS8 : Format.CU8;
            dataOffset = 0; dataLength = Files.size(p);
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{5,10})hz").matcher(n);
        while (m.find()) {
            long v = Long.parseLong(m.group(1));
            if (v > 30_000_000L) { if (center == 0) center = v; } else if (rate <= 0) rate = v;
        }
        m = java.util.regex.Pattern.compile("(\\d{2,4}\\.\\d+)mhz").matcher(n);
        if (m.find() && center == 0) center = Math.round(Double.parseDouble(m.group(1)) * 1e6);
        m = java.util.regex.Pattern.compile("_(\\d{2,5})khz").matcher(n);
        if (m.find() && rate <= 0) rate = Integer.parseInt(m.group(1)) * 1000.0;
        if (rate <= 0) throw new IOException("Unknown sample rate for " + p.getFileName() + " - enter it in the test panel.");
    }

    void parseWav() throws IOException {
        try (SeekableByteChannel ch = Files.newByteChannel(path)) {
            ByteBuffer h = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            ch.read(h); h.flip();
            if (h.getInt() != 0x46464952) throw new IOException("Not a RIFF/WAV file");
            h.getInt();
            if (h.getInt() != 0x45564157) throw new IOException("Not a WAV file");
            long pos = 12;
            while (true) {
                ByteBuffer c = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                ch.position(pos);
                if (ch.read(c) < 8) throw new IOException("WAV has no data chunk");
                c.flip();
                int id = c.getInt();
                long size = c.getInt() & 0xffffffffL;
                if (id == 0x20746d66) { // fmt
                    ByteBuffer f = ByteBuffer.allocate((int) Math.min(size, 64)).order(ByteOrder.LITTLE_ENDIAN);
                    ch.read(f); f.flip();
                    int fmt = f.getShort() & 0xffff;
                    channels = f.getShort();
                    int sr = f.getInt();
                    f.getInt(); f.getShort();
                    bits = f.getShort();
                    if (fmt == 0xFFFE && size >= 40) { f.position(24); fmt = f.getShort() & 0xffff; }
                    floatWav = fmt == 3;
                    if (rate <= 0) rate = sr; else rate = sr;
                } else if (id == 0x61746164) { // data
                    dataOffset = pos + 8;
                    dataLength = size == 0xffffffffL || size == 0 ? Files.size(path) - dataOffset : Math.min(size, Files.size(path) - dataOffset);
                    break;
                }
                pos += 8 + size + (size & 1);
            }
            if (channels != 2) throw new IOException("WAV must be 2-channel I/Q (this one has " + channels + " channel(s))");
        }
    }

    public Format format() { return format; }
    @Override public double sampleRate() { return rate; }
    @Override public long centerHz() { return center; }
    public double seconds() { return dataLength / (double) frameBytes() / rate; }
    public double progress() { return progress; }
    @Override public String name() { return path.getFileName().toString(); }
    @Override public boolean running() { return run; }
    @Override public String error() { return error; }

    int frameBytes() {
        return switch (format) {
            case WAV -> channels * (bits / 8);
            case CU8, CS8 -> 2;
            case CS16 -> 4;
            case CF32 -> 8;
        };
    }

    @Override public void start(Listener l) {
        run = true;
        thread = new Thread(() -> {
            try { do { readAll(l); } while (run && loop); }
            catch (Exception e) { error = e.toString(); }
            finally { run = false; }
        }, "iq-file");
        thread.setDaemon(true);
        thread.start();
    }

    /** Reads the whole file synchronously (used by tests and the file test panel). */
    public void readAll(Listener l) throws IOException {
        int fb = frameBytes();
        int block = 16384;
        float[] i = new float[block], q = new float[block];
        byte[] buf = new byte[block * fb];
        long t0 = System.nanoTime(), done = 0, total = dataLength / fb;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(path), 1 << 20)) {
            in.skipNBytes(dataOffset);
            long left = dataLength;
            while (left >= fb && (run || thread == null || Thread.currentThread() != thread)) {
                int want = (int) Math.min(buf.length, left - left % fb);
                int got = in.readNBytes(buf, 0, want);
                if (got < fb) break;
                left -= got;
                int n = got / fb;
                convert(buf, n, i, q);
                l.samples(i, q, n);
                done += n;
                progress = done / (double) total;
                if (realtime && thread != null && Thread.currentThread() == thread) {
                    long due = t0 + (long) (done / rate * 1e9), now = System.nanoTime();
                    if (due > now) try { Thread.sleep((due - now) / 1_000_000); } catch (InterruptedException e) { return; }
                }
            }
        }
    }

    void convert(byte[] b, int n, float[] i, float[] q) {
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        switch (format) {
            case CU8 -> { for (int k = 0; k < n; k++) { i[k] = ((b[2 * k] & 0xff) - 127.5f) / 128f; q[k] = ((b[2 * k + 1] & 0xff) - 127.5f) / 128f; } }
            case CS8 -> { for (int k = 0; k < n; k++) { i[k] = b[2 * k] / 128f; q[k] = b[2 * k + 1] / 128f; } }
            case CS16 -> { for (int k = 0; k < n; k++) { i[k] = bb.getShort() / 32768f; q[k] = bb.getShort() / 32768f; } }
            case CF32 -> { for (int k = 0; k < n; k++) { i[k] = bb.getFloat(); q[k] = bb.getFloat(); } }
            case WAV -> {
                for (int k = 0; k < n; k++) { i[k] = sample(bb); q[k] = sample(bb); for (int c = 2; c < channels; c++) sample(bb); }
            }
        }
    }

    float sample(ByteBuffer bb) {
        if (floatWav) return bits == 64 ? (float) bb.getDouble() : bb.getFloat();
        return switch (bits) {
            case 8 -> ((bb.get() & 0xff) - 128) / 128f;
            case 16 -> bb.getShort() / 32768f;
            case 24 -> { int v = (bb.get() & 0xff) | ((bb.get() & 0xff) << 8) | (bb.get() << 16); yield v / 8388608f; }
            default -> bb.getInt() / 2147483648f;
        };
    }

    @Override public void stop() {
        run = false;
        if (thread != null) thread.interrupt();
    }
}
