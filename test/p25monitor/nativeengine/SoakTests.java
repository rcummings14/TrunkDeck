package p25monitor.nativeengine;

import p25monitor.DiagLog;
import p25monitor.dsp.C4fmDemod;
import p25monitor.sdr.StreamWatch;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Long-run failures, run faster than real time. The sample clock is moved forward by hours, and a USB
 * timeline of several hours is decided without waiting. No dongle and no credentials.
 */
public final class SoakTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else { fail++; System.out.println("  FAIL  " + what); } }

    public static void main(String[] a) throws Exception {
        clock();
        decoderPastIntBoundary();
        carrierRestarts();
        usbTimeline();
        diagLog();
        System.out.println("SoakTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** Eight hours of 48 kHz samples, then the 2^31-sample boundary, without generating the samples. */
    static void clock() {
        System.out.println("1. demodulator sample clock across hours and 2^31 samples");
        C4fmDemod d = new C4fmDemod(new C4fmDemod.Sink() {
            public void sync(float q) {}
            public void symbol(int dibit, float level) {}
        });
        float[] i = new float[200], q = new float[200];
        for (int k = 0; k < i.length; k++) { i[k] = 0.2f; q[k] = 0.1f; }
        d.process(i, q, i.length);
        long hourRings = 8L * 3600 * C4fmDemod.FS / C4fmDemod.RING;
        d.skipRings(hourRings);
        check(d.samplesSeen() > 8L * 3600 * C4fmDemod.FS - C4fmDemod.RING, "8 hours of 48 kHz samples skipped, clock at " + d.samplesSeen());
        d.skipRings((1L << 31) / C4fmDemod.RING);
        long before = d.samplesSeen();
        d.process(i, q, i.length);
        check(before > Integer.MAX_VALUE && d.samplesSeen() == before + i.length && d.samplesSinceSync() > 0,
                "still demodulating after 2^31 samples (" + d.samplesSeen() + ")");
    }

    static void decoderPastIntBoundary() {
        System.out.println("2. channel decoder across the 32-bit sample boundary");
        ChannelDecoder d = new ChannelDecoder(48_000, 0, 159_090_000L, new p25monitor.p25.P25Framer.Listener() {}, 0);
        d.samples = (1L << 31) - 10;
        d.windowAt = d.samples;
        float[] i = new float[480], q = new float[480];
        d.process(i, q, i.length);
        check(d.samples > Integer.MAX_VALUE && !Double.isNaN(d.signalDb), "decoder sample count passed 2^31 (" + d.samples + ") and still measures a level");
    }

    static void carrierRestarts() {
        System.out.println("3. a carrier with no frames resets; a quiet channel does not");
        ChannelDecoder loud = new ChannelDecoder(48_000, 0, 159_090_000L, new p25monitor.p25.P25Framer.Listener() {}, 0);
        loud.samples = 48_000L * 60;
        loud.signalDb = -8;
        loud.syncPower = -6;
        loud.lastFrameAt = 1_000;
        long now = 1_000 + 120_000;
        check(loud.carrierWithoutFrame(now) && loud.resyncCarrier(now) && loud.syncResets == 1, "strong carrier, no frame for 2 min: demodulator reset");
        check(!loud.resyncCarrier(now), "not again in the same minute");
        ChannelDecoder quiet = new ChannelDecoder(48_000, 0, 159_090_000L, new p25monitor.p25.P25Framer.Listener() {}, 0);
        quiet.samples = 48_000L * 60;
        quiet.signalDb = -40;
        quiet.lastFrameAt = 0;
        check(!quiet.carrierWithoutFrame(now), "a quiet channel is not treated as a stuck signal");
    }

    /** Five hours of samples, then the USB read stops. The decision is the same one the RTL-SDR watchdog uses. */
    static void usbTimeline() {
        System.out.println("4. USB timeline: hours of samples, then silence");
        StreamWatch w = new StreamWatch();
        w.wanted = true;
        long t = 1_000_000_000L;
        w.noteSample(t);
        long fiveHours = 5L * 3600 * 1_000_000_000L;
        w.noteSample(t + fiveHours);
        check(!w.sampleQuiet(t + fiveHours + 1_000_000_000L, true), "samples a second ago, after 5 hours: still healthy");
        check(w.sampleQuiet(t + fiveHours + 9_000_000_000L, true), "no samples for 9 s: restart");
        check(w.sampleQuiet(t + fiveHours + 1_000_000_000L, false), "USB thread ended: restart at once");
        for (int i = 0; i < 5; i++) check(w.allowRestart(t + fiveHours), "restart " + (i + 1) + " allowed");
        check(!w.allowRestart(t + fiveHours) && w.givenUp(t + fiveHours + 1_000_000_000L), "sixth restart is refused and reported");
        check(w.allowRestart(t + fiveHours + 61_000_000_000L), "a minute later it tries again");
    }

    static void diagLog() throws Exception {
        System.out.println("5. rotating diagnostic log, credentials removed");
        Path home = Files.createTempDirectory("soak-log");
        System.setProperty("trunkdeck.diagMax", "400");
        DiagLog.attach(home);
        String homePath = System.getProperty("user.home") + "/TrunkDeck/recordings/iq/call.wav";
        String secret = "password=s3cret apiKey=abc123 app-key: " + "k".repeat(20)
                + " http://user:pw@radio.example/stream SN ABC999 connected to feeds.example.com:8000 from 203.0.113.8:443 "
                + homePath + " C:\\Users\\tim\\TrunkDeck\\iq\\a.wav";
        String clean = DiagLog.scrub(secret);
        check(!clean.contains("s3cret") && !clean.contains("abc123") && !clean.contains("kkkk")
                && !clean.contains("pw@") && !clean.contains("ABC999") && !clean.contains("feeds.example.com")
                && !clean.contains(":8000") && !clean.contains(System.getProperty("user.home"))
                && !clean.contains("203.0.113.8") && !clean.contains("C:\\Users\\tim"),
                "scrubbed: " + clean);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 80; i++) big.append("x");
        for (int i = 0; i < 12; i++) DiagLog.event("sdr", "RTL-SDR stream event " + i + " " + big);
        check(Files.exists(home.resolve("logs").resolve("trunkdeck.log")), "wrote " + home.resolve("logs").resolve("trunkdeck.log"));
        String all = Files.readString(home.resolve("logs").resolve("trunkdeck.log"));
        Path rotated = home.resolve("logs").resolve("trunkdeck.1.log");
        check(Files.exists(rotated) && !all.contains("s3cret"), "rotated once the file passed the limit, and the live file has no secret");
        String old = Files.readString(rotated);
        check(!old.contains("s3cret") && old.contains("RTL-SDR stream event"), "rotated file is the earlier stream events");
    }
}
