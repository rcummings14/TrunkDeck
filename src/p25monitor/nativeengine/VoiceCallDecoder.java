package p25monitor.nativeengine;

import p25monitor.App;
import p25monitor.CallEvent;
import p25monitor.p25.P25Framer;
import p25monitor.p25.P2Mac;
import java.util.*;

/**
 * The voice side of each call: its own channel decoder on a tuner, its own IMBE codec, and its audio going to its own
 * recording, its stream feeds and (when it is the call being heard) the speaker. Voice frames are used only once the
 * channel's link control confirms the talkgroup, so audio of one call never ends up in another.
 * <p>Phase 2 TDMA calls share one channel decoder per RF channel ({@link TdmaChannels}: two time slots, two calls); each
 * call has its own AMBE+2 half-rate codec in {@link ActiveCall#imbe} and its talkgroup is confirmed by the slot's MAC PDUs
 * (MAC_PTT / group voice channel user) the way link control confirms it on Phase 1. The rest of the call handling is the same.
 */
final class VoiceCallDecoder {
    /** Corrected bits per frame at/above which a frame is noise, not voice (random data scores 11-15; real speech 0-3). */
    static final int IMBE_NOISE = 12;
    /** Same for AMBE+2: of 20,000 random frames 97.5% score 5 or more (or fail c0); real clean frames score 0. */
    static final int AMBE_NOISE = 5;

    final NativeEngine e;
    VoiceCallDecoder(NativeEngine e) { this.e = e; }

    void start(ActiveCall c, Tuner t) {
        long now = System.currentTimeMillis();
        double a = e.tunerAnchor(t, null, c.freq);
        double afc = Double.isNaN(a) ? 0 : a;
        if (t == e.ccTuner) {
            boolean listed = false; for (long f : e.ccFreqs) if (Math.abs(f - c.freq) < 1000) listed = true;
            if (listed && e.voiceOnCcLogged.add(-c.freq)) e.log("Voice on " + NativeEngine.mhz(c.freq) + " MHz (the alternate control-channel frequency): decoding it on the control tuner.");
        }
        ActiveCall call = c;
        ChannelDecoder vd;
        if (c.phase2) {
            c.slot = e.map.timeslot(c.channel);
            vd = e.tdma.attach(c, t, afc);
            c.syncBase = vd.syncs(); c.nidOkBase = vd.nidOk(); c.nidBadBase = vd.nidBad();
            if (c.imbe == null) c.imbe = EngineLibs.newAmbe();
            if (c.imbe == null && e.ambeMissingLogged.compareAndSet(false, true))
                e.log("Phase 2 call TG " + c.tg + ": the AMBE+2 voice codec is not available (it needs JMBE 2.x built by the installer, see START-HERE) - calls are followed but silent.");
            e.phase2Calls++;
        } else vd = new ChannelDecoder(t.decoderRate(), t.decoderCenter(c.freq), c.freq, new P25Framer.Listener() {
            public void ldu(int nac, int duid, byte[][] imbe, P25Framer.LinkControl lc, P25Framer.EncryptionSync ess) { onVoice(call, imbe, lc, ess); }
            public void terminator(int nac, int duid) { onVoiceEnd(call); }
            public void nidError() { onVoiceLost(call); }
        }, afc, e.modulation(), e.preferLsm(), NativeEngine.parseNac(e.sys.nac));
        if (!c.phase2) { final long vf = c.freq; final ChannelDecoder d = vd; vd.anchor = () -> e.tunerAnchor(t, d, vf); }
        c.followed = true; c.followStart = now; c.lastVoice = now; c.hang = false; c.waiting = false;
        if (c.imbe == null && !c.phase2) c.imbe = EngineLibs.newImbe();
        c.tuner = t;
        e.follows++;
        if (Boolean.parseBoolean(e.app.settings.getProperty("native.recordIq", "false")) && e.iqRecorded < 5 && !vd.tapping()) {
            try {
                c.iqFile = App.recordingDir().resolve("iq").resolve(e.fileTs.format(new Date(now)) + "_TrunkDeck_IQ_TG" + c.tg + "_" + c.freq + "Hz_48000sps.wav");
                vd.startTap(c.iqFile, t.decoderRate(), 30);
                e.iqRecorded++;
                e.log("Recording voice-channel I/Q to " + c.iqFile.getFileName() + " (up to 30 s; " + (5 - e.iqRecorded) + " more this session)");
            } catch (Exception ex) { e.log("I/Q recording failed: " + ex.getMessage()); c.iqFile = null; }
        }
        c.vd = vd;
        if (!c.phase2) t.decoders.add(vd);
        int n = e.calls.decoding();
        e.peakConcurrent = Math.max(e.peakConcurrent, n);
        c.ev.details = "DECODING " + NativeEngine.mhz(c.freq) + " ON T" + t.id + ((c.t != null && c.t.record) || e.calls.recordAll() ? "  REC" : "");
        e.listener.callUpdated(c.ev);
        e.log("Decoding TG " + c.tg + (c.alias().isEmpty() ? "" : " (" + c.alias() + ")") + " on " + NativeEngine.mhz(c.freq) + " MHz" + (c.phase2 ? " Phase 2 slot " + (c.slot + 1) : "") + ", tuner T" + t.id + " (" + n + " call" + (n == 1 ? "" : "s") + " decoding)");
        e.logTalker(c);
    }

    void learnPpm(Tuner t, ChannelDecoder v) {
        double ppm = v.frequencyError() / (v.freqHz / 1e6);
        t.ppmErr = Double.isNaN(t.ppmErr) ? ppm : 0.5 * t.ppmErr + 0.5 * ppm;
    }

    void onVoice(ActiveCall c, byte[][] imbe, P25Framer.LinkControl lc, P25Framer.EncryptionSync ess) {
        if (c.ended) return;
        long now = System.currentTimeMillis();
        c.ldus++;
        if (lc != null && lc.isGroupVoice() && !c.unitToUnit && !c.conventional) {
            c.lastLcTg = lc.talkgroup();
            if (lc.talkgroup() != c.tg && !c.patch) {
                c.lcMismatch++;
                c.unconfirmed.clear();
                if (++c.mismatchRun >= 2) {
                    synchronized (e) {
                        if (!c.ended) {
                            e.log("TG " + c.tg + ": channel " + NativeEngine.mhz(c.freq) + " now carries TG " + lc.talkgroup() + " - leaving it");
                            c.ev.details = "CHANNEL NOW CARRIES TG " + lc.talkgroup();
                            if (e.speaker.speaker == c) e.audio.clear();
                            e.calls.endCall(c, "channel reassigned");
                        }
                    }
                }
                return;
            }
            c.mismatchRun = 0;
            if (lc.source() > 0 && lc.source() != c.src) { c.src = lc.source(); c.ev.from = String.valueOf(c.src); c.ev.radioId = c.src; e.listener.callUpdated(c.ev); e.logTalker(c); }
            if (lc.encrypted()) markEncrypted(c, "LC");
        }
        if (c.mismatchRun > 0) return;
        if (lc != null && c.conventional && lc.isGroupVoice()) {
            c.lastLcTg = lc.talkgroup();
            if (c.tg != lc.talkgroup() || c.src != lc.source()) {
                c.tg = lc.talkgroup(); c.src = lc.source(); c.t = e.tg(c.tg);
                if (c.t != null) c.priority = c.t.priority;
                c.ev.to = String.valueOf(c.tg); c.ev.talkgroup = c.tg; c.ev.from = String.valueOf(c.src); c.ev.radioId = c.src;
                e.listener.callUpdated(c.ev); e.logTalker(c);
            } else if (c.t == null) { c.t = e.tg(c.tg); if (c.t != null) c.priority = c.t.priority; }
            if (lc.encrypted()) markEncrypted(c, "LC");
            if (e.conventional) applyListenPolicy(c);
        }
        if (ess != null) { c.alg = ess.algorithm(); if (ess.encrypted()) { markEncrypted(c, ess.algorithmName()); if (e.conventional) c.eligible = false; } }
        if (c.hang) { c.hang = false; c.ptts++; }
        c.lastVoice = now;
        c.lastSeen = Math.max(c.lastSeen, now);
        c.voiceFrames++;
        ChannelDecoder vd = c.vd;
        if (vd != null) c.maxAbsOffset = Math.max(c.maxAbsOffset, Math.abs(vd.frequencyError()));
        if (e.conventional && c.conventional && !c.eligible) { c.unconfirmed.clear(); return; }
        if (c.encrypted || c.imbe == null) return;
        // a saved conventional system waits until the talkgroup is known, so mute / favorites / "only my list" apply
        if (e.conventional && c.conventional && c.lastLcTg < 0) {
            if (c.ldus < 4) { c.unconfirmed.add(imbe); if (c.unconfirmed.size() > 2) c.unconfirmed.poll(); return; }
            if (e.app.listenMode() != p25monitor.ListenMode.ALL) return;
        }
        if (!c.conventional && !c.unitToUnit && !c.patch && c.lastLcTg == -1 && c.ldus < 4) {
            c.unconfirmed.add(imbe);
            if (c.unconfirmed.size() > 2) c.unconfirmed.poll();
            return;
        }
        while (!c.unconfirmed.isEmpty()) playFrames(c, c.unconfirmed.poll());
        playFrames(c, imbe);
    }

    void playFrames(ActiveCall c, byte[][] imbe) {
        NativeEngine.FrameTap ft = e.frameTap;
        for (byte[] f : imbe) {
            if (ft != null) ft.frame(c.tg, c.freq, f);
            int[] st = c.imbe.stats(f);
            float[] a = c.imbe.decode(f);
            if (a == null) continue;
            c.imbeFrames++;
            int err = st == null ? -1 : st[0];
            if (st != null) { c.eHist[Math.min(16, Math.max(0, st[0]))]++; if (st[1] >= 0) c.e0Hist[Math.min(4, st[1])]++; }
            boolean bad = st != null && (st[0] >= (c.phase2 ? AMBE_NOISE : IMBE_NOISE) || st[3] != 0);
            if (err > 0) c.imbeErrSum += err;
            if (st != null && st[2] != 0 && !bad) c.imbeRepeat++;
            if (bad) { c.imbeBad++; a = new float[a.length]; }
            emit(c, a, a.length, new int[]{c.tg, c.lastLcTg, err, bad ? 1 : 0});
        }
    }

    /** One block of a call's audio: its own recording, its feeds, and the speaker if it is the call being heard. */
    void emit(ActiveCall c, float[] a, int n, int[] tapInfo) {
        if (e.speaker.play(c, a, n)) { var tap = e.playTap; if (tap != null && tapInfo != null) tap.accept(tapInfo); }
        Streaming st = e.streaming;
        if (st != null) {
            st.audio(c.id, c.t, c.tg, c.src, a, n);
            // Broadcastify Calls: this transmission's own audio, MP3-encoded as it arrives (tapInfo == null: filler for a lost frame)
            boolean voice = tapInfo != null, opened = false;
            synchronized (c) {
                if (c.tx == null && voice && !c.ended && !c.unitToUnit && !st.wantsTransmissions(c.t)) c.txSkip = true;
                else if (c.tx == null && voice && !c.ended && !c.unitToUnit && !c.txSkip) {
                    c.tx = new Transmission(c.id, c.tg, c.freq, c.txDone ? System.currentTimeMillis() : c.start, c.src, EngineLibs.mp3Available());
                    c.tx.first = !c.txDone;
                    c.tx.pinned = c.t;
                    opened = true;
                }
                if (c.tx != null) { c.tx.add(a, n, voice); if (c.src > 0) c.tx.src = c.src; }
            }
            if (opened) st.transmissionStarted(c.t);
        }
        // the call's recording; read by endCall (housekeeping or another decoder's thread): appended under the call's lock
        synchronized (c) {
            if (c.pcmLen + n <= 8000 * 600) {
                if (c.src > 0 && (c.sources.isEmpty() || c.sources.get(c.sources.size() - 1)[1] != c.src)) c.sources.add(new long[]{c.pcmLen, c.src});
                if (c.pcm.length < c.pcmLen + n) c.pcm = Arrays.copyOf(c.pcm, Math.max(c.pcm.length * 2, Math.max(8000 * 10, c.pcmLen + n)));
                System.arraycopy(a, 0, c.pcm, c.pcmLen, n);
                c.pcmLen += n;
            }
        }
    }

    /** Mute, favorites-only, and "only my list" — the same rules as a trunked grant. Encrypted calls are already flagged. */
    void applyListenPolicy(ActiveCall c) {
        if (c.encrypted) { c.eligible = false; c.unconfirmed.clear(); return; }
        String why = e.calls.skipReason(c);
        c.eligible = why == null;
        if (why != null) {
            c.unconfirmed.clear();
            String up = why.toUpperCase(java.util.Locale.ROOT);
            if (c.ev.details == null || !c.ev.details.equals(up)) { c.ev.details = up; e.listener.callUpdated(c.ev); }
        }
    }

    void markEncrypted(ActiveCall c, String how) {
        if (c.encrypted) return;
        c.encrypted = true;
        c.ev.event = c.ev.event.contains("Encrypted") ? c.ev.event : c.ev.event.replace("Group Call", "Encrypted Group Call");
        c.ev.details = "ENCRYPTED (" + how + ") - AUDIO MUTED, NOT DECRYPTED";
        e.listener.callUpdated(c.ev);
    }

    // ------------------------------------------------------------------ Phase 2 (TDMA, one time slot)
    /** A 4V or 2V burst on this call's slot: 4 or 2 AMBE+2 frames (20 ms each). */
    void onP2Voice(ActiveCall c, byte[][] frames) {
        if (c.ended) return;
        long now = System.currentTimeMillis();
        c.ldus++;
        if (c.mismatchRun > 0) return;
        if (c.hang) { c.hang = false; c.ptts++; }
        c.lastVoice = now;
        c.lastSeen = Math.max(c.lastSeen, now);
        c.p2Frames += frames.length;
        c.voiceFrames = (int) ((c.p2Frames + 8) / 9);       // in Phase 1 units (180 ms) for durations and checks
        ChannelDecoder vd = c.vd;
        if (vd != null) c.maxAbsOffset = Math.max(c.maxAbsOffset, Math.abs(vd.frequencyError()));
        if (c.encrypted || c.imbe == null) return;
        // the talkgroup is confirmed by the slot's MAC (PTT at the start, group voice channel user every superframe)
        if (!c.unitToUnit && !c.patch && c.lastLcTg == -1 && c.ldus < 8) {
            c.unconfirmed.add(frames);
            if (c.unconfirmed.size() > 6) c.unconfirmed.poll();
            return;
        }
        while (!c.unconfirmed.isEmpty()) playFrames(c, c.unconfirmed.poll());
        playFrames(c, frames);
    }

    /** A MAC PDU (FACCH or SACCH) on this call's slot that passed its CRC. */
    void onP2Mac(ActiveCall c, P2Mac m) {
        if (c.ended || !m.crcOk) return;
        long now = System.currentTimeMillis();
        if (m.group > 0 && !c.unitToUnit) {
            c.lastLcTg = m.group;
            if (m.group != c.tg && !c.patch) {
                c.lcMismatch++;
                c.unconfirmed.clear();
                if (++c.mismatchRun >= 2) {
                    synchronized (e) {
                        if (!c.ended) {
                            e.log("TG " + c.tg + ": " + NativeEngine.mhz(c.freq) + " slot " + (c.slot + 1) + " now carries TG " + m.group + " - leaving it");
                            c.ev.details = "SLOT NOW CARRIES TG " + m.group;
                            if (e.speaker.speaker == c) e.audio.clear();
                            e.calls.endCall(c, "slot reassigned");
                        }
                    }
                }
                return;
            }
            c.mismatchRun = 0;
        }
        if (m.source > 0 && m.source != c.src) { c.src = m.source; c.ev.from = String.valueOf(c.src); c.ev.radioId = c.src; e.listener.callUpdated(c.ev); e.logTalker(c); }
        if (m.encrypted()) {
            if (m.alg > 0) c.alg = m.alg;
            markEncrypted(c, m.alg > 0 && m.alg != 0x80 ? String.format("MAC algorithm %02X", m.alg) : "MAC service options");
        }
        switch (m.type) {
            case P2Mac.PTT -> { c.lastSeen = Math.max(c.lastSeen, now); if (c.hang) { c.hang = false; c.ptts++; } }
            case P2Mac.END_PTT -> onVoiceEnd(c);
            case P2Mac.IDLE, P2Mac.HANGTIME -> { if (c.p2Frames > 0 && !c.hang) onVoiceEnd(c); }
            default -> { }
        }
    }

    /** A voice frame was lost mid-transmission: 180 ms of silence in its place keeps the timing. */
    void onVoiceLost(ActiveCall c) {
        if (c.ended || c.hang || c.encrypted || c.imbe == null || c.imbeFrames == 0) return;
        if (System.currentTimeMillis() - c.lastVoice > 400) return;
        c.lostLdus++;
        emit(c, new float[1440], 1440, null);
    }

    /**
     * TDU / TDULC (Phase 1) or MAC_END_PTT / idle (Phase 2): one transmission ended. Its Broadcastify Calls upload starts
     * now; the call itself (display, speaker, tuner, recording, OpenMHz) continues through the talkgroup hang time.
     */
    void onVoiceEnd(ActiveCall c) {
        synchronized (e) {
            if (c.ended) return;
            c.tdus++;
            if (c.conventional) { e.calls.endCall(c, "end of transmission"); return; }
            e.calls.closeTx(c, "terminator");
            if (!c.hang) { c.hang = true; c.hangSince = System.currentTimeMillis(); if (e.speaker.speaker == c) e.audio.endOfTransmission(); }
        }
    }

    boolean conventionalAllowed() {
        if (e.conventional) return true;
        if (e.mode == NativeEngine.Mode.FILE) return true;
        return e.msgTotal == 0 && System.currentTimeMillis() - e.startedAt > 10000;
    }

    /** Conventional / single-channel voice heard on a control-channel decoder (recordings of a repeater, simplex). */
    void onConventional(long f, int nac, byte[][] imbe, P25Framer.LinkControl lc, P25Framer.EncryptionSync ess) {
        ActiveCall c;
        synchronized (e) {
            if (!conventionalAllowed()) {
                if (e.voiceOnCcLogged.add(f)) e.log("Voice frames heard on " + NativeEngine.mhz(f) + " MHz (listed as a control channel): it is in use as a voice channel right now. Calls are followed from the grants.");
                return;
            }
            c = e.callMap.get("C" + f);
            if (c == null) {
                c = new ActiveCall();
                c.conventional = true; c.freq = f; c.start = System.currentTimeMillis(); c.lastSeen = c.start; c.followStart = c.start;
                c.tg = lc != null && lc.isGroupVoice() ? lc.talkgroup() : 0;
                c.src = lc != null && lc.isGroupVoice() ? lc.source() : -1;
                c.t = e.tg(c.tg);
                c.imbe = EngineLibs.newImbe();
                c.followed = true; c.eligible = true; c.tuner = e.tunerFor(f);
                CallEvent ev = new CallEvent();
                ev.timestamp = e.ts.format(new Date()); ev.protocol = "APCO-25"; ev.event = "Group Call"; ev.to = String.valueOf(c.tg); ev.talkgroup = c.tg;
                ev.from = c.src > 0 ? String.valueOf(c.src) : ""; ev.radioId = c.src; ev.frequencyMHz = f / 1e6;
                ev.details = String.format("CONVENTIONAL / DIRECT VOICE NAC %03X", nac);
                ev.background = true;
                c.ev = ev;
                e.callMap.put("C" + f, c);
                e.listener.callStarted(ev);
            }
        }
        onVoice(c, imbe, lc, ess);
    }

    void onConventionalEnd(long f) { synchronized (e) { ActiveCall c = e.callMap.get("C" + f); if (c != null) e.calls.endCall(c, "end of transmission"); } }
}
