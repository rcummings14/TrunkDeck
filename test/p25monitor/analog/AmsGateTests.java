package p25monitor.analog;

/**
 * The mixed-mode (AMS) gate on a Fusion channel: analog FM is played, Fusion digital (which opens the FM squelch too)
 * and the repeater's carrier after it are not. Each step is one 20 ms block of the FM receiver; the "decoder" clock
 * counts how long ago the digital decoder last heard a good frame, as the Fusion decoder does.
 */
public final class AmsGateTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    static final class Out implements FmReceiver.Listener {
        int starts, ends, blocks;
        public void start(double t) { starts++; }
        public void audio(float[] pcm) { blocks++; }
        public void end() { ends++; }
    }

    /** The digital decoder's clock: ms since its last good frame. */
    static final class Dec {
        long since = 60_000;
        boolean frames;
        void tick() { since = frames ? 0 : since + 20; }
    }

    static final float[] PCM = new float[160];
    static void run(Dec d, AmsGate g, int blocks) { for (int k = 0; k < blocks; k++) { d.tick(); g.audio(PCM); } }

    public static void main(String[] a) {
        Dec d = new Dec();
        Out o = new Out(); AmsGate g = new AmsGate(() -> d.since, o);
        g.start(0); run(d, g, 19);
        check(o.starts == 0, "analog: held for the first 0.38 s while digital sync is looked for");
        run(d, g, 1);
        check(o.starts == 1 && o.blocks == 20, "analog: after 0.4 s it is a call, with all held audio (" + o.blocks + " blocks)");
        run(d, g, 50); g.end();
        check(o.ends == 1 && o.blocks == 70, "analog: every block played, one end (" + o.blocks + ")");

        o = new Out(); g = new AmsGate(() -> d.since, o);
        g.start(0); run(d, g, 5);
        d.frames = true; run(d, g, 100);
        d.frames = false; run(d, g, 100);   // 2 s of carrier after the last frame
        g.end();
        check(o.starts == 0 && o.blocks == 0, "Fusion transmission, then 2 s of carrier: nothing played (" + o.starts + " starts)");

        // the real repeater: the squelch closes and reopens at once on the carrier after a Fusion user,
        // and the next Fusion user's data comes 1.4 s later
        o = new Out(); g = new AmsGate(() -> d.since, o);
        g.start(0); d.frames = true; run(d, g, 100);
        d.frames = false; run(d, g, 5); g.end();
        g.start(0); run(d, g, 65);
        check(o.starts == 0, "squelch reopened on the carrier 0.1 s after a Fusion user: no analog call before the next user's data");
        d.frames = true; run(d, g, 100); d.frames = false; run(d, g, 40); g.end();
        check(o.starts == 0 && o.blocks == 0, "and the next Fusion user is not played as analog either (" + o.starts + ")");

        o = new Out(); g = new AmsGate(() -> d.since, o);
        g.start(0); d.frames = true; run(d, g, 50);
        d.frames = false; run(d, g, 149);
        check(o.starts == 0, "analog right after digital, carrier kept: waits until the decoder has heard nothing for 3 s");
        run(d, g, 1);
        check(o.starts == 1 && o.blocks >= 137, "then it is played from within 0.25 s of where digital stopped (" + o.blocks + " of 150 blocks)");
        g.end();

        o = new Out(); g = new AmsGate(() -> d.since, o);
        d.since = 60_000; g.start(0); run(d, g, 40);
        d.frames = true; run(d, g, 1);
        check(o.starts == 1 && o.ends == 1, "a running analog call ends as soon as digital is heard");
        d.frames = false; g.end();
        check(o.ends == 1, "and is not ended twice when the squelch closes");

        System.out.println("AmsGateTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
