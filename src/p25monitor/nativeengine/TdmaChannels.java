package p25monitor.nativeengine;

import p25monitor.p25.P2Framer;
import p25monitor.p25.P2Mac;
import p25monitor.p25.P2Scrambler;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 2 TDMA voice channels. One RF channel carries two time slots, so up to two calls (two talkgroups) share one
 * {@link ChannelDecoder}: it is created when the first slot's call starts, each slot's voice and MAC PDUs go to the call
 * on that slot, and it is removed when neither slot has a call. Each call still has its own AMBE+2 codec, recording,
 * feeds and upload, exactly like a Phase 1 call. The descrambling seed is the site's WACN, system ID and NAC from the
 * control channel (e.g. A4321 / 2D5 / 2D5).
 */
final class TdmaChannels {
    final NativeEngine e;
    TdmaChannels(NativeEngine e) { this.e = e; }

    static final class Channel {
        final long freq; final Tuner tuner; ChannelDecoder vd;
        final ActiveCall[] slot = new ActiveCall[2];
        Channel(long f, Tuner t) { freq = f; tuner = t; }
        boolean empty() { return slot[0] == null && slot[1] == null; }
    }

    final Map<Long, Channel> channels = new ConcurrentHashMap<>();

    /** Scrambling seed from the control channel, or null while the WACN / system ID are not known. */
    P2Scrambler scrambler() {
        if (e.wacn < 0 || e.sysId < 0 || e.nac < 0) return null;
        return new P2Scrambler((int) e.wacn, e.sysId, e.nac);
    }

    /** Attaches call c (time slot from its grant) to the TDMA decoder of its frequency on tuner t; returns the decoder. */
    ChannelDecoder attach(ActiveCall c, Tuner t, double afc) {
        synchronized (e) {
            Channel ch = channels.get(c.freq);
            if (ch != null && ch.tuner != t) t = ch.tuner;  // the other slot's call already has this channel on a tuner: share it
            if (ch == null) {
                final Tuner tu = t;
                Channel nc = new Channel(c.freq, tu);
                nc.vd = new ChannelDecoder(tu.decoderRate(), tu.decoderCenter(c.freq), c.freq, new P2Framer.Listener() {
                    public void voice(int s, byte[][] f, int duid) { ActiveCall a = nc.slot[s]; if (a != null) e.voice.onP2Voice(a, f); }
                    public void mac(int s, P2Mac m) { ActiveCall a = nc.slot[s]; if (a != null) e.voice.onP2Mac(a, m); }
                    public void syncLost() { for (ActiveCall a : nc.slot) if (a != null) e.voice.onVoiceLost(a); }
                }, afc);
                nc.vd.p2.scrambler = scrambler();
                final long vf = c.freq;
                nc.vd.anchor = () -> e.tunerAnchor(tu, nc.vd, vf);
                channels.put(c.freq, nc);
                tu.decoders.add(nc.vd);
                ch = nc;
            }
            int s = Math.max(0, Math.min(1, c.slot));
            ActiveCall old = ch.slot[s];
            if (old != null && old != c && !old.ended) e.calls.endCall(old, "slot " + (s + 1) + " now carries TG " + c.tg);
            ch.slot[s] = c;
            return ch.vd;
        }
    }

    /** The tuner already decoding this TDMA channel (for the other slot's call), or null. */
    Tuner tunerFor(long freq) { Channel ch = channels.get(freq); return ch == null ? null : ch.tuner; }

    /** Detaches a call; the decoder goes when neither slot has a call. True if the decoder was removed. */
    boolean detach(ActiveCall c) {
        synchronized (e) {
            Channel ch = channels.get(c.freq);
            if (ch == null) return true;
            for (int s = 0; s < 2; s++) if (ch.slot[s] == c) ch.slot[s] = null;
            if (!ch.empty()) return false;
            channels.remove(c.freq);
            ch.tuner.decoders.remove(ch.vd);
            return true;
        }
    }

    /** Keeps every channel's descrambler on the site's current WACN / system / NAC (learned after the call started). */
    void refreshScrambler() {
        P2Scrambler want = null; boolean have = false;
        for (Channel ch : channels.values()) {
            P2Scrambler cur = ch.vd.p2.scrambler;
            if (!have) { want = scrambler(); have = true; }
            if (want != null && (cur == null || !cur.same(want.wacn, want.sysId, want.nac))) ch.vd.p2.scrambler = want;
        }
    }

    void clear() { channels.clear(); }
}
