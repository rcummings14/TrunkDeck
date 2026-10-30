package p25monitor;

import p25monitor.nativeengine.AudioOut;

/**
 * "Now playing" timing: the display stays lit until the call's last audio frame has actually been heard (sound-card
 * buffer latency included), then shows hang time, then goes idle.
 */
public final class DisplayTests {
    private DisplayTests() {}

    /** A sound card with a 250 ms buffer, paced by the real clock. Remembers when the last non-silent sample is heard. */
    static final class LatencySink implements AudioOut.Sink {
        volatile long playedUntil, lastAudioHeardAt;
        public void open(int rate) { playedUntil = System.nanoTime(); }
        public void write(byte[] b, int off, int len) {
            long now = System.nanoTime();
            long start = Math.max(now, playedUntil);
            long end = start + (len / 2) * 125_000L;
            boolean audio = false; for (int i = off; i < off + len; i++) if (b[i] != 0) { audio = true; break; }
            playedUntil = end;
            if (audio) lastAudioHeardAt = end;
            long wait = end - 250_000_000L - now;   // block while more than 250 ms is buffered
            if (wait > 0) try { Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        public void close() {}
        public long latencyNanos() { return Math.max(0, playedUntil - System.nanoTime()); }
    }

    /** Returns {pass, fail}. */
    public static int[] lit() throws Exception {
        int[] r = new int[2];
        LatencySink sink = new LatencySink();
        AudioOut ao = new AudioOut(sink);
        ao.open();
        long id = 42;
        float[] tone = new float[160];
        for (int k = 0; k < 50; k++) {                       // 1 s of call audio, delivered in 20 ms frames
            for (int i = 0; i < 160; i++) tone[i] = (float) (0.3 * Math.sin(2 * Math.PI * 440 * (k * 160 + i) / 8000.0));
            ao.play(id, tone, 160);
            Thread.sleep(20);
        }
        ao.endOfTransmission();
        long goneAt = 0, lastTrueWithEmptyQueue = 0, deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            boolean a = ao.audible(id);
            long now = System.nanoTime();
            if (a && ao.queuedMs() == 0) lastTrueWithEmptyQueue = now;
            if (!a) { goneAt = now; break; }
            Thread.sleep(2);
        }
        long heard = sink.lastAudioHeardAt;
        double lateMs = (goneAt - heard) / 1e6;
        System.out.printf("  last audio heard at T, 'audible' ended at T%+.1f ms; lit while queue empty but sound card still playing: %s%n", lateMs, lastTrueWithEmptyQueue > 0);
        check(r, goneAt > 0, "audible() never ended");
        check(r, lateMs >= -5, "display dimmed " + (-lateMs) + " ms before the last audio was heard");
        check(r, lateMs <= 80, "display stayed lit " + lateMs + " ms after the last audio was heard");
        check(r, lastTrueWithEmptyQueue > 0, "lit state did not cover the sound-card buffer latency");

        // the big display follows the audio-side state: 2 = playing, 1 = hang time, 0 = idle
        {
            ScannerDisplay d = new ScannerDisplay();
            CallEvent e = new CallEvent(); e.talkgroup = 2001; e.to = "2001";
            long now = System.currentTimeMillis();
            d.onCall(e, null);
            d.applyLit(0, now + 100);  check(r, d.active, "dimmed before the first audio reached the speaker (just shown)");
            d.applyLit(2, now + 900);  check(r, d.active && !d.hang, "not lit while its audio is playing");
            d.applyLit(2, now + 5000); check(r, d.active, "4 s demo rule dimmed a call that is still playing");
            d.applyLit(1, now + 5100); check(r, !d.active && d.hang, "not in hang time after the audio finished");
            d.applyLit(0, now + 7600); check(r, !d.active && !d.hang, "not idle after hang time");
            ScannerDisplay demo = new ScannerDisplay();
            demo.onCall(e, null);
            demo.applyLit(-1, now + 3000); check(r, demo.active, "demo mode dimmed early");
            demo.applyLit(-1, now + 4500); check(r, !demo.active, "demo mode did not dim after 4 s");
        }
        return r;
    }

    static void check(int[] r, boolean ok, String what) { if (ok) r[0]++; else { r[1]++; System.out.println("  FAIL: " + what); } }
}
