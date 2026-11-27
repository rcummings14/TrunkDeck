package p25monitor.nativeengine;

import p25monitor.App;
import p25monitor.CallEvent;
import p25monitor.RadioSystem;
import p25monitor.ysf.YsfFrame;
import p25monitor.ysf.YsfSignal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Yaesu System Fusion through the whole engine: an I/Q recording of a repeater (as SDR# writes it, centred on the
 * channel) carrying a DN call and then a VW call is played on a Fusion system. Each becomes one call with the caller's
 * callsign and the DG-ID, and its voice frames reach the call.
 */
public final class FusionEngineTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("FusionEngineTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void run() throws Exception {
        long hz = 147_165_000L;
        YsfSignal.Tx dn = YsfSignal.tx(28, YsfFrame.DT_VD2, 21);
        dn.source = "KD8ABC"; dn.dest = "ALL"; dn.dgid = 0; dn.uplink = "WV8HC";
        YsfSignal.Tx vw = YsfSignal.tx(21, YsfFrame.DT_VOICE_FR, 22);
        vw.source = "N8FMD"; vw.dgid = 10;
        List<byte[]> frames = new ArrayList<>(YsfSignal.frames(dn));
        int[] d1 = YsfSignal.dibits(frames, 4800, 1), d2 = YsfSignal.dibits(YsfSignal.frames(vw), 4800, 2);
        int[] all = new int[d1.length + d2.length];
        System.arraycopy(d1, 0, all, 0, d1.length);
        System.arraycopy(d2, 0, all, d1.length, d2.length);
        float[][] bb = YsfSignal.modulate(all, 900, 150, 0.03, 9);
        // silence (no carrier) between and after: the "quiet" dibits are replaced by noise only
        for (int k = 0; k < bb[0].length; k++) {
            int sym = k / 10;
            boolean quiet = sym < 4800 || (sym >= d1.length - 4800 && sym < d1.length + 4800) || sym >= all.length - 4800;
            if (quiet) { bb[0][k] = (float) (0.03 * Math.sin(k * 1.7)); bb[1][k] = (float) (0.03 * Math.cos(k * 2.3)); }
        }
        // then an analog FM user on the mixed-mode repeater: 2.5 s of a 1 kHz tone at 2.5 kHz deviation, then 1 s quiet
        int fmN = 48_000 * 5 / 2, tail = 48_000;
        float[][] mixed = {Arrays.copyOf(bb[0], bb[0].length + fmN + tail), Arrays.copyOf(bb[1], bb[1].length + fmN + tail)};
        Random rnd = new Random(4);
        double ph = 0;
        for (int k = 0; k < fmN + tail; k++) {
            int at = bb[0].length + k;
            if (k < fmN) {
                ph += 2 * Math.PI * 2500 * Math.sin(2 * Math.PI * 1000 * k / 48_000.0) / 48_000.0;
                mixed[0][at] = (float) (0.9 * Math.cos(ph) + 0.02 * rnd.nextGaussian());
                mixed[1][at] = (float) (0.9 * Math.sin(ph) + 0.02 * rnd.nextGaussian());
            } else { mixed[0][at] = (float) (0.03 * Math.sin(at * 1.7)); mixed[1][at] = (float) (0.03 * Math.cos(at * 2.3)); }
        }
        bb = mixed;
        int up = 5, n = bb[0].length * up;
        Path dir = Files.createTempDirectory("td-ysf");
        Path file = dir.resolve("10-20-30_" + hz + "Hz.wav");
        ByteBuffer b = ByteBuffer.allocate(44 + n * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(b.capacity() - 8).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 2).putInt(48_000 * up).putInt(48_000 * up * 4).putShort((short) 4).putShort((short) 16);
        b.put("data".getBytes()).putInt(n * 4);
        for (int k = 0; k < bb[0].length; k++) {
            int prev = Math.max(0, k - 1);
            for (int u = 1; u <= up; u++) {
                float f = u / (float) up;
                float i = bb[0][prev] + (bb[0][k] - bb[0][prev]) * f, q = bb[1][prev] + (bb[1][k] - bb[1][prev]) * f;
                b.putShort((short) Math.round(i * 0.5f * 32767)).putShort((short) Math.round(q * 0.5f * 32767));
            }
        }
        Files.write(file, b.array());

        Path home = Files.createTempDirectory("td-ysf-home"), profile = Files.createTempDirectory("td-ysf-profile");
        System.setProperty("trunkdeck.profile", profile.toString());
        App app = new App(home);
        app.load();
        app.settings.setProperty("listenMode", "ALL");
        RadioSystem s = new RadioSystem();
        s.kind = RadioSystem.FUSION;
        s.name = "Fusion test";
        s.channels = RadioSystem.parseChannelText("147.1650 WV8HC");
        app.current = s;
        check(s.fusion() && s.fixedChannels() && s.kindLabel().contains("Fusion"), "a Fusion system has fixed channels: " + s.kindLabel());
        Path saved = Files.createTempDirectory("td-ysf-sys");
        s.save(saved);
        RadioSystem back = RadioSystem.load(saved);
        check(back.fusion() && back.channels.size() == 1 && "WV8HC".equals(back.channels.get(0).name), "saved and loaded again it is still Fusion: " + back.kindLabel() + ", " + back.channelText());
        List<CallEvent> calls = Collections.synchronizedList(new ArrayList<>());
        NativeEngine ne = new NativeEngine(app, new NativeEngine.Listener() {
            public void callStarted(CallEvent e) { calls.add(e); }
            public void callUpdated(CallEvent e) {}
            public void messages(int k, String last) {}
        });
        ne.audioSinkOverride = new ConventionalTests.Sink();
        ne.setFile(file, 0, 0, 0, true);
        ne.start(s, NativeEngine.Mode.FILE);
        Map<String, Long> voice = new HashMap<>();
        try {
            long end = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < end && !"FILE FINISHED".equals(ne.state)) {
                synchronized (ne) {
                    ActiveCall c = ne.callMap.get("Y" + hz);
                    if (c != null) voice.merge(c.ev.from, (long) c.voiceMs, Math::max);
                    ActiveCall a = ne.callMap.get("A" + hz);
                    if (a != null) voice.merge("analog", (long) a.voiceMs, Math::max);
                }
                Thread.sleep(20);
            }
            Thread.sleep(1500);
        } finally { ne.stop(); }
        CallEvent first = null, second = null, analog = null;
        int analogCalls = 0;
        synchronized (calls) {
            for (CallEvent e : calls) {
                if ("KD8ABC".equals(e.from)) first = e;
                if ("N8FMD".equals(e.from)) second = e;
                if ("Analog FM".equals(e.protocol)) { analog = e; analogCalls++; }
            }
        }
        check(calls.size() == 3, "two Fusion transmissions and one analog, three calls (" + calls.size() + ": " + calls.stream().map(e -> e.protocol + " " + e.from).toList() + ")");
        check(analogCalls == 1 && analog != null && "WV8HC".equals(analog.to), "mixed-mode repeater: the analog FM user is one analog call on the channel, the Fusion calls none (" + analogCalls + ")");
        long anMs = voice.getOrDefault("analog", 0L);
        check(anMs >= 2200, "the analog call's audio reached the call from its start: " + anMs + " ms of 2500");
        check(first != null && "Fusion".equals(first.protocol) && "ALL".equals(first.to) && first.talkgroup == 0 && Math.abs(first.frequencyMHz - 147.165) < 1e-6,
                "DN call: " + (first == null ? "none" : first.from + " -> " + first.to + " DG-ID " + first.talkgroup + ", " + first.details));
        check(first != null && first.details.contains("DN") && first.details.contains("WV8HC"), "DN call details name the mode and the repeater: " + (first == null ? "" : first.details));
        check(second != null && second.talkgroup == 10 && second.details.contains("VW"), "VW call: " + (second == null ? "none" : second.from + " DG-ID " + second.talkgroup + ", " + second.details));
        long dnMs = voice.getOrDefault("KD8ABC", 0L), vwMs = voice.getOrDefault("N8FMD", 0L);
        check(dnMs >= 2500, "DN voice reached the call: " + dnMs + " ms of 2800");
        check(vwMs >= 1700, "VW voice reached the call: " + vwMs + " ms of 1920");
        String mode = null;
        for (String line : ne.recentLog()) if (line.contains("Yaesu System Fusion")) mode = line;
        check(mode != null, "the log says the system is Fusion");
    }
}
