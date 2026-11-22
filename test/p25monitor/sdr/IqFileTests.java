package p25monitor.sdr;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/** I/Q recordings: a plain WAV and an RF64 one (what SDR#, SDR++ and SDRuno write for long recordings) read the same. */
public final class IqFileTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory("td-iqfile");
        int frames = 48_000;
        for (boolean rf64 : new boolean[]{false, true}) {
            Path p = dir.resolve((rf64 ? "rf64" : "riff") + "_146700000Hz.wav");
            Files.write(p, wav(rf64, 48_000, frames));
            IqFileSource f = new IqFileSource(p, 0, 0);
            String kind = rf64 ? "RF64" : "WAV";
            check(f.sampleRate() == 48_000 && f.centerHz() == 146_700_000L, kind + ": rate " + (int) f.sampleRate() + ", centre " + f.centerHz());
            check(Math.abs(f.seconds() - 1.0) < 1e-9, kind + ": length " + f.seconds() + " s");
            f.realtime = false;
            AtomicLong n = new AtomicLong();
            float[] first = new float[2];
            f.start(new IqSource.Listener() {
                public void samples(float[] i, float[] q, int k) { if (n.get() == 0) { first[0] = i[0]; first[1] = q[0]; } n.addAndGet(k); }
            });
            long end = System.currentTimeMillis() + 10_000;
            while (f.running() && System.currentTimeMillis() < end) Thread.sleep(20);
            check(n.get() == frames, kind + ": " + n.get() + " samples read");
            check(Math.abs(first[0] - 0.5f) < 1e-3 && Math.abs(first[1] + 0.25f) < 1e-3, kind + ": first sample " + first[0] + ", " + first[1]);
        }
        Path bad = dir.resolve("bad.wav");
        Files.write(bad, new byte[]{'J', 'U', 'N', 'K', 0, 0, 0, 0, 'W', 'A', 'V', 'E'});
        boolean refused = false;
        try { new IqFileSource(bad, 0, 0); } catch (IOException e) { refused = true; }
        check(refused, "a file that is not WAV is refused");
        System.out.println("IqFileTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** 16-bit stereo I/Q; the first sample is (0.5, -0.25). RF64 has a ds64 chunk and 0xFFFFFFFF sizes, as SDR# writes. */
    static byte[] wav(boolean rf64, int rate, int frames) {
        int data = frames * 4;
        ByteBuffer b = ByteBuffer.allocate(12 + (rf64 ? 36 : 0) + 24 + 8 + data).order(ByteOrder.LITTLE_ENDIAN);
        b.put((rf64 ? "RF64" : "RIFF").getBytes()).putInt(rf64 ? -1 : b.capacity() - 8).put("WAVE".getBytes());
        if (rf64) b.put("ds64".getBytes()).putInt(28).putLong(b.capacity() - 8).putLong(data).putLong(frames).putInt(0);
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 2).putInt(rate).putInt(rate * 4).putShort((short) 4).putShort((short) 16);
        b.put("data".getBytes()).putInt(rf64 ? -1 : data);
        for (int k = 0; k < frames; k++) b.putShort(k == 0 ? (short) 16384 : 0).putShort(k == 0 ? (short) -8192 : 0);
        return b.array();
    }
}
