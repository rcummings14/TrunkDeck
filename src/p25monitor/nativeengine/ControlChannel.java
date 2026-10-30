package p25monitor.nativeengine;

import p25monitor.p25.*;
import java.util.*;

/**
 * The control-channel side: decoders for every listed control channel inside the control tuner's window (the active
 * one plus the alternate for instant fallback), TSBK parsing and grants, hunting main -> alternate when control data
 * stops, and re-centring the control tuner for voice coverage while keeping the active control channel inside.
 */
final class ControlChannel {
    final NativeEngine e;
    ControlChannel(NativeEngine e) { this.e = e; }

    // ------------------------------------------------------------------ decoders
    double anchor(ChannelDecoder d) {
        long now = System.currentTimeMillis();
        for (ChannelDecoder o : e.ccDecoders) if (o != d && o.locked(now) && o.freqHz > 0) return o.frequencyError() * d.freqHz / o.freqHz;
        return Double.NaN;
    }

    void buildDecoders(int expectedNac) {
        List<ChannelDecoder> list = new ArrayList<>();
        double afc = 0;
        for (ChannelDecoder old : e.ccDecoders) if (old.locked(System.currentTimeMillis() + 60000)) afc = old.afcHz;
        List<Long> fs = new ArrayList<>();
        if (e.ccCenter <= 0) fs.addAll(e.ccGroups.get(e.ccGroup));
        else for (long f : e.ccFreqs) if (e.ccTuner.covers(f) || Math.abs(f - e.ccCenter) < e.ccSource.sampleRate() * 0.45 && Math.abs(f - e.ccCenter) >= e.ccTuner.dcGuard) fs.add(f);
        if (fs.isEmpty()) fs.addAll(e.ccGroups.get(e.ccGroup));
        for (long f : fs) {
            ChannelDecoder d = new ChannelDecoder(e.ccTuner.decoderRate(), e.ccTuner.decoderCenter(f), f, new P25Framer.Listener() {
                public void tsbk(Tsbk t) { onTsbk(t, f); }
                public void tsbkError() { e.tsbkBad++; }
                public void ldu(int nac, int duid, byte[][] imbe, P25Framer.LinkControl lc, P25Framer.EncryptionSync ess) { e.voice.onConventional(f, nac, imbe, lc, ess); }
                public void terminator(int nac, int duid) { e.voice.onConventionalEnd(f); }
            }, afc, e.modulation(), e.lsmPreferred, expectedNac);
            d.anchor = () -> anchor(d);
            list.add(d);
        }
        e.ccDecoders.clear();
        e.ccDecoders.addAll(list);
    }

    /** Retunes the control tuner and rebuilds the control-channel decoders. */
    void retuneTo(long c, String why) {
        Tuner t = e.ccTuner;
        if (!t.src.tune(c)) return;
        var tap = e.retuneTap; if (tap != null) tap.accept(new long[]{t.id, e.calls.callsOn(t), c});
        e.ccCenter = c; t.center = c;
        t.retunes++; t.lastRetuneAt = System.currentTimeMillis();
        e.log("Control tuner T1 -> " + t.window() + " (" + why + ")");
        if (e.sys != null) buildDecoders(NativeEngine.parseNac(e.sys.nac));
    }

    // ------------------------------------------------------------------ messages
    void onTsbk(Tsbk t, long fromHz) {
        synchronized (e) {
            long now = System.currentTimeMillis();
            e.lastTsbkAt = now;
            e.ccFreqHz = fromHz;
            e.nac = t.nac;
            e.msgTotal++;
            e.pendingMsgs++;
            ChannelMap map = e.map;
            if (t.standard()) switch (t.opcode) {
                case Tsbk.IDEN_UP, Tsbk.IDEN_UP_VU, Tsbk.IDEN_UP_TDMA -> map.update(ChannelMap.Band.from(t));
                case Tsbk.NET_STS_BCST -> { e.wacn = t.bits(24, 20); e.sysId = (int) t.bits(44, 12); }
                case Tsbk.RFSS_STS_BCST -> { e.sysId = (int) t.bits(28, 12); e.rfss = t.u8(5); e.site = t.u8(6); }
                case Tsbk.ADJ_STS_BCST -> { long f = map.frequency(t.u16(7)); e.adjacent.put(String.format("%03d-%03d", t.u8(5), t.u8(6)), f > 0 ? NativeEngine.mhz(f) + " MHz" : "channel " + (t.u16(7) >> 12) + "-" + (t.u16(7) & 0xfff)); }
                case Tsbk.GRP_V_CH_GRANT -> grant(t.u16(5), t.u24(7), t.u16(3), t.u8(2), false, false);
                case Tsbk.GRP_V_CH_GRANT_UPDT -> { grant(t.u16(4), -1, t.u16(2), 0, true, false); if (t.u16(8) != t.u16(4)) grant(t.u16(8), -1, t.u16(6), 0, true, false); }
                case Tsbk.GRP_V_CH_GRANT_UPDT_EXP -> grant(t.u16(8), -1, t.u16(4), t.u8(2), true, false);
                case Tsbk.UU_V_CH_GRANT -> unitGrant(t.u24(4), t.u24(7), t.u16(2), false);
                case Tsbk.UU_V_CH_GRANT_UPDT -> unitGrant(t.u24(4), t.u24(7), t.u16(2), true);
                default -> {}
            }
            else if (t.mfid == 0x90 && t.opcode == 0x02) grant(t.u16(5), t.u24(7), t.u16(3), t.u8(2), false, true);   // Motorola patch/supergroup grant
            else if (t.mfid == 0x90 && t.opcode == 0x03) { grant(t.u16(4), -1, t.u16(2), 0, true, true); if (t.u16(8) != t.u16(4)) grant(t.u16(8), -1, t.u16(6), 0, true, true); }
            String text = t.standard() ? t.describe(map) : t.mfid == 0x90 && (t.opcode == 2 || t.opcode == 3)
                    ? (t.opcode == 2 ? "MOTOROLA PATCH GROUP GRANT SG:" + t.u16(5) + " SRC:" + t.u24(7) : "MOTOROLA PATCH GROUP GRANT UPDATE SG:" + t.u16(4)) : t.describe(map);
            e.msgs.add(new NativeEngine.Msg(now, fromHz / 1e6, text));
            while (e.msgs.size() > 3000) e.msgs.pollFirst();
            e.lastMsg = text;
        }
    }

    void grant(int tg, int src, int channel, int so, boolean update, boolean patch) {
        long now = System.currentTimeMillis();
        String key = "G" + tg;
        ActiveCall c = e.callMap.get(key);
        if (c != null && c.channel != channel && !update) { e.calls.endCall(c, "new grant on another channel"); c = null; }
        if (c == null) {
            Long ended = e.recentlyEnded.get(key);
            if (update && ended != null && now - ended < 2500) return;   // stale update after the call ended
            c = new ActiveCall();
            c.tg = tg; c.src = src; c.channel = channel; c.start = now; c.lastSeen = now; c.patch = patch;
            c.freq = e.map.frequency(channel);
            c.encrypted = Tsbk.encrypted(so); c.emergency = Tsbk.emergency(so);
            c.phase2 = e.map.isTdma(channel);
            c.t = e.tg(tg);
            if (c.t != null) c.priority = c.t.priority;
            e.callMap.put(key, c);
            e.calls.newCall(c);
        } else {
            c.lastSeen = now;
            if (src > 0 && c.src <= 0) { c.src = src; c.ev.from = String.valueOf(src); c.ev.radioId = src; e.listener.callUpdated(c.ev); }
            if (!update) { c.encrypted |= Tsbk.encrypted(so); c.emergency |= Tsbk.emergency(so); }
        }
    }

    void unitGrant(int target, int src, int channel, boolean update) {
        long now = System.currentTimeMillis();
        String key = "U" + target;
        ActiveCall c = e.callMap.get(key);
        if (c == null) {
            Long ended = e.recentlyEnded.get(key);
            if (update && ended != null && now - ended < 2500) return;
            c = new ActiveCall();
            c.unitToUnit = true; c.tg = target; c.src = src; c.channel = channel; c.start = now; c.lastSeen = now;
            c.freq = e.map.frequency(channel); c.phase2 = e.map.isTdma(channel);
            e.callMap.put(key, c);
            e.calls.newCall(c);
        } else c.lastSeen = now;
    }

    // ------------------------------------------------------------------ hunting and placement
    /**
     * No control data for 6 s: the site uses one control channel at a time (main or alternate). If a listed control
     * channel has no decoder (it is outside the window), retune so it is covered; otherwise cycle through the groups.
     */
    void hunt(long now) {
        synchronized (e) {
            e.lastHuntAt = now;
            List<Long> missing = new ArrayList<>();
            for (long f : e.ccFreqs) { boolean has = false; for (ChannelDecoder d : e.ccDecoders) if (d.freqHz == f) has = true; if (!has) missing.add(f); }
            if (missing.isEmpty() && e.ccGroups.size() <= 1) return;   // every control channel already has a decoder
            long target;
            String what;
            if (!missing.isEmpty()) {
                long f = missing.get((int) (e.huntCount++ % missing.size()));
                List<Long> g = new ArrayList<>(e.ccFreqs);                  // cover all listed CCs if they fit, else just f
                Collections.sort(g);
                long all = TunerPlanner.plan(g, Map.of(), x -> false, e.ccTuner.halfSpan(), e.ccTuner.dcGuard, 0);
                target = all > 0 ? all : TunerPlanner.plan(List.of(f), Map.of(), x -> false, e.ccTuner.halfSpan(), e.ccTuner.dcGuard, 0);
                what = NativeEngine.mhz(f) + " MHz";
            } else { e.ccGroup = (e.ccGroup + 1) % e.ccGroups.size(); target = NativeEngine.centerFor(e.ccGroups.get(e.ccGroup)); what = "the next control-channel group"; }
            if (target <= 0) return;
            for (ActiveCall c : new ArrayList<>(e.callMap.values())) if (c.tuner == e.ccTuner && c.vd != null) e.calls.endCall(c, "control channel lost - retuning");
            retuneTo(target, "no control data for 6 s - trying " + what);
        }
    }

    /**
     * Once locked and voice usage is known, the control tuner is re-centred to cover as much voice traffic as possible,
     * keeping the active control channel inside (and the alternate when it fits) - only while it carries no call, at
     * most every 2 minutes, and only for a clear gain.
     */
    void plan(long now) {
        Tuner t = e.ccTuner;
        if (t == null || e.ccCenter <= 0 || e.mode == NativeEngine.Mode.FILE || !Boolean.parseBoolean(e.app.settings.getProperty("native.ccRecenter", "true"))) return;
        if (e.mode != NativeEngine.Mode.RTL && !e.huntable) return;
        if (t.wideband()) { planWide(now); return; }
        long every = Long.getLong("trunkdeck.ccPlanMs", 120_000L);
        if (now - e.lastTsbkAt > 1500 || e.ccFreqHz <= 0 || now - e.lastCcPlanAt < every || e.calls.callsOn(t) > 0) return;
        e.lastCcPlanAt = now;
        Map<Long, Double> w = new HashMap<>(e.usage);
        double total = 0; for (double x : w.values()) total += x;
        if (w.size() < 3 || total < 5) return;
        java.util.function.LongPredicate other = f -> e.calls.coveredElsewhere(t, f);
        for (long f : e.ccFreqs) if (f != e.ccFreqHz) w.merge(f, Math.max(2.0, total * 0.1), Double::sum);   // keep the alternate CC if it fits
        long c = TunerPlanner.plan(List.of(e.ccFreqHz), w, other, t.halfSpan(), t.dcGuard, e.ccCenter);
        if (c <= 0 || Math.abs(c - e.ccCenter) < 50_000) return;
        double before = TunerPlanner.score(e.ccCenter, w, other, t.halfSpan(), t.dcGuard), after = TunerPlanner.score(c, w, other, t.halfSpan(), t.dcGuard);
        if (after < before + Math.max(1.0, total * 0.1)) return;
        retuneTo(c, String.format(Locale.ROOT, "re-centred for voice coverage (score %.1f -> %.1f), active control channel %s kept inside", before, after, NativeEngine.mhz(e.ccFreqHz)));
    }

    // ------------------------------------------------------------------ wideband (Airspy) control tuner
    /**
     * A wideband control tuner decodes the control channel and every voice channel in its ~9 MHz window and does not
     * roam. Its centre is planned: first to cover the control channels, then - as soon as the IDEN_UP messages give the
     * site's voice band - to cover that band, and later the voice channels the grants actually use (calls in progress
     * stay inside). Re-centring is live: decoders keep running and just move to their new sub-band.
     */
    void planWide(long now) {
        Tuner t = e.ccTuner;
        if (e.fixedWideCenter > 0 || now - e.lastTsbkAt > 1500 || e.ccFreqHz <= 0) return;
        List<long[]> bands = new ArrayList<>();
        for (ChannelMap.Band b : e.map.bands()) bands.add(new long[]{b.baseHz, b.spacingHz});
        boolean first = !e.widePlanned && !bands.isEmpty();
        long every = Long.getLong("trunkdeck.widePlanMs", 20_000L);
        if (!first && now - e.lastCcPlanAt < every) return;
        Map<Long, Double> w = new HashMap<>(e.usage);
        double total = 0; for (double x : w.values()) total += x;
        if (!first && w.isEmpty()) return;
        e.lastCcPlanAt = now;
        if (!bands.isEmpty()) e.widePlanned = true;
        double half = t.halfSpan();
        List<Long> must = new ArrayList<>(e.ccFreqs);
        if (must.isEmpty() || Collections.max(must) - Collections.min(must) > 2 * half - 200_000) must = new ArrayList<>(List.of(e.ccFreqHz));
        List<Long> withCalls = new ArrayList<>(must);
        int onT = 0;
        for (ActiveCall c : e.callMap.values()) if (c.tuner == t && c.vd != null && !c.ended && c.freq > 0) { withCalls.add(c.freq); onT++; }
        java.util.function.LongPredicate other = f -> e.calls.coveredElsewhere(t, f);
        long c = TunerPlanner.planWide(withCalls, w, bands, other, half, t.dcGuard, e.ccCenter);
        if (c <= 0) {                       // the calls in progress cannot all stay inside: wait until they end
            if (onT > 0) return;
            c = TunerPlanner.planWide(must, w, bands, other, half, t.dcGuard, e.ccCenter);
        }
        if (c <= 0 || Math.abs(c - e.ccCenter) < 50_000) return;
        double before = TunerPlanner.score(e.ccCenter, w, other, half, t.dcGuard), after = TunerPlanner.score(c, w, other, half, t.dcGuard);
        if (!first && after < before + Math.max(0.5, total * 0.05)) return;
        if (first && after < before) return;
        String why = first && total < 1 ? "voice band from the site's channel plan (IDEN_UP)"
                : String.format(Locale.ROOT, "voice coverage %.1f -> %.1f of %.1f (grants seen)", before, after, total);
        recenterWide(c, why);
    }

    /** Moves the wideband control tuner without restarting its decoders. */
    void recenterWide(long c, String why) {
        Tuner t = e.ccTuner;
        if (!t.src.tune(c)) return;
        var tap = e.retuneTap; if (tap != null) tap.accept(new long[]{t.id, e.calls.callsOn(t), c});
        e.ccCenter = c; t.center = c;
        t.retunes++; t.lastRetuneAt = System.currentTimeMillis();
        for (ChannelDecoder d : e.ccDecoders) d.recenter(t.decoderCenter(d.freqHz));
        for (ChannelDecoder d : t.decoders) d.recenter(t.decoderCenter(d.freqHz));
        e.log("Control tuner T1 (wideband) -> " + t.window() + " (" + why + ")");
        boolean rebuild = false;
        for (long f : e.ccFreqs) {
            boolean has = false; for (ChannelDecoder d : e.ccDecoders) if (d.freqHz == f) has = true;
            if (has != t.covers(f)) rebuild = true;
        }
        if (rebuild && e.sys != null) buildDecoders(NativeEngine.parseNac(e.sys.nac));
        for (ActiveCall a : new ArrayList<>(e.callMap.values())) if (a.tuner == t && a.vd != null && !t.covers(a.freq)) e.calls.endCall(a, "outside the re-centred window");
    }
}
