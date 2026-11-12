package p25monitor.nativeengine;

import p25monitor.CallEvent;
import p25monitor.ListenMode;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Call lifecycle and the tuner pool. Every eligible grant (Phase 1, or one Phase 2 time slot) is decoded if any tuner's usable window covers its
 * frequency, each with its own channel decoder and voice codec. A grant outside all windows takes a roaming tuner that
 * carries no active call (it is retuned to cover the new call plus as much recent channel usage as possible); a tuner
 * with an active call is never moved. Calls that cannot get a tuner wait (highest priority, then newest, first when a
 * tuner frees up) and are logged as MISSED with the reason if they end before that.
 */
final class CallManager {
    final NativeEngine e;
    CallManager(NativeEngine e) { this.e = e; }

    /** Calls decoded at the same time: Settings "native.maxCalls", else the system's "Max simultaneous calls" (1-32). */
    int maxCalls() {
        String o = e.app.settings.getProperty("native.maxCalls", "");
        int n = e.sys != null ? e.sys.trafficChannels : 12;
        try { if (!o.isBlank()) n = Integer.parseInt(o.trim()); } catch (Exception ignored) {}
        return Math.max(1, Math.min(32, n));
    }
    int hangMs() { try { return Math.max(300, Math.min(15000, Integer.parseInt(e.app.settings.getProperty("native.hangMs", "2500")))); } catch (Exception x) { return 2500; } }
    /** End of a transmission when no terminator is heard (fade, lost TDU): Settings "native.eotMs", 400-5000, default 800. */
    int eotMs() { try { return Math.max(400, Math.min(5000, Integer.parseInt(e.app.settings.getProperty("native.eotMs", "800").trim()))); } catch (Exception x) { return 800; } }

    /** Closes the call's open transmission (if any) and hands it to Broadcastify Calls straight away. */
    void closeTx(ActiveCall c, String why) {
        Transmission tx;
        synchronized (c) {
            tx = c.tx;
            if (tx == null) { if (c.txSkip) { c.txSkip = false; c.txDone = true; } return; }   // a transmission not uploaded has ended
            c.tx = null; c.txDone = true;
        }
        if (!tx.first) tx.startMs = tx.lastAudioMs - Math.round(tx.seconds() * 1000);   // the real start of this PTT
        tx.end(why);
        Streaming st = e.streaming;
        // the uploads wanted when it began (a stream assignment edited mid-transmission applies from the next one), unless the talkgroup itself was corrected
        p25monitor.Talkgroup t = tx.pinned != null && tx.tg == c.tg ? tx.pinned : c.t;
        if (st != null) st.transmissionEnded(t, tx);
        if (c.endAfterTx && !c.ended) endCall(c, "muted / no longer listened to");
    }

    /** Talkgroup reasons a call is not followed; anything else (encryption, no voice ...) is not the user's doing. */
    static boolean blockedBySettings(String why) {
        return why != null && (why.startsWith("muted") || why.startsWith("not in talkgroup list") || why.startsWith("not a favorite"));
    }

    /**
     * The user edited talkgroups (1.4.4): calls in progress pick up the new settings at once. A call now muted (or no
     * longer in the list / favorites being listened to) leaves the speaker immediately, queued audio included, and is
     * ended when its current transmission closes (so an upload in progress is not cut); one waiting for a tuner is
     * dropped. A call now allowed is followed from now on. Priority and names follow the edit.
     */
    void talkgroupsChanged() {
        synchronized (e) {
            for (ActiveCall c : new ArrayList<>(e.callMap.values())) {
                if (c.ended || c.ev == null || c.unitToUnit) continue;
                c.t = e.tg(c.tg);
                c.priority = c.t != null ? c.t.priority : 100;
                String why = skipReason(c);
                if (blockedBySettings(why)) {
                    if (c.vd != null) {
                        if (!c.endAfterTx) {
                            c.endAfterTx = true;
                            e.speaker.silence(c);
                            e.log("TG " + c.tg + (c.alias().isEmpty() ? "" : " (" + c.alias() + ")") + ": " + why + " - off the speaker now" + (c.tx != null ? ", the call ends with its transmission" : ""));
                        }
                        Transmission tx = c.tx;
                        if (tx == null) endCall(c, why);
                    } else {
                        boolean wasWaiting = c.waiting;
                        c.eligible = false; c.waiting = false;
                        e.speaker.silence(c);
                        if (wasWaiting) endCall(c, why);
                        else if (!why.toUpperCase().equals(c.ev.details)) { c.ev.details = why.toUpperCase(); e.listener.callUpdated(c.ev); }
                    }
                } else {
                    c.endAfterTx = false;
                    if (!c.eligible && why == null) {
                        c.eligible = true;
                        c.txDone = true;   // joined mid-call: its first transmission starts now, not at the grant
                        c.ev.details = "";
                        e.listener.callUpdated(c.ev);
                        e.log("TG " + c.tg + (c.alias().isEmpty() ? "" : " (" + c.alias() + ")") + ": now followed (talkgroup settings changed)");
                        assign(c);
                    } else if (c.ev.details != null && blockedBySettings(c.ev.details.toLowerCase()) && why != null) {
                        c.ev.details = why.toUpperCase(); e.listener.callUpdated(c.ev);
                    }
                }
            }
        }
    }

    /** Every 50 ms: transmissions that went silent without a terminator. */
    void transmissionTick(long now) {
        int eot = eotMs();
        for (ActiveCall c : e.callMap.values()) {
            Transmission tx = c.tx;
            if (tx != null && now - Math.max(c.lastVoice, tx.lastAudioMs) > eot) closeTx(c, "no terminator, " + eot + " ms timeout");
            else if (tx == null && c.txSkip && now - c.lastVoice > eot) closeTx(c, "no terminator");
        }
    }

    boolean recordAll() { return (e.sys != null && e.sys.recordAll) || Boolean.parseBoolean(e.app.settings.getProperty("native.recordAll", "false")); }

    /** Why a call can't / mustn't be followed, or null if it can. */
    String skipReason(ActiveCall c) {
        ListenMode lm = e.app.listenMode();
        if (c.freq <= 0) return "channel " + (c.channel >> 12) + "-" + (c.channel & 0xfff) + " not in band plan yet";
        if (c.unitToUnit) return "unit-to-unit call (not followed)";
        if (c.t != null && c.t.muted) return "muted / locked out";
        if (lm == ListenMode.LISTED && c.t == null) return "not in talkgroup list";
        if (lm == ListenMode.FAVORITES && (c.t == null || !c.t.favorite)) return "not a favorite";
        if (c.encrypted) return "encrypted (not decrypted, not followed)";
        Long u = e.undecodable.get(c.key());
        if (u != null && System.currentTimeMillis() - u < 30000) return "no Phase " + (c.phase2 ? 2 : 1) + " voice decoded on its channel a moment ago (see Engine diagnostics)";
        return null;
    }

    void newCall(ActiveCall c) {
        String why = skipReason(c);
        CallEvent ev = new CallEvent();
        ev.timestamp = e.ts.format(new Date(c.start));
        ev.protocol = "APCO-25";
        ev.event = (c.phase2 ? "Phase 2 " : "") + (c.emergency ? "Emergency " : "") + (c.encrypted ? "Encrypted " : "") + (c.unitToUnit ? "Unit To Unit Call" : c.patch ? "Patch Group Call" : "Group Call");
        ev.from = c.src > 0 ? String.valueOf(c.src) : "";
        ev.to = String.valueOf(c.tg);
        ev.talkgroup = c.unitToUnit ? -1 : c.tg;
        ev.radioId = c.src;
        ev.channel = (c.channel >> 12) + "-" + (c.channel & 0xfff);
        ev.frequencyMHz = c.freq / 1e6;
        ev.timeslot = c.phase2 ? "TS" + (e.map.timeslot(c.channel) + 1) : "";
        ev.details = why == null ? "" : why.toUpperCase();
        ev.background = true;          // shown on the display once it is actually heard on the speaker
        c.ev = ev;
        c.eligible = why == null;
        if (c.freq > 0 && !c.unitToUnit) e.usage.merge(c.freq, c.eligible ? 1.0 : 0.25, Double::sum);
        e.listener.callStarted(ev);
        if (c.eligible) assign(c);
    }

    int callsOn(Tuner t) { int n = 0; for (ActiveCall c : e.callMap.values()) if (c.tuner == t && !c.ended) n++; return n; }
    int decoding() { int n = 0; for (ActiveCall c : e.callMap.values()) if (c.vd != null && !c.ended) n++; return n; }

    /** The tuner covering f with the channel furthest from its edges, or null. */
    Tuner covering(long f) {
        Tuner best = null; double bestMargin = -1;
        for (Tuner t : e.tuners) if (t.covers(f)) {
            double m = t.halfSpan() - Math.abs(f - t.center);
            if (m > bestMargin) { bestMargin = m; best = t; }
        }
        return best;
    }

    boolean coveredElsewhere(Tuner self, long f) { for (Tuner t : e.tuners) if (t != self && t.covers(f)) return true; return false; }

    /** A roaming tuner with no active call (the one retuned longest ago), or null. */
    Tuner freeRoamer() {
        Tuner best = null;
        for (Tuner t : e.tuners) if (t.role == Tuner.Role.ROAMING && callsOn(t) == 0 && (best == null || t.lastRetuneAt < best.lastRetuneAt)) best = t;
        return best;
    }

    /** Gives the call a tuner and a decoder if possible; otherwise it waits. */
    boolean assign(ActiveCall c) {
        synchronized (e) {
            if (c.vd != null || c.ended) return true;
            if (decoding() >= maxCalls()) { markWaiting(c, "CALL LIMIT (" + maxCalls() + " CALLS) REACHED"); return false; }
            Tuner t = c.phase2 ? e.tdma.tunerFor(c.freq) : null;   // the other time slot's call already has this channel
            if (t == null) t = covering(c.freq);
            if (t == null) {
                Tuner r = freeRoamer();
                if (r != null) {
                    long cen = planRoamer(r, c);
                    if (cen > 0 && retuneRoamer(r, cen, "TG " + c.tg + " on " + NativeEngine.mhz(c.freq))) t = r;
                }
            }
            if (t == null) {
                markWaiting(c, e.tuners.size() == 1 ? "OUTSIDE THE DONGLE'S WINDOW - A 2ND DONGLE WOULD COVER IT"
                        : "WAITING FOR A FREE TUNER (" + NativeEngine.mhz(c.freq) + " IS OUTSIDE ALL WINDOWS)");
                return false;
            }
            e.voice.start(c, t);
            return true;
        }
    }

    void markWaiting(ActiveCall c, String why) {
        if (!c.waiting) { c.waiting = true; c.waitingSince = System.currentTimeMillis(); }
        if (!why.equals(c.ev.details)) { c.ev.details = why; e.listener.callUpdated(c.ev); }
    }

    /** Centre for a free roaming tuner: c inside, plus the most usage (and other waiting calls) not covered elsewhere. */
    long planRoamer(Tuner r, ActiveCall c) {
        Map<Long, Double> w = new HashMap<>(e.usage);
        long now = System.currentTimeMillis();
        for (ActiveCall o : e.callMap.values()) if (o != c && o.waiting && o.eligible && !o.ended && now - o.lastSeen < 2500)
            w.merge(o.freq, 5.0 + Math.max(0, 100 - o.priority) / 10.0, Double::sum);
        return TunerPlanner.plan(List.of(c.freq), w, f -> coveredElsewhere(r, f), r.halfSpan(), r.dcGuard, r.center);
    }

    boolean retuneRoamer(Tuner r, long center, String why) {
        int active = callsOn(r);
        var tap = e.retuneTap; if (tap != null) tap.accept(new long[]{r.id, active, center});
        if (active > 0) { e.log("Refusing to retune T" + r.id + ": " + active + " active call(s) on it"); return false; }
        if (!r.src.tune(center)) return false;
        r.center = center; r.retunes++; r.lastRetuneAt = System.currentTimeMillis();
        e.retunes++;
        int ch = 0; for (long f : e.usage.keySet()) if (r.covers(f)) ch++;
        e.log("Voice tuner T" + r.id + " -> " + r.window() + " for " + why + " (covers " + ch + " of " + e.usage.size() + " channels seen so far)");
        return true;
    }

    /** Waiting calls: start those now inside a window; give free roaming tuners to the best waiting call. */
    void serviceWaiting(long now) {
        synchronized (e) {
            List<ActiveCall> waiting = new ArrayList<>();
            for (ActiveCall c : e.callMap.values()) if (c.waiting && c.eligible && !c.ended && c.vd == null && skipReason(c) == null) waiting.add(c);
            if (waiting.isEmpty()) return;
            for (ActiveCall c : waiting) if (covering(c.freq) != null) assign(c);
            while (true) {
                if (freeRoamer() == null || decoding() >= maxCalls()) break;
                ActiveCall best = null;
                for (ActiveCall c : waiting) if (c.vd == null && !c.ended && now - c.lastSeen < 2500
                        && (best == null || c.priority < best.priority || (c.priority == best.priority && c.lastSeen > best.lastSeen))) best = c;
                if (best == null || !assign(best)) break;
                for (ActiveCall c : waiting) if (c.vd == null && !c.ended && covering(c.freq) != null) assign(c);
            }
        }
    }

    /** Ends a call: frees its decoder (and so its tuner), finishes its recording/upload, logs a MISSED call if it never got a tuner. */
    void endCall(ActiveCall c, String why) {
        synchronized (e) {
            if (c.ev == null || c.ended) return;
            c.ended = true;
            long now = System.currentTimeMillis();
            e.callMap.values().remove(c);
            e.recentlyEnded.put(c.key(), now);
            long end = Math.max(c.lastSeen, c.lastVoice);
            c.ev.durationMs = Math.max(Math.max(0, Math.min(now, end) - c.start), c.voiceFrames * 180L);
            ChannelDecoder v = c.vd;
            boolean stopping = why.equals("engine stopped");
            if (v != null) {
                boolean dmrDec = v.dmr != null;
                if (!dmrDec && c.tuner != null && c.tuner != e.ccTuner && c.voiceFrames > 3) e.voice.learnPpm(c.tuner, v);
                if (!dmrDec && c.imbeFrames > 0)
                    e.log(String.format(Locale.ROOT, "TG %d call (T%d %s%s): %d voice frames, %.2f corrected bits/frame, %.0f%% muted, %.0f%% repeated; %s; level %s; offset %+.0f Hz; %s; %s errors %s",
                            c.tg, c.tuner == null ? 0 : c.tuner.id, NativeEngine.mhz(c.freq), c.phase2 ? " slot " + (c.slot + 1) : "", c.imbeFrames, (double) c.imbeErrSum / c.imbeFrames, 100.0 * c.imbeBad / c.imbeFrames, 100.0 * c.imbeRepeat / c.imbeFrames,
                            v.demodSummary(), Double.isNaN(v.syncPower) ? "-" : String.format(Locale.ROOT, "%.1f dB", v.syncPower), v.frequencyError(), TunerAdvice.streamSummary(v), c.phase2 ? "AMBE+2" : "IMBE", TunerAdvice.histSummary(c)));
                if (!dmrDec && ChannelDecoder.TRACE) e.log("TG " + c.tg + " trace: " + v.traceText());
                if (!dmrDec && c.voiceFrames == 0 && !c.encrypted && !c.conventional && !stopping) {
                    boolean sync = v.syncs() > c.syncBase;
                    String ph = c.phase2 ? "PHASE 2" : "PHASE 1";
                    c.ev.details = (sync ? ph + " SYNC BUT NO VOICE FRAMES ON " : "NO " + ph + " SIGNAL ON ") + NativeEngine.mhz(c.freq) + (sync || c.tuner == null ? "" : " - CHECK PPM/GAIN OF T" + c.tuner.id);
                    e.undecodable.put(c.key(), now);
                    countMissed(c.phase2 ? "no Phase 2 voice decoded" : "no Phase 1 voice decoded", c);
                    e.log("TG " + c.tg + ": " + c.ev.details.toLowerCase() + " (offset " + String.format(Locale.ROOT, "%+.0f Hz", v.frequencyError()) + ", level " + String.format(Locale.ROOT, "%.1f dB", v.power) + ")");
                }
                if (!dmrDec && (c == e.speaker.speaker || c == e.speaker.last)) { String[][] d = e.diag.callRows(c, v, why); if (d != null) e.lastDiag = d; }
                boolean shared = false;
                if (dmrDec) for (ActiveCall o : e.callMap.values()) if (o != c && !o.ended && o.vd == v) shared = true;
                boolean ccDec = false;
                if (dmrDec) for (ChannelDecoder d : e.ccDecoders) if (d == v) ccDec = true;
                boolean last = !shared && !ccDec && (dmrDec || !c.phase2 || e.tdma.detach(c));    // a TDMA channel stays while the other slot has a call
                if (last) { Path p = v.stopTap(); if (p != null) e.log("Saved voice-channel I/Q: " + p); }
                if (!shared && !ccDec && c.tuner != null && (dmrDec || !c.phase2)) c.tuner.decoders.remove(v);
                c.vd = null;
            } else if (c.eligible && c.waiting && !stopping) {
                String reason = c.ev.details != null && c.ev.details.startsWith("CALL LIMIT") ? "call limit reached" : "out of tuner range";
                countMissed(reason, c);
                c.ev.details = "MISSED - " + reason.toUpperCase() + " (" + NativeEngine.mhz(c.freq) + " MHZ)";
                e.log("MISSED TG " + c.tg + (c.alias().isEmpty() ? "" : " (" + c.alias() + ")") + " on " + NativeEngine.mhz(c.freq) + " MHz: " + reason + " (waited " + (now - c.waitingSince) / 1000 + " s)");
            }
            c.waiting = false;
            e.speaker.release(c);
            closeTx(c, "call ended: " + why);
            if (e.streaming != null) e.streaming.callEnded(c.id);
            finishAudio(c);
            e.listener.callUpdated(c.ev);
            if (e.running && !stopping) serviceWaiting(now);
        }
    }

    void countMissed(String reason, ActiveCall c) {
        e.missed.merge(reason, 1L, Long::sum);
        e.missedRecent.add(new java.text.SimpleDateFormat("HH:mm:ss").format(new Date()) + "  TG " + c.tg + "  " + NativeEngine.mhz(c.freq) + " MHz  " + reason);
        while (e.missedRecent.size() > 10) e.missedRecent.pollFirst();
    }

    /** Every call is recorded and uploaded on its own, from its own audio. */
    void finishAudio(ActiveCall c) {
        float[] pcm; int n; List<long[]> src;
        synchronized (c) { pcm = c.pcm; n = c.pcmLen; src = new ArrayList<>(c.sources); }   // consistent with a decoder still appending (emit)
        if (n == 0) return;
        if (e.streaming != null) {
            if (src.isEmpty() && c.src > 0) src.add(new long[]{0, c.src});
            e.streaming.callFinished(c.t, new OpenMhzUploader.Call(c.tg, c.freq, c.start, pcm, n, src, c.emergency, (int) Math.min(Integer.MAX_VALUE, c.imbeErrSum), List.of()), Math.max(0, c.src));
        }
        if ((c.t != null && c.t.record) || recordAll()) {
            String stamp = e.fileTs.format(new Date(c.start));
            CompletableFuture.runAsync(() -> {
                try { Path p = Recorder.write(pcm, n, stamp, c.tg, c.src); e.log("Recorded " + p.getFileName()); }
                catch (Exception x) { e.log("Recording failed: " + x.getMessage()); }
            });
        }
    }

    /** Timeouts, hang time, waiting calls. Called every 250 ms. */
    void housekeep(long now) {
        synchronized (e) {
            int hang = hangMs();
            boolean anyWaiting = false;
            for (ActiveCall c : e.callMap.values()) if (c.waiting && !c.ended) anyWaiting = true;
            for (ActiveCall c : new ArrayList<>(e.callMap.values())) {
                if (c.conventional) { if (now - c.lastVoice > 1200) endCall(c, "timeout"); continue; }
                if (c.vd != null) {
                    long silent = now - c.lastVoice;
                    if (c.voiceFrames == 0) { if (now - c.followStart > 3500) endCall(c, "no voice decoded"); continue; }
                    // a roaming tuner that others are waiting for is freed at the end of the hang time even if grant updates continue
                    boolean freeUp = anyWaiting && c.tuner != null && c.tuner.role == Tuner.Role.ROAMING;
                    if (silent > hang && (now - c.lastSeen > 1500 || freeUp)) endCall(c, "call over");
                    else if (silent > 20000) endCall(c, "silent for 20 s");
                    else if (silent > 600 && !c.hang) { c.hang = true; c.hangSince = now - silent; if (e.speaker.speaker == c) e.audio.endOfTransmission(); }
                } else if (now - c.lastSeen > 6000) endCall(c, "grant timeout");
                else if (c.waiting && now - c.lastSeen > 2500) endCall(c, "grant ended");
            }
            serviceWaiting(now);
            e.recentlyEnded.values().removeIf(t -> now - t > 10000);
            if (now - e.usageDecayAt > 60_000) {   // usage half-life about 10 minutes
                e.usageDecayAt = now;
                e.usage.replaceAll((f, w) -> w * 0.933);
                e.usage.values().removeIf(w -> w < 0.05);
            }
        }
    }
}
