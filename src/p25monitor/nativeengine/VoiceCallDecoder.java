package p25monitor.nativeengine;

import p25monitor.App;
import p25monitor.CallEvent;
import p25monitor.RadioSystem;
import p25monitor.p25.MotorolaAlias;
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
    /** One Motorola alias train per frequency (Phase 2: per frequency and slot, a negative key). A conventional terminator ends the call before the next block arrives. */
    final Map<Long, MotorolaAlias> motorola = new HashMap<>();
    VoiceCallDecoder(NativeEngine e) { this.e = e; }

    MotorolaAlias motorola(long freq) {
        synchronized (motorola) { return motorola.computeIfAbsent(freq, k -> new MotorolaAlias()); }
    }

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
        } else if (c.edacs) {
            vd = new ChannelDecoder(t.decoderRate(), t.decoderCenter(c.freq), c.freq, new P25Framer.Listener() {}, afc);
            vd.widen(t.decoderRate(), NativeEngine.EDACS_CUTOFF_HZ);
            if (c.analogVoice) vd.analog = new p25monitor.analog.FmReceiver(0, new p25monitor.analog.FmReceiver.Listener() {
                public void start(double toneHz) {}
                public void audio(float[] pcm) { onType2Audio(call, pcm); }
                public void end() {}
            });
            vd.provoice = new p25monitor.edacs.ProVoiceReceiver(new p25monitor.edacs.ProVoiceReceiver.Listener() {
                public void voice(byte[][] frames, int errors) { if (!call.analogVoice) onProVoice(call, frames); }
                public void end() { onEdacsEnd(call); }
                public void encrypted() { onProVoiceEncrypted(call); }
            });
            vd.provoice.dottingAlwaysEnds = c.analogVoice;
        } else if (c.type2 && c.analogVoice) {
            vd = new ChannelDecoder(t.decoderRate(), t.decoderCenter(c.freq), c.freq, new P25Framer.Listener() {}, afc);
            vd.analog = new p25monitor.analog.FmReceiver(0, new p25monitor.analog.FmReceiver.Listener() {
                public void start(double toneHz) {}
                public void audio(float[] pcm) { onType2Audio(call, pcm); }
                public void end() {}
            });
        } else vd = new ChannelDecoder(t.decoderRate(), t.decoderCenter(c.freq), c.freq, new P25Framer.Listener() {
            public void ldu(int nac, int duid, byte[][] imbe, P25Framer.LinkControl lc, P25Framer.EncryptionSync ess) { onVoice(call, imbe, lc, ess); }
            public void tdulc(int nac, P25Framer.LinkControl lc) { onTrunkTdulc(call, lc); }
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
        acceptAlias(c, c.freq, lc);
        if (lc != null && !c.conventional) e.cc.voiceChannelUpdate(lc);
        if (lc != null && lc.isGroupVoice() && !c.unitToUnit && !c.conventional) {
            c.lastLcTg = lc.talkgroup();
            if (lc.talkgroup() != c.tg && !c.patch && !c.type2) {
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
            if (lc.source() > 0 && lc.source() != c.src) {
                if (c.src > 0) resetP25AliasParts(c);
                c.src = lc.source(); c.ev.from = String.valueOf(c.src); c.ev.radioId = c.src;
                publishP25Alias(c); e.listener.callUpdated(c.ev); e.logTalker(c);
            }
            if (lc.encrypted()) markEncrypted(c, "LC");
        }
        if (c.mismatchRun > 0) return;
        if (lc != null && c.conventional && lc.isGroupVoice()) {
            c.lastLcTg = lc.talkgroup();
            if (c.tg != lc.talkgroup() || c.src != lc.source()) {
                if (c.src > 0 && c.src != lc.source()) resetP25AliasParts(c);
                c.tg = lc.talkgroup(); c.src = lc.source(); c.t = e.tg(c.tg);
                if (c.t != null) c.priority = c.t.priority;
                c.ev.to = String.valueOf(c.tg); c.ev.talkgroup = c.tg; c.ev.from = String.valueOf(c.src); c.ev.radioId = c.src;
                publishP25Alias(c); e.listener.callUpdated(c.ev); e.logTalker(c);
            } else if (c.t == null) { c.t = e.tg(c.tg); if (c.t != null) c.priority = c.t.priority; }
            if (lc.encrypted()) markEncrypted(c, "LC");
            if (e.conventional) applyListenPolicy(c);
        }
        if (ess != null) { c.alg = ess.algorithm(); if (ess.encrypted()) { markEncrypted(c, ess.algorithmName()); if (e.conventional) c.eligible = false; } }
        if (c.hang) { c.hang = false; c.ptts++; }
        c.lastVoice = now;
        c.lastSeen = Math.max(c.lastSeen, now);
        c.voiceFrames++;
        c.voiceMs += imbe == null || imbe.length == 0 ? 180 : imbe.length * 20L;   // P25 LDU: 9 x 20 ms; DMR burst 3, NXDN frame 4
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

    /** Motorola talker alias (MFID 0x90) and the standard P25 user-alias pair. An on-air name replaces a typed one. */
    private void acceptAlias(ActiveCall c, long freq, P25Framer.LinkControl lc) {
        if (lc == null) return;
        MotorolaAlias.Decoded mot = motorola(freq).feed(lc);
        if (mot != null) publishOta(c, mot);
        if (c != null) learnP25Alias(c, lc);
    }

    /** Link control from a trunked voice channel's TDULC. The terminator callback still ends the transmission. */
    void onTrunkTdulc(ActiveCall c, P25Framer.LinkControl lc) {
        if (c != null) acceptAlias(c, c.freq, lc);
        if (c != null && !c.conventional) e.cc.voiceChannelUpdate(lc);
    }

    /** Link control from a conventional channel. The call may already be gone; the radio ID is in the payload. */
    void onConventionalLc(long f, P25Framer.LinkControl lc) {
        ActiveCall c;
        synchronized (e) { c = e.callMap.get("C" + f); }
        acceptAlias(c, f, lc);
    }

    private void publishOta(ActiveCall c, MotorolaAlias.Decoded d) {
        RadioSystem system = e.sys;
        if (system == null || d == null) return;
        int radio = d.radioId();
        String clean = p25monitor.RadioAliases.clean(d.alias());
        if (!p25monitor.RadioAliases.validId(radio) || clean.isEmpty()) return;
        String before = system.radios.alias(radio);
        if (!system.radios.setOverTheAir(radio, clean)) return;
        String after = system.radios.alias(radio);
        try { e.app.saveRadioAliases(system); }
        catch (Exception ex) { e.log("Could not save learned radio alias for " + radio + ": " + ex.getMessage()); }
        if (before.equals(after)) return;
        e.log("Learned P25 over-the-air radio alias " + after + " for radio " + radio);
        if (c == null || c.ended || c.ev == null || (c.src > 0 && c.src != radio)) return;
        if (c.src <= 0) { c.src = radio; c.ev.from = String.valueOf(radio); c.ev.radioId = radio; }
        c.loggedSrc = -1;
        e.listener.callUpdated(c.ev);
        e.logTalker(c);
    }

    /** Reassembles the standard P25 FDMA User Alias A/B pair for the current talker. */
    private void learnP25Alias(ActiveCall c, P25Framer.LinkControl lc) {
        if (c.phase2) return;
        int op = lc.opcode();
        if (op < 0x32 || op > 0x35) return;
        if (lc.mfid != 0xA4) {
            e.log(String.format(Locale.ROOT, "P25 alias opcode 0x%02X seen with MFID 0x%02X; this format is not supported", op, lc.mfid));
            return;
        }
        String part = lc.userAliasPart();
        if (part.isEmpty()) {
            e.log(String.format(Locale.ROOT, "P25 alias part 0x%02X received but its ASCII payload was invalid", op));
            return;
        }
        e.log(String.format(Locale.ROOT, "P25 alias part 0x%02X received%s", op, c.src > 0 ? " for radio " + c.src : " before a talker radio ID was available"));
        String alias = null;
        switch (op) {
            case 0x32 -> { c.p25AliasOddA = part; c.p25AliasOddB = null; }
            case 0x33 -> { c.p25AliasOddB = part; if (c.p25AliasOddA != null) alias = c.p25AliasOddA + part; }
            case 0x34 -> { c.p25AliasEvenA = part; c.p25AliasEvenB = null; }
            case 0x35 -> { c.p25AliasEvenB = part; if (c.p25AliasEvenA != null) alias = c.p25AliasEvenA + part; }
            default -> { return; }
        }
        if (alias == null) return;
        c.p25AliasComplete = alias;
        publishP25Alias(c);
    }

    private void publishP25Alias(ActiveCall c) {
        if (c.src <= 0 || c.p25AliasComplete == null) return;
        RadioSystem system = e.sys;
        if (system == null) return;
        String clean = p25monitor.RadioAliases.clean(c.p25AliasComplete);
        if (clean.isEmpty()) return;
        c.p25AliasComplete = null;
        String before = system.radios.alias(c.src);
        if (!system.radios.setOverTheAir(c.src, clean)) return;
        try { e.app.saveRadioAliases(system); }
        catch (Exception ex) { e.log("Could not save learned radio alias for " + c.src + ": " + ex.getMessage()); }
        String after = system.radios.alias(c.src);
        if (!after.equals(before)) {
            c.loggedSrc = -1;
            e.listener.callUpdated(c.ev);
            e.log("Learned P25 over-the-air radio alias " + after + " for radio " + c.src);
            e.logTalker(c);
        }
    }

    private static void resetP25AliasParts(ActiveCall c) {
        c.p25AliasOddA = c.p25AliasOddB = c.p25AliasEvenA = c.p25AliasEvenB = null;
        c.p25AliasComplete = null;
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
        c.voiceMs += frames.length * 20L;
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
        if (!m.crcOk) return;
        // Motorola talker alias: one train per frequency and time slot (the radio ID is in the payload)
        MotorolaAlias.Decoded mot = motorola(-(c.freq * 2 + Math.max(0, Math.min(1, c.slot)) + 1)).feed(m);
        if (mot != null) publishOta(c, mot);
        if (c.ended) return;
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
        // FFFFFF is "no unit" (MAC_END_PTT carries it), not a talker
        if (m.source > 0 && m.source != 0xFFFFFF && m.source != c.src) { c.src = m.source; c.ev.from = String.valueOf(c.src); c.ev.radioId = c.src; e.listener.callUpdated(c.ev); e.logTalker(c); }
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
        if (e.lockedOut(f)) return;   // Monitor page, Lockout
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
        if (e.conventional) { e.lastAnalogHeard = f; e.lastAnalogAt = System.currentTimeMillis(); }   // what Hold / Lockout take
        onVoice(c, imbe, lc, ess);
    }

    void onConventionalEnd(long f) { synchronized (e) { ActiveCall c = e.callMap.get("C" + f); if (c != null) e.calls.endCall(c, "end of transmission"); } }

    /** A DMR group or private call. Encrypted calls are not started. Audio uses the Phase 2 AMBE+2 codec when it is installed. */
    void onDmr(long f, int slot, int color, int tg, int src, boolean group, byte[][] ambe) {
        ActiveCall c;
        synchronized (e) {
            c = e.callMap.get("D" + f + "/" + slot);
            if (c == null) {
                c = new ActiveCall();
                c.conventional = true; c.phase2 = true; c.freq = f; c.slot = slot - 1;
                c.start = System.currentTimeMillis(); c.lastSeen = c.start; c.followStart = c.start; c.lastVoice = c.start;
                c.tg = tg; c.src = src; c.t = e.tg(tg); c.followed = true; c.eligible = true; c.tuner = e.tunerFor(f);
                c.imbe = EngineLibs.newAmbe();
                if (c.imbe == null && e.ambeMissingLogged.compareAndSet(false, true))
                    e.log("DMR call TG " + tg + ": the AMBE+2 voice codec is not available (it needs JMBE 2.x) - the call is followed but silent. DMR is untested.");
                CallEvent ev = new CallEvent();
                ev.timestamp = e.ts.format(new Date()); ev.protocol = "DMR"; ev.event = group ? "Group Call" : "Private Call";
                ev.to = String.valueOf(tg); ev.talkgroup = tg; ev.from = src > 0 ? String.valueOf(src) : ""; ev.radioId = src;
                ev.frequencyMHz = f / 1e6; ev.timeslot = "TS" + slot + (color >= 0 ? "  CC " + color : "");
                ev.details = "DMR (untested) " + ev.timeslot;
                c.ev = ev;
                e.callMap.put("D" + f + "/" + slot, c);
                e.listener.callStarted(ev);
                e.log("DMR (untested) TG " + tg + " slot " + slot + " colour code " + color + " on " + NativeEngine.mhz(f) + " MHz");
            }
        }
        if (ambe != null && ambe.length > 0) onVoice(c, codecFrames(ambe), null, null);
    }

    /** Each DMR frame is rewritten as the 9-byte AMBE word JMBE's getAudio(byte[]) decodes. */
    private static byte[][] codecFrames(byte[][] ambe) {
        byte[][] out = new byte[ambe.length][];
        for (int i = 0; i < ambe.length; i++) out[i] = p25monitor.dmr.DmrAmbe.forCodec(ambe[i]);
        return out;
    }

    void onDmrEncrypted(long f, int slot, int color) {
        e.log("DMR encrypted call on " + NativeEngine.mhz(f) + " MHz slot " + slot + " colour code " + color + " - skipped");
        ActiveCall c;
        synchronized (e) { c = e.callMap.get("D" + f + "/" + slot); }
        if (c != null && !c.ended) { if (c.ev.event.equals("Private Call")) c.ev.event = "Encrypted Private Call"; markEncrypted(c, "DMR"); }
    }

    /** Emergency bit in a DMR call's link control (or its grant): the call is shown as an emergency call. */
    void onDmrEmergency(long f, int slot) {
        ActiveCall c;
        synchronized (e) { c = e.callMap.get("D" + f + "/" + slot); }
        if (c == null || c.ended || c.emergency) return;
        c.emergency = true;
        if (!c.ev.event.startsWith("Emergency")) c.ev.event = "Emergency " + c.ev.event;
        e.listener.callUpdated(c.ev);
    }

    /**
     * End of a DMR transmission on one slot. A call on the other slot of the frequency keeps going while it has voice;
     * one with no voice for 1.5 s (a grant whose slot label never matched) is ended with it.
     */
    void onDmrEnd(long f, int slot) {
        long now = System.currentTimeMillis();
        synchronized (e) {
            java.util.List<ActiveCall> end = new java.util.ArrayList<>();
            for (var en : e.callMap.entrySet()) {
                if (!en.getKey().startsWith("D" + f + "/")) continue;
                boolean same = en.getKey().equals("D" + f + "/" + slot);
                if (same || slot != 1 && slot != 2 || now - en.getValue().lastVoice > 1500) end.add(en.getValue());
            }
            for (ActiveCall c : end) e.calls.endCall(c, "end of transmission");
        }
    }

    void onDmrEnd(long f) { onDmrEnd(f, 0); }

    private String lastDmrGrant = ""; private long lastDmrGrantLog;
    void onDmrGrant(p25monitor.dmr.DmrTrunk.Grant g) {
        if (g == null) return;
        long now = System.currentTimeMillis();
        Long ended = e.recentlyEnded.get("G" + g.talkgroup);
        if (ended != null && now - ended < 4000) return;   // repeat of a grant for a call that just ended
        String sig = g.talkgroup + "/" + g.hz + "/" + g.slot;
        if (!sig.equals(lastDmrGrant) || now - lastDmrGrantLog > 10_000) {
            lastDmrGrant = sig; lastDmrGrantLog = now;
            e.log("DMR " + (g.group ? "grant TG " : "private call grant to ") + g.talkgroup + " -> " + NativeEngine.mhz(g.hz) + " MHz slot " + g.slot + (g.emergency ? " EMERGENCY" : ""));
        }
        onDmr(g.hz, g.slot, -1, g.talkgroup, g.source, g.group, new byte[0][]);
        if (g.emergency) onDmrEmergency(g.hz, g.slot);
    }

    // ------------------------------------------------------------------ NXDN (untested on air)
    /**
     * An NXDN voice call header (FACCH1 or SACCH VCALL) or a Type-C voice channel assignment, on {@code f}. A call that
     * already runs on the channel with a different talkgroup or source ends first. Encrypted calls are followed but muted.
     */
    void onNxdnCall(long f, p25monitor.nxdn.NxdnMessage m) {
        int tg = m.destination(), src = m.source();
        boolean group = m.group();
        ActiveCall c;
        synchronized (e) {
            c = e.callMap.get("N" + f);
            if (c != null && !c.ended && c.tg > 0 && (c.tg != tg || src > 0 && c.src > 0 && c.src != src)) { e.calls.endCall(c, "new transmission"); c = null; }
            if (c == null || c.ended) c = newNxdnCall(f, tg, src, group);
            else if (c.tg <= 0) {   // started from voice before its header: fill in the IDs now
                c.tg = tg; c.src = src; c.t = e.tg(tg);
                c.ev.to = String.valueOf(tg); c.ev.talkgroup = tg; c.ev.from = src > 0 ? String.valueOf(src) : ""; c.ev.radioId = src;
                c.ev.event = group ? "Group Call" : "Private Call";
                e.listener.callUpdated(c.ev);
            }
        }
        if (m.emergency() && !c.emergency) {
            c.emergency = true;
            if (!c.ev.event.startsWith("Emergency")) c.ev.event = "Emergency " + c.ev.event;
            e.listener.callUpdated(c.ev);
        }
        if (m.isCall() && m.cipher() != 0 && !c.encrypted) markEncrypted(c, "NXDN");
    }

    private ActiveCall newNxdnCall(long f, int tg, int src, boolean group) {
        ActiveCall c = new ActiveCall();
        c.conventional = true; c.phase2 = true; c.freq = f; c.slot = -1;
        c.start = System.currentTimeMillis(); c.lastSeen = c.start; c.followStart = c.start; c.lastVoice = c.start;
        c.tg = tg; c.src = src; c.t = e.tg(tg); c.followed = true; c.eligible = true; c.tuner = e.tunerFor(f);
        c.imbe = EngineLibs.newAmbe();
        if (c.imbe == null && e.ambeMissingLogged.compareAndSet(false, true))
            e.log("NXDN call TG " + tg + ": the AMBE+2 voice codec is not available (it needs JMBE 2.x) - the call is followed but silent. NXDN is untested.");
        CallEvent ev = new CallEvent();
        ev.timestamp = e.ts.format(new Date()); ev.protocol = "NXDN"; ev.event = group ? "Group Call" : "Private Call";
        ev.to = tg > 0 ? String.valueOf(tg) : ""; ev.talkgroup = tg; ev.from = src > 0 ? String.valueOf(src) : ""; ev.radioId = src;
        ev.frequencyMHz = f / 1e6;
        ev.details = "NXDN (untested)";
        c.ev = ev;
        e.callMap.put("N" + f, c);
        e.listener.callStarted(ev);
        e.log("NXDN (untested) " + (group ? "TG " : "private call to ") + tg + " from " + src + " on " + NativeEngine.mhz(f) + " MHz");
        return c;
    }

    /** NXDN voice: 72-bit AMBE+2 frames, the same air format as DMR, so the same Golay repair feeds JMBE. */
    void onNxdnVoice(long f, byte[][] ambe) {
        ActiveCall c;
        synchronized (e) {
            c = e.callMap.get("N" + f);
            if (c == null || c.ended) c = newNxdnCall(f, 0, -1, true);   // joined mid-call: the SACCH fills in the IDs
        }
        if (ambe != null && ambe.length > 0) onVoice(c, codecFrames(ambe), null, null);
    }

    /** NXDN transmission release or disconnect, or lost sync. */
    void onNxdnEnd(long f) {
        synchronized (e) { ActiveCall c = e.callMap.get("N" + f); if (c != null && !c.ended) e.calls.endCall(c, "end of transmission"); }
    }

    // ------------------------------------------------------------------ Yaesu System Fusion (untested on air)
    /**
     * A Fusion transmission started on {@code f}, or its callsigns or mode became known. The talkgroup is the DG-ID
     * (0 on most repeaters), so it can be named, muted or listed like a talkgroup; the caller is the callsign.
     */
    void onYsfCall(long f, p25monitor.ysf.YsfDecoder.Call y) {
        if (e.lockedOut(f)) return;
        synchronized (e) {
            ActiveCall c = e.callMap.get("Y" + f);
            if (c == null || c.ended) { newYsfCall(f, y); return; }
            boolean imbe = "VW".equals(y.mode);
            if (imbe != !c.phase2) { c.phase2 = !imbe; c.imbe = imbe ? EngineLibs.newImbe() : EngineLibs.newAmbe(); }
            fillYsf(c, y);
            e.listener.callUpdated(c.ev);
        }
    }

    private ActiveCall newYsfCall(long f, p25monitor.ysf.YsfDecoder.Call y) {
        boolean imbe = "VW".equals(y.mode);
        ActiveCall c = new ActiveCall();
        c.conventional = true; c.phase2 = !imbe; c.freq = f; c.slot = -1;
        c.start = System.currentTimeMillis(); c.lastSeen = c.start; c.followStart = c.start; c.lastVoice = c.start;
        c.followed = true; c.eligible = true; c.tuner = e.tunerFor(f);
        c.imbe = imbe ? EngineLibs.newImbe() : EngineLibs.newAmbe();
        if (c.imbe == null && e.ambeMissingLogged.compareAndSet(false, true))
            e.log("Fusion call: the " + (imbe ? "IMBE" : "AMBE+2") + " voice codec is not available (it needs JMBE 2.x) - the call is followed but silent.");
        CallEvent ev = new CallEvent();
        ev.timestamp = e.ts.format(new Date()); ev.protocol = "Fusion"; ev.event = "Group Call";
        ev.radioId = -1; ev.frequencyMHz = f / 1e6;
        c.ev = ev;
        fillYsf(c, y);
        e.callMap.put("Y" + f, c);
        e.listener.callStarted(ev);
        return c;
    }

    /** Callsigns, DG-ID and mode onto the call's display fields. */
    private void fillYsf(ActiveCall c, p25monitor.ysf.YsfDecoder.Call y) {
        c.tg = y.dgid; c.lastLcTg = y.dgid; c.src = -1; c.t = e.tg(y.dgid);
        if (c.t != null) c.priority = c.t.priority;
        CallEvent ev = c.ev;
        ev.talkgroup = y.dgid;
        ev.to = !y.dest.isEmpty() && !y.dest.startsWith("*") ? y.dest : "ALL";
        ev.from = y.source;
        ev.details = "FUSION " + y.mode + "  DG-ID " + y.dgid + (y.uplink.isEmpty() ? "" : "  VIA " + y.uplink) + " (untested)";
    }

    /** Fusion voice: AMBE+2 frames in the DMR layout (DN, V/D mode 1) or IMBE frames in the P25 layout (VW). */
    void onYsfVoice(long f, byte[][] frames, boolean imbe) {
        if (e.lockedOut(f) || frames == null || frames.length == 0) return;
        ActiveCall c;
        synchronized (e) { c = e.callMap.get("Y" + f); }
        if (c == null || c.ended) return;
        long now = System.currentTimeMillis();
        if (c.hang) { c.hang = false; c.ptts++; }
        c.lastVoice = now; c.lastSeen = Math.max(c.lastSeen, now);
        c.voiceFrames++; c.ldus++;
        c.voiceMs += frames.length * 20L;
        if (c.imbe == null || imbe == c.phase2) return;   // no codec, or frames of the other kind
        playFrames(c, imbe ? frames : codecFrames(frames));
    }

    /** A Fusion terminator, or a second without frames. */
    void onYsfEnd(long f) {
        synchronized (e) { ActiveCall c = e.callMap.get("Y" + f); if (c != null && !c.ended) e.calls.endCall(c, "end of transmission"); }
    }

    // ------------------------------------------------------------------ analog FM (untested on air)
    /** An analog transmission opened the squelch (and matched the channel's CTCSS tone, when it has one). */
    void onAnalogStart(long f, double tone) {
        if (e.lockedOut(f)) return;
        synchronized (e) {
            ActiveCall old = e.callMap.get("A" + f);
            if (old != null && !old.ended) return;
            ActiveCall c = new ActiveCall();
            c.conventional = true; c.freq = f; c.slot = -1;
            c.start = System.currentTimeMillis(); c.lastSeen = c.start; c.followStart = c.start; c.lastVoice = c.start;
            c.tg = 0; c.src = -1; c.followed = true; c.eligible = true; c.tuner = e.tunerFor(f);
            CallEvent ev = new CallEvent();
            RadioSystem.Channel ch = e.sys == null ? null : e.sys.channelAt(f);
            String name = ch != null && ch.name != null && !ch.name.isBlank() ? ch.name : NativeEngine.mhz(f) + " MHz";
            ev.timestamp = e.ts.format(new Date()); ev.protocol = "Analog FM"; ev.event = "Analog Call";
            ev.to = name; ev.talkgroup = 0; ev.from = ""; ev.radioId = -1; ev.frequencyMHz = f / 1e6;
            ev.details = "ANALOG FM" + toneText(tone);
            c.ev = ev;
            e.callMap.put("A" + f, c);
            e.listener.callStarted(ev);
            e.log("Analog (untested) " + name + toneText(tone).toLowerCase(Locale.ROOT));
        }
    }

    static String toneText(double tone) { return tone > 0 ? String.format(Locale.ROOT, "  PL %.1f", tone) : ""; }

    /** The CTCSS tone of a running carrier-squelch transmission was identified: shown on the call. */
    void onAnalogTone(long f, double tone) {
        ActiveCall c;
        synchronized (e) { c = e.callMap.get("A" + f); }
        if (c == null || c.ended) return;
        c.ev.details = "ANALOG FM" + toneText(tone);
        e.listener.callUpdated(c.ev);
    }

    /** Four 20 ms ProVoice voice frames (converted to P25 IMBE frames) on an EDACS voice channel. */
    void onProVoice(ActiveCall c, byte[][] frames) {
        if (c.ended) return;
        long now = System.currentTimeMillis();
        ChannelDecoder vd = c.vd;
        if (vd != null) vd.lastFrameAt = now;
        if (c.hang) { c.hang = false; c.ptts++; }
        c.lastVoice = now; c.lastSeen = Math.max(c.lastSeen, now);
        c.voiceFrames++; c.ldus++;
        c.voiceMs += frames.length * 20L;
        if (c.emergencyPending && !c.emergency) e.calls.confirmEmergency(c);
        if (c.encrypted || c.imbe == null) return;
        playFrames(c, frames);
    }

    /** ProVoice frames carrying an IV: the call is encrypted, so its audio is muted like an encrypted P25 call. */
    void onProVoiceEncrypted(ActiveCall c) {
        synchronized (e) {
            if (c.ended || c.encrypted) return;
            markEncrypted(c, "ProVoice");
            if (e.speaker.speaker == c) e.audio.clear();
        }
        e.log("TG " + c.tg + ": ProVoice is encrypted - audio muted");
    }

    /**
     * Dotting (or lost ProVoice sync) on an EDACS voice channel: the transmission is over. Dotting in the first second
     * of an analog call, before any audio, is the channel's set-up data, not the end.
     */
    void onEdacsEnd(ActiveCall c) {
        synchronized (e) {
            if (c.ended) return;
            if (c.analogVoice && c.voiceFrames == 0 && System.currentTimeMillis() - c.followStart < 1000) return;
            e.calls.endCall(c, "end of transmission");
        }
    }

    /** 20 ms of analog audio at 8 kHz on a Motorola Type II (or EDACS) voice channel. */
    void onType2Audio(ActiveCall c, float[] pcm) {
        if (c.ended) return;
        long now = System.currentTimeMillis();
        ChannelDecoder vd = c.vd;
        if (vd != null) vd.lastFrameAt = now;
        if (c.hang) { c.hang = false; c.ptts++; }
        c.lastVoice = now; c.lastSeen = Math.max(c.lastSeen, now);
        c.voiceFrames++; c.imbeFrames++;
        c.voiceMs += pcm.length / 8;   // 8 kHz audio
        if (c.emergencyPending && !c.emergency) e.calls.confirmEmergency(c);
        if (c.encrypted) return;
        emit(c, pcm, pcm.length, new int[]{c.tg, c.lastLcTg, 0, 0});
    }

    /** 20 ms of analog audio at 8 kHz. */
    void onAnalogAudio(long f, float[] pcm) {
        if (e.lockedOut(f)) return;
        ActiveCall c;
        synchronized (e) { c = e.callMap.get("A" + f); }
        if (c == null || c.ended) { onAnalogStart(f, 0); synchronized (e) { c = e.callMap.get("A" + f); } }
        if (c == null || c.ended) return;
        long now = System.currentTimeMillis();
        c.lastVoice = now; c.lastSeen = Math.max(c.lastSeen, now);
        c.voiceFrames++; c.imbeFrames++;
        c.voiceMs += pcm.length / 8;   // 8 kHz audio
        e.lastAnalogHeard = f; e.lastAnalogAt = now;
        emit(c, pcm, pcm.length, new int[]{c.tg, c.lastLcTg, 0, 0});
    }

    void onAnalogEnd(long f) {
        synchronized (e) { ActiveCall c = e.callMap.get("A" + f); if (c != null && !c.ended) e.calls.endCall(c, "end of transmission"); }
    }
}
