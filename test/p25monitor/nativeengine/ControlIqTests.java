package p25monitor.nativeengine;

import p25monitor.App;
import p25monitor.CallEvent;
import p25monitor.edacs.EdacsDemod;
import p25monitor.edacs.EdacsTests;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Engine page "Record control channel": the control channel being received is saved as a 48 kS/s I/Q .wav that
 * decodes again. Checked with an EDACS control channel 900 Hz off frequency.
 */
public final class ControlIqTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("ControlIqTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void run() throws Exception {
        Path home = Files.createTempDirectory("td-cciq"), rec = Files.createTempDirectory("td-cciq-rec");
        System.setProperty("trunkdeck.profile", home.resolve("p").toString());
        System.setProperty("trunkdeck.recordings", rec.toString());
        NativeEngine ne = new NativeEngine(new App(home), new NativeEngine.Listener() {
            public void callStarted(CallEvent e) {}
            public void callUpdated(CallEvent e) {}
            public void messages(int k, String last) {}
        });
        check(ne.recordControlIq(2) == null && ne.recentLog().get(ne.recentLog().size() - 1).contains("no control channel"),
                "nothing running: nothing recorded, and the log says why");

        long hz = 851_675_000L;
        Tuner t = new Tuner(1, "control", new ControlSignalTests.Fake(), Tuner.Role.CONTROL);
        ChannelDecoder other = new ChannelDecoder(48_000, hz, 851_150_000L, new p25monitor.p25.P25Framer.Listener() {}, 0);
        ChannelDecoder cc = new ChannelDecoder(48_000, hz, hz, new p25monitor.p25.P25Framer.Listener() {}, 0);
        cc.lastFrameAt = System.currentTimeMillis();   // the locked one
        ne.ccTuner = t;
        ne.ccDecoders.add(other);
        ne.ccDecoders.add(cc);
        ne.running = true;

        // 3 s of an EDACS control channel: junk, then frames
        Random r = new Random(5);
        List<Boolean> bits = new ArrayList<>();
        for (int k = 0; k < 200; k++) bits.add(r.nextBoolean());
        List<int[]> sent = new ArrayList<>();
        while (bits.size() < 9600 * 3) {
            int[] m = {r.nextInt(1 << 28), r.nextInt(1 << 28)};
            sent.add(m);
            for (boolean b : EdacsDemod.encode(m[0], m[1])) bits.add(b);
        }
        float[][] s = EdacsTests.fsk(bits, 2800, 900, 0.1, false, 9);

        Path f = ne.recordControlIq(2);
        check(f != null && f.getFileName().toString().contains("_CC_851675000Hz_") && f.startsWith(rec.resolve("iq")),
                "the locked control channel is recorded into the I/Q folder: " + (f == null ? "none" : rec.relativize(f)));
        check(cc.tapping() && !other.tapping(), "only the locked control channel is recorded");
        check(ne.recordControlIq(2) == null && ne.recentLog().get(ne.recentLog().size() - 1).contains("already"), "a second press while recording does nothing");
        for (int k = 0; k < s[0].length; k += 960) {
            int m = Math.min(960, s[0].length - k);
            cc.process(Arrays.copyOfRange(s[0], k, k + m), Arrays.copyOfRange(s[1], k, k + m), m);
        }
        long until = System.currentTimeMillis() + 8000;
        while (cc.tapping() && System.currentTimeMillis() < until) Thread.sleep(50);
        check(!cc.tapping(), "the recording stops by itself");
        String saved = null;
        for (String l : ne.recentLog()) if (l.contains("Saved control-channel I/Q")) saved = l;
        check(saved != null, "the log says where it was saved");

        byte[] w = f == null ? new byte[0] : Files.readAllBytes(f);
        ByteBuffer b = ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN);
        boolean header = w.length > 44 && new String(w, 0, 4).equals("RIFF") && b.getShort(20) == 3 && b.getShort(22) == 2 && b.getInt(24) == 48_000;
        int n = header ? b.getInt(40) / 8 : 0;
        check(header && Math.abs(n - 96_000) <= 960, "a 2-channel float WAV at 48 kS/s, 2 s long (" + n + " samples)");

        // the file decodes: the frames sent during those 2 s come back
        float[] i = new float[n], q = new float[n];
        for (int k = 0; k < n; k++) { i[k] = b.getFloat(44 + 8 * k); q[k] = b.getFloat(48 + 8 * k); }
        List<int[]> got = new ArrayList<>();
        EdacsDemod d = new EdacsDemod((m1, m2) -> got.add(new int[]{m1, m2}));
        for (int k = 0; k < n; k += 960) { int m = Math.min(960, n - k); d.baseband(Arrays.copyOfRange(i, k, k + m), Arrays.copyOfRange(q, k, k + m), m); }
        int match = 0;
        Set<String> want = new HashSet<>();
        for (int[] m : sent) want.add(m[0] + "/" + m[1]);
        for (int[] m : got) if (want.contains(m[0] + "/" + m[1])) match++;
        int expected = (int) (2.0 * 9600 / EdacsDemod.encode(0, 0).length);
        check(match >= expected - 4, "played back, the recording decodes: " + match + " of about " + expected + " frames");
        ne.running = false;
    }
}
