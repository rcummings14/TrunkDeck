package p25monitor.nativeengine;

import p25monitor.App;
import p25monitor.CallEvent;
import p25monitor.RadioSystem;
import java.nio.file.Files;
import java.util.*;

/** Analog FM in the engine: channel tones in the channel text, tone lookup, call start / audio / end, save and load. */
public final class AnalogEngineTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("AnalogEngineTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void run() throws Exception {
        List<RadioSystem.Channel> ch = RadioSystem.parseChannelText("155.2500 Dispatch, 155.3700:100.0 Fire, 155.4000:2D5");
        check(ch.size() == 3 && ch.get(1).nac.equals("100.0") && ch.get(1).name.equals("Fire") && ch.get(2).nac.equals("2D5"), "tone and NAC after the colon both parse");
        check(RadioSystem.parseChannelText("155.2500 155.3700:71.9").size() == 2, "space-separated frequencies with a tone");
        boolean threw = false;
        try { RadioSystem.parseChannelText("155.2500:99.9"); } catch (NumberFormatException e) { threw = e.getMessage().contains("CTCSS"); }
        check(threw, "a non-standard tone is refused with a readable message");

        var home = Files.createTempDirectory("td-analog");
        System.setProperty("trunkdeck.profile", home.resolve("p").toString());
        List<String> started = new ArrayList<>();
        NativeEngine ne = new NativeEngine(new App(home), new NativeEngine.Listener() {
            public void callStarted(CallEvent ev) { started.add(ev.to + "|" + ev.details); }
            public void callUpdated(CallEvent ev) {}
            public void messages(int count, String last) {}
        });
        RadioSystem s = new RadioSystem();
        s.kind = RadioSystem.ANALOG;
        s.channels = ch;
        s.nac = "123.0";
        ne.sys = s;
        check(s.analog() && s.fixedChannels() && s.kindLabel().startsWith("Analog"), "analog is a fixed-channel system: " + s.kindLabel());
        check(s.toneFor(155_370_000L) == 100.0 && s.toneFor(155_250_000L) == 123.0, "a channel's own tone wins over the system tone");

        VoiceCallDecoder v = ne.voice;
        long f = 155_370_000L;
        v.onAnalogStart(f, 100.0);
        ActiveCall c = ne.callMap.get("A" + f);
        check(c != null && "Analog FM".equals(c.ev.protocol) && c.ev.to.equals("Fire") && c.ev.details.contains("PL 100.0"), "call named after the channel, tone shown: " + started);
        v.onAnalogAudio(f, new float[160]);
        v.onAnalogAudio(f, new float[160]);
        check(c.voiceFrames == 2 && c.pcmLen == 320, "audio is recorded on the call (" + c.pcmLen + " samples)");
        v.onAnalogEnd(f);
        check(c.ended, "squelch close ends the call");
        v.onAnalogAudio(f, new float[160]);
        check(ne.callMap.get("A" + f) != null && ne.callMap.get("A" + f) != c, "audio after an end starts a new call");
        v.onAnalogEnd(f);

        // Hold / Scan (Monitor page, analog systems only)
        check(!ne.analogRunning() && ne.holdAnalog() != null, "Hold is offered only while an analog system is running");
        ne.running = true;
        check(ne.analogRunning() && ne.holdAnalog() == null && ne.holdFreq() == f, "Hold stays on the last channel heard (" + ne.holdFreq() + ")");
        long other = 155_250_000L;
        v.onAnalogAudio(other, new float[160]);
        ActiveCall o = ne.callMap.get("A" + other);
        v.onAnalogAudio(f, new float[160]);
        ActiveCall held = ne.callMap.get("A" + f);
        check(!ne.speaker.play(o, new float[160], 160) && ne.speaker.play(held, new float[160], 160) && o.pcmLen == 160,
                "while held, only that channel plays; the other channel is still recorded");
        check(ne.lastAnalogHeard == f, "last heard follows the audio");
        ne.scanAnalog();
        check(ne.holdFreq() == 0, "Scan releases the hold");
        List<Long> order = new ArrayList<>();
        for (RadioSystem.Channel x : s.channels) order.add(Math.round(x.mhz * 1e6));
        int at = order.indexOf(f);
        ne.stepAnalog(1);
        long up1 = ne.holdFreq();
        ne.stepAnalog(1); ne.stepAnalog(1); ne.stepAnalog(1);
        long round = ne.holdFreq();
        ne.stepAnalog(-1);
        check(up1 == order.get((at + 1) % order.size()) && round == up1 && ne.holdFreq() == f,
                "Up holds the next channel and wraps round; Down goes back (" + order + ")");
        check(ne.channelName(f).equals("Fire"), "the held channel's name is shown");
        ne.scanAnalog();
        s.kind = RadioSystem.TRUNKED;
        check(!ne.analogRunning() && ne.holdAnalog() != null, "not offered on a P25 system");
        s.kind = RadioSystem.ANALOG;
        ne.running = false;
        v.onAnalogEnd(other); v.onAnalogEnd(f);

        // Scan delay (Systems page): stay on the channel a few seconds after its transmission ends
        long now = System.currentTimeMillis();
        ne.scanGroups = List.of(List.of(f), List.of(other)); ne.scanIndex = 0;
        ne.lastAnalogHeard = f; ne.lastAnalogAt = now;
        check(s.scanDelay == RadioSystem.SCAN_DELAY_DEFAULT && ne.analogDelay(now + 1500), "default 2 s delay: still waiting 1.5 s after the transmission");
        check(!ne.analogDelay(now + 2100), "scanning resumes after the delay");
        s.scanDelay = 0;
        check(!ne.analogDelay(now + 10), "delay 0 moves on at once");
        s.scanDelay = 10;
        check(ne.analogDelay(now + 9000) && !ne.analogDelay(now + 10_500), "a longer delay is read live (no restart)");
        ne.scanIndex = 1;
        check(!ne.analogDelay(now + 100), "no delay once the scan is on other channels");
        s.kind = RadioSystem.TRUNKED; ne.scanIndex = 0;
        check(!ne.analogDelay(now + 100), "the delay is for analog systems only");
        s.kind = RadioSystem.ANALOG;
        s.scanDelay = 7;

        var dir = home.resolve("sys");
        s.save(dir);
        RadioSystem back = RadioSystem.load(dir);
        check(back.analog() && back.toneFor(f) == 100.0 && back.toneFor(155_250_000L) == 123.0, "kind and tones survive save and load");
        check(back.scanDelay == 7, "the scan delay is saved with the system (" + back.scanDelay + ")");
    }
}
