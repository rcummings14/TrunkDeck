package p25monitor.analog;

import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/**
 * Analog FM on a mixed-mode (AMS) Fusion repeater. The repeater sends digital Fusion and analog FM on the same
 * frequency, and a Fusion transmission also opens the FM squelch (C4FM is an FM carrier). This sits between the FM
 * receiver and the speaker and lets only analog through:
 * <ul>
 *   <li>A new opening is held for {@link #WAIT_MS} before it becomes an analog call. If the digital decoder syncs in
 *       that time, it is a digital transmission and the held audio is dropped. Nothing is lost: the held audio is
 *       played when it turns out to be analog.</li>
 *   <li>Analog may also start only when the digital decoder has heard no frame for {@link #AFTER_DIGITAL_MS}. A
 *       repeater keeps its carrier up between users, and the FM squelch can close and reopen on that carrier: a real
 *       repeater (K8DF, November 25) gave a 0.4 s false analog call that way, just before the next Fusion user's data.
 *       The time is the digital decoder's, so it carries over squelch openings. An analog user who keys up right after
 *       a digital one is still played from the start (the held audio), only later.</li>
 *   <li>A running analog call ends as soon as digital is heard.</li>
 * </ul>
 * Time is counted in the receiver's 20 ms audio blocks, so the decisions follow the samples, not the wall clock.
 */
public final class AmsGate implements FmReceiver.Listener {
    /** How long a new opening is held while the digital decoder looks for sync. */
    public static final int WAIT_MS = 400;
    /** After digital in the same opening: how long digital must be gone before analog plays. */
    public static final int AFTER_DIGITAL_MS = 3000;
    /** A good digital frame this recent means digital is on the air now (Fusion sends one every 100 ms). */
    public static final int DIGITAL_NOW_MS = 250;
    static final int BLOCK_MS = 20;

    private final FmReceiver.Listener out;
    private final LongSupplier sinceDigitalMs;
    private final ArrayDeque<float[]> held = new ArrayDeque<>();
    private boolean open, active;
    private int heldMs;
    private double tone;

    /** @param sinceDigitalMs how long ago (ms of signal) the digital decoder on the same channel last heard a good frame */
    public AmsGate(LongSupplier sinceDigitalMs, FmReceiver.Listener out) { this.sinceDigitalMs = sinceDigitalMs; this.out = out; }

    @Override public void start(double toneHz) {
        open = true; active = false; heldMs = 0; tone = toneHz; held.clear();
    }

    @Override public void tone(double toneHz) { tone = toneHz; if (active) out.tone(toneHz); }

    @Override public void audio(float[] pcm) {
        if (!open) return;
        if (sinceDigitalMs.getAsLong() < DIGITAL_NOW_MS) {
            if (active) { active = false; out.end(); }
            heldMs = 0; held.clear();
            return;
        }
        if (active) { out.audio(pcm); return; }
        held.addLast(pcm);
        heldMs += BLOCK_MS;
        if (heldMs >= WAIT_MS && sinceDigitalMs.getAsLong() >= AFTER_DIGITAL_MS) {
            active = true;
            out.start(tone);
            while (!held.isEmpty()) out.audio(held.pollFirst());
        }
    }

    @Override public void end() {
        if (active) out.end();
        open = false; active = false; heldMs = 0; held.clear();
    }

    /** True while an analog call is being passed on. */
    public boolean active() { return active; }
}
