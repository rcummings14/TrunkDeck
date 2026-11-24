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
        for (int k = 0; k < 198; k++) v.onAnalogAudio(f, new float[160]);   // 4 s of audio in 20 ms blocks
        c.levelSum = -60 - 50; c.levelN = 2;   // two level samples taken while it was on the air
        v.onAnalogEnd(f);
        check(c.ended, "squelch close ends the call");
        check(c.voiceMs == 4000 && c.ev.durationMs < 5000, "the logged length is the audio heard (4 s), not 20 ms blocks counted as 180 ms (" + c.ev.durationMs + " ms)");
        check(c.ev.levelDb == -55, "the call log's Level is the average while it was on the air (" + c.ev.levelDb + " dB)");
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

        // Scan speed (Systems page, analog): time on each group of channels
        check(ne.scanDwellMs() == 1500, "analog scans 1.5 s per channel group by default (" + ne.scanDwellMs() + " ms)");
        s.scanDwell = 3.0;
        check(ne.scanDwellMs() == 3000, "the setting is read live (3 s)");
        s.scanDwell = 0.2;
        check(ne.scanDwellMs() == 1000, "never faster than 1 s");
        s.scanDwell = 99;
        check(ne.scanDwellMs() == 10_000, "never slower than 10 s");
        s.kind = RadioSystem.CONVENTIONAL;
        check(ne.scanDwellMs() == 5000, "digital conventional systems keep 5 s (they need longer to sync)");
        s.kind = RadioSystem.ANALOG;
        s.scanDwell = 2.5;
        p25monitor.analog.FmReceiver fm = new p25monitor.analog.FmReceiver(0, new p25monitor.analog.FmReceiver.Listener() {
            public void start(double toneHz) {} public void tone(double toneHz) {} public void audio(float[] pcm) {} public void end() {}
        });
        check(fm.carrierSince == 0, "no carrier: the scan is not held for a tone check");

        // Lockout (Monitor page): skipped from then on, saved with the system
        ne.running = true;
        long third = 155_400_000L;
        ne.lastAnalogHeard = other;
        check(ne.lockoutTarget() == other, "Lockout takes the channel last heard when nothing is playing");
        ne.lockout(other);
        int before = started.size();
        v.onAnalogStart(other, 0); v.onAnalogAudio(other, new float[160]);
        ActiveCall lc = ne.callMap.get("A" + other);
        check((lc == null || lc.ended) && started.size() == before && ne.lastAnalogHeard != other, "a locked-out channel is not played, recorded or logged");
        check(ne.lockedOutChannels().equals(List.of(other)), "Locked out lists it (" + ne.lockedOutChannels() + ")");
        ne.scanGroups = List.of(List.of(f), List.of(other), List.of(third)); ne.scanIndex = 0;
        check(ne.nextScanGroup() == 2, "scanning skips a group whose channels are all locked out");
        ne.scanGroups = List.of(List.of(f), List.of(other)); ne.scanIndex = 0;
        check(ne.nextScanGroup() == 0, "scanning stays put when every other group is locked out");
        ne.stepAnalog(1); ne.stepAnalog(1);   // hold Fire, then step: 155.4000 is next
        long upFrom = ne.holdFreq();
        ne.stepAnalog(1);
        check(ne.holdFreq() != other && ne.holdFreq() != 0, "Up / Down skip a locked-out channel (" + upFrom + " -> " + ne.holdFreq() + ")");
        long heldNow = ne.holdFreq();
        ne.lockout(heldNow);
        check(ne.holdFreq() == 0, "locking out the held channel goes back to scanning");
        ne.unlock(heldNow);
        ne.running = false;
        ne.scanGroups = List.of(); ne.scanIndex = 0;

        var dir = home.resolve("sys");
        s.save(dir);
        RadioSystem back = RadioSystem.load(dir);
        check(back.analog() && back.toneFor(f) == 100.0 && back.toneFor(155_250_000L) == 123.0, "kind and tones survive save and load");
        check(back.scanDelay == 7, "the scan delay is saved with the system (" + back.scanDelay + ")");
        check(back.scanDwell == 2.5, "the scan speed is saved with the system (" + back.scanDwell + " s)");
        check(back.lockout.equals(java.util.Set.of(other)), "the lockout is saved with the system (" + back.lockout + ")");
        ne.running = true; ne.unlock(other);
        check(!ne.lockedOut(other) && ne.lockedOutChannels().isEmpty(), "Unlock brings the channel back");
        s.save(dir);
        check(RadioSystem.load(dir).lockout.isEmpty(), "an unlocked channel stays unlocked after a restart");

        // P25 conventional: the same Hold / Scan / Up / Down / Lockout
        s.kind = RadioSystem.CONVENTIONAL;
        s.lockout.clear();
        ne.scanAnalog();
        ne.conventional = true; ne.running = true; ne.lastAnalogHeard = 0;
        check(ne.holdRunning() && !ne.analogRunning(), "Hold / Lockout are offered on a P25 conventional system");
        long p1 = 155_250_000L, p2 = 155_400_000L;
        v.onConventional(p1, 0x2D5, new byte[0][], null, null);
        check(ne.lockoutTarget() == p1, "the P25 channel being heard is what Hold / Lockout take (" + ne.lockoutTarget() + ")");
        check(ne.holdAnalog() == null && ne.holdFreq() == p1, "Hold stays on the P25 channel heard");
        ne.stepAnalog(1);
        check(ne.holdFreq() == 155_370_000L, "Up steps to the next channel in the list (" + ne.holdFreq() + ")");
        ne.scanAnalog();
        check(ne.holdFreq() == 0, "Scan goes back to every channel");
        v.onConventionalEnd(p1);
        ne.lockout(p2);
        v.onConventional(p2, 0x2D5, new byte[0][], null, null);
        ActiveCall locked = ne.callMap.get("C" + p2);
        check(locked == null, "a locked-out P25 channel is not followed");
        check(ne.lockedOutChannels().equals(List.of(p2)), "it is listed under Locked out (" + ne.lockedOutChannels() + ")");
        ne.unlock(p2);
        v.onConventional(p2, 0x2D5, new byte[0][], null, null);
        check(ne.callMap.get("C" + p2) != null, "after Unlock it is followed again");
        ne.running = false;
    }
}
