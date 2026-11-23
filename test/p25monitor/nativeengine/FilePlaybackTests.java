package p25monitor.nativeengine;

import p25monitor.App;
import p25monitor.CallEvent;
import p25monitor.RadioSystem;
import p25monitor.dmr.DmrDecoderTests;
import p25monitor.dmr.XptSignal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Playing an I/Q recording that is centred on the channel (as SDR# and SDR++ record when tuned to it), written as RF64.
 * A live receiver keeps channels away from its centre (DC spike), and that rule used to skip the only channel in such a
 * file: nothing was decoded. Also: the repeater's hang time (the terminator repeated) is one call, not dozens.
 */
public final class FilePlaybackTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("FilePlaybackTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void run() throws Exception {
        long hz = 146_700_000L;
        // a two-slot call, 48 kHz baseband, put on 240 kS/s at the file's centre
        float[][] bb = DmrDecoderTests.modulate(DmrDecoderTests.repeaterDibits(3123, 3123446, null, 202, 5678), true, 0, 0.02);
        int up = 5, n = bb[0].length * up;
        Path dir = Files.createTempDirectory("td-play");
        Path file = dir.resolve("10-03-05_" + hz + "Hz.wav");
        ByteBuffer b = ByteBuffer.allocate(80 + n * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RF64".getBytes()).putInt(-1).put("WAVE".getBytes());
        b.put("ds64".getBytes()).putInt(28).putLong(b.capacity() - 8).putLong(n * 4L).putLong(n).putInt(0);
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 2).putInt(48_000 * up).putInt(48_000 * up * 4).putShort((short) 4).putShort((short) 16);
        b.put("data".getBytes()).putInt(-1);
        for (int k = 0; k < bb[0].length; k++) {
            int prev = Math.max(0, k - 1);
            for (int u = 1; u <= up; u++) {
                float f = u / (float) up;
                float i = bb[0][prev] + (bb[0][k] - bb[0][prev]) * f, q = bb[1][prev] + (bb[1][k] - bb[1][prev]) * f;
                b.putShort((short) Math.round(i * 0.5f * 32767)).putShort((short) Math.round(q * 0.5f * 32767));
            }
        }
        Files.write(file, b.array());

        Path home = Files.createTempDirectory("td-play-home"), profile = Files.createTempDirectory("td-play-profile");
        System.setProperty("trunkdeck.profile", profile.toString());
        App app = new App(home);
        app.load();
        app.settings.setProperty("listenMode", "ALL");
        RadioSystem s = new RadioSystem();
        s.kind = RadioSystem.DMR;
        s.name = "file test";
        s.colorCode = XptSignal.CC;
        s.channels = RadioSystem.parseChannelsProperty("146.7000||146.700 DMR");
        app.current = s;
        List<CallEvent> calls = Collections.synchronizedList(new ArrayList<>());
        NativeEngine ne = new NativeEngine(app, new NativeEngine.Listener() {
            public void callStarted(CallEvent e) { calls.add(e); }
            public void callUpdated(CallEvent e) {}
            public void messages(int k, String last) {}
        });
        ne.audioSinkOverride = new ConventionalTests.Sink();
        ne.setFile(file, 0, 0, 0, true);
        ne.start(s, NativeEngine.Mode.FILE);
        try {
            long end = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < end && !"FILE FINISHED".equals(ne.state)) Thread.sleep(100);
            Thread.sleep(1500);
        } finally { ne.stop(); }
        int tg3123 = 0, tg202 = 0;
        synchronized (calls) { for (CallEvent e : calls) { if (e.talkgroup == 3123) tg3123++; if (e.talkgroup == 202) tg202++; } }
        check(tg3123 >= 1 && tg202 >= 1, "the channel at the centre of the recording is decoded (TG 3123: " + tg3123 + " call(s), TG 202: " + tg202 + ")");
        check(tg3123 == 1 && tg202 == 1, "each transmission is one call (" + calls.size() + " in all)");
    }
}
