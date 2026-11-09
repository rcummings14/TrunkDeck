package p25monitor.nativeengine;

import p25monitor.sdr.AirspyR2;
import p25monitor.sdr.AirspyStream;
import p25monitor.sdr.RtlSdr;
import java.util.*;

/** Engine page and Monitor view status: tuner windows, every call, missed calls, PPM / gain advice. */
final class EngineDiagnostics {
    final NativeEngine e;
    EngineDiagnostics(NativeEngine e) { this.e = e; }

    static String mhz(long f) { return NativeEngine.mhz(f); }

    String[][] callRows(ActiveCall c, ChannelDecoder vd, String status) {
        if (c == null) return null;
        long now = System.currentTimeMillis();
        List<String[]> r = new ArrayList<>();
        String alias = c.alias().isEmpty() ? "" : "  " + c.alias();
        r.add(new String[]{"Talkgroup", (c.conventional ? "conventional " : "") + c.tg + alias + (c.priority < 100 ? "  (priority " + c.priority + ")" : "")});
        r.add(new String[]{"Status", status});
        r.add(new String[]{"Channel", mhz(c.freq) + " MHz  " + (c.phase2 ? "Phase 2 TDMA slot " + (c.slot + 1) : "Phase 1 FDMA") + (c.conventional ? "" : "  ch " + (c.channel >> 12) + "-" + (c.channel & 0xfff)) + (c.tuner != null ? "  on tuner T" + c.tuner.id : "")});
        if (vd != null) {
            r.add(new String[]{"Frequency offset", String.format(Locale.ROOT, "%+.0f Hz measured (filter correction %+.0f Hz, FLL %d / AFC %d steps)", vd.frequencyError(), vd.afcHz, vd.fllSteps, vd.afcSteps)});
            r.add(new String[]{"Demodulator", vd.demodSummary() + (vd.demodSwitches > 0 ? "  (" + vd.demodSwitches + " switches)" : "")});
            r.add(new String[]{"Signal", String.format(Locale.ROOT, "%s while receiving, %.1f dB now (dB of the ADC full scale), %s", Double.isNaN(vd.syncPower) ? "-" : String.format(Locale.ROOT, "%.1f dB", vd.syncPower), vd.power, vd.locked(now) ? "frame sync OK" : "no frame sync")});
            if (vd.tdma()) {
                var p = vd.p2;
                r.add(new String[]{"TDMA frames", String.format(Locale.ROOT, "channel: %d fragments in sync (%d sync losses), ISCH %d ok / %d bad, %d MAC PDUs ok / %d bad; this slot: %d voice bursts (%d AMBE+2 frames), %d transmissions, %d end-of-transmission (MAC_END_PTT / idle)",
                        p.fragments, Math.max(0, p.lockCount - 1), p.ischOk, p.ischBad, p.macOk, p.macBad, c.ldus, c.p2Frames, c.ptts, c.tdus)});
                r.add(new String[]{"Scrambling", vd.scramblingSummary()});
            } else
            r.add(new String[]{"Frames", String.format(Locale.ROOT, "%d syncs, %d NID ok / %d bad, %d voice LDUs (%d lost, replaced by silence), %d transmissions, %d end-of-transmission (TDU)", vd.syncs() - c.syncBase, vd.nidOk() - c.nidOkBase, vd.nidBad() - c.nidBadBase, c.ldus, c.lostLdus, c.ptts, c.tdus)});
            r.add(new String[]{"Stream", TunerAdvice.streamSummary(vd) + (vd.phaseJumps >= 3 && vd.phaseJumps * 480000L > vd.jumpSamples ? "  - SAMPLES ARE BEING LOST (USB/CPU) or the signal is extremely weak: voice frames after each break are garbage" : "  (should be 0 or close to it)")});
        } else {
            r.add(new String[]{"Frequency offset", String.format(Locale.ROOT, "max %.0f Hz during the call", c.maxAbsOffset)});
            r.add(new String[]{"Frames", String.format(Locale.ROOT, "%d voice LDUs, %d transmissions", c.ldus, c.ptts)});
        }
        String codec = c.phase2 ? "AMBE+2" : "IMBE";
        if (c.imbeFrames > 0) r.add(new String[]{codec + " error histogram", TunerAdvice.histSummary(c)});
        if (c.imbeFrames >= 50 && c.imbeBad * 5 > c.imbeFrames)
            r.add(new String[]{"Recommendation", "Voice is still damaged: tick Record voice-channel I/Q, let it save 2-3 calls, and send one or two of the .wav files (I/Q folder) together with this text."});
        r.add(new String[]{codec + " voice frames", c.imbeFrames == 0 ? (c.imbe == null ? (c.phase2 ? "AMBE+2 codec not available (needs JMBE 2.x)" : "voice codec not installed") : c.encrypted ? "encrypted - not decoded" : "none yet")
                : String.format(Locale.ROOT, "%d decoded, %.2f corrected bits/frame (clean speech 0-1), %.1f%% repeated, %.1f%% muted as noise", c.imbeFrames, (double) c.imbeErrSum / c.imbeFrames, 100.0 * c.imbeRepeat / c.imbeFrames, 100.0 * c.imbeBad / c.imbeFrames)});
        r.add(new String[]{"Talkgroup check", c.lcMismatch == 0 ? (c.lastLcTg == -1 ? "not confirmed yet" : c.phase2 ? "OK (MAC PDUs of the slot match)" : "OK (link control matches)") : c.lcMismatch + (c.phase2 ? " MAC PDUs" : " voice frames") + " for other talkgroups rejected"});
        r.add(new String[]{"Audio", String.format(Locale.ROOT, "%d ms buffered (jitter buffer %d ms), %d underruns, %d frames dropped (session)", e.audio.queuedMs(), e.audio.prebufferMs, e.audio.underruns, e.audio.droppedFrames)});
        String ga = gainAdvice();
        if (!ga.isEmpty()) r.add(new String[]{"Gain", ga});
        if (c.iqFile != null) r.add(new String[]{"I/Q recording", c.iqFile.toString()});
        return r.toArray(new String[0][]);
    }

    String[][] voiceDiagnostics() {
        synchronized (e) {
            List<String[]> r = new ArrayList<>(poolRows());
            ActiveCall c = e.speaker.speaker;
            if (c == null) for (ActiveCall a : e.callMap.values()) if (a.vd != null && (c == null || a.lastVoice > c.lastVoice)) c = a;
            if (c != null) {
                r.add(new String[]{"\u2014 Call details", "TG " + c.tg + (c == e.speaker.speaker ? " (on the speaker)" : "")});
                r.addAll(Arrays.asList(callRows(c, c.vd, c.hang ? "HANG TIME (waiting for a reply on this talkgroup)" : c.encrypted ? "ENCRYPTED - muted" : "VOICE")));
            } else if (e.lastDiag != null) { r.add(new String[]{"\u2014 Last call", ""}); r.addAll(Arrays.asList(e.lastDiag)); }
            return r.toArray(new String[0][]);
        }
    }

    List<String[]> poolRows() {
        List<String[]> r = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Tuner t : e.tuners) {
            int ch = 0; for (long f : e.usage.keySet()) if (t.covers(f)) ch++;
            r.add(new String[]{"Tuner T" + t.id + " (" + (t == e.ccTuner ? "control" : "voice, roaming") + ")",
                    t.label + ": " + t.window() + ", span " + String.format(Locale.ROOT, "%.2f MHz", 2 * t.halfSpan() / 1e6) + ", " + e.calls.callsOn(t) + " active call(s), covers " + ch + " of " + e.usage.size() + " voice channels seen, " + t.retunes + " retunes"});
            if (t.wideband()) r.add(new String[]{"Wideband T" + t.id, widebandLine(t)});
            String pl = processingLine(t);
            if (!pl.isEmpty()) r.add(new String[]{"Processing T" + t.id, pl});
        }
        List<ActiveCall> cs = new ArrayList<>(e.callMap.values());
        cs.sort(Comparator.comparingLong(c -> c.start));
        for (ActiveCall c : cs) if (!c.ended && c.eligible) r.add(new String[]{"Call TG " + c.tg, callLine(c, now)});
        r.add(new String[]{"Concurrent calls", e.calls.decoding() + " decoding now, peak " + e.peakConcurrent + " (limit " + e.calls.maxCalls() + "), " + e.follows + " calls decoded this session (" + e.phase2Calls + " Phase 2 slots)"});
        long tot = e.missed.values().stream().mapToLong(Long::longValue).sum();
        r.add(new String[]{"Missed calls", tot == 0 ? "none" : tot + " (" + e.missed.entrySet().stream().map(x -> x.getValue() + " " + x.getKey()).reduce((a, b) -> a + ", " + b).orElse("") + ")" + (e.missedRecent.isEmpty() ? "" : "  | last: " + e.missedRecent.peekLast())});
        List<String> outside = new ArrayList<>();
        for (var x : new TreeMap<>(e.usage).entrySet()) if (e.calls.covering(x.getKey()) == null) outside.add(mhz(x.getKey()));
        if (!outside.isEmpty()) r.add(new String[]{"Voice channels outside all windows now", String.join(", ", outside) + " MHz"});
        NativeEngine.SpeakerMode m = e.speaker.mode;
        r.add(new String[]{"Speaker", m == NativeEngine.SpeakerMode.PRIORITY ? "one call at a time by priority" : m == NativeEngine.SpeakerMode.MIX ? "mixing all calls"
                : m == NativeEngine.SpeakerMode.QUEUE ? queueLine() : "holding TG " + e.speaker.holdTg});
        return r;
    }

    String queueLine() {
        long[] q = e.speaker.queueStatus();
        if (q == null) return "queue";
        return String.format(Locale.ROOT, "queue: every call one after another; %d waiting, %+.0f s behind live, max delay %d s; %d call(s) skipped by the limit (memory only, nothing written to disk)",
                q[0], q[1] / 1000.0, e.speaker.queueMaxDelay() / 1000, q[2]);
    }

    String callLine(ActiveCall c, long now) {
        String st = c.waiting ? "WAITING FOR TUNER" : c.encrypted ? "ENCRYPTED" : c.hang ? "HANG" : c.vd == null ? "-" : "VOICE";
        ChannelDecoder v = c.vd;
        return String.format(Locale.ROOT, "%s%s  %s MHz  %s  %s  %ds  %s  %s%s", c.tg, c.alias().isEmpty() ? "" : " " + c.alias(), mhz(c.freq), c.tuner == null ? "no tuner" : "T" + c.tuner.id, st,
                (now - c.start) / 1000, v == null || Double.isNaN(v.syncPower) ? "-" : String.format(Locale.ROOT, "%.1f dB", v.syncPower),
                c.imbeFrames == 0 ? "no voice yet" : String.format(Locale.ROOT, "%.2f bits/frame, %.0f%% muted", (double) c.imbeErrSum / c.imbeFrames, 100.0 * c.imbeBad / c.imbeFrames),
                c == e.speaker.speaker ? "  SPEAKER" : "");
    }

    List<CallView> activeCalls() {
        long now = System.currentTimeMillis();
        List<CallView> out = new ArrayList<>();
        ActiveCall spk = e.speaker.speaker;
        for (ActiveCall c : e.callMap.values()) {
            if (c.ended || !c.eligible) continue;
            CallView v = new CallView();
            v.id = c.id; v.tg = c.tg; v.src = Math.max(0, c.src); v.alias = c.alias(); v.mhz = c.freq / 1e6; v.durationMs = now - c.start; v.tuner = c.tuner == null ? "-" : "T" + c.tuner.id;
            v.onSpeaker = c == spk; v.audible = e.speaker.audible(c);
            v.encrypted = c.encrypted; v.emergency = c.emergency; v.slot = c.phase2 && c.slot >= 0 ? c.slot + 1 : 0;
            boolean live = c.vd != null || (c.conventional && c.voiceFrames > 0 && now - c.lastVoice < 1500);
            v.recording = live && ((c.t != null && c.t.record) || e.calls.recordAll());
            ChannelDecoder d = c.vd;
            if (d != null && !Double.isNaN(d.syncPower)) v.levelDb = d.syncPower;
            if ((d == null || Double.isNaN(v.levelDb)) && c.conventional) {
                for (ChannelDecoder p : e.decodersOn(c.tuner)) if (p.freqHz == c.freq && !Double.isNaN(p.signalDb)) v.levelDb = p.signalDb;
            }
            if (c.imbeFrames > 0) v.bitsPerFrame = (double) c.imbeErrSum / c.imbeFrames;
            v.state = c.waiting ? "WAITING FOR TUNER" : c.encrypted ? "ENCRYPTED" : v.audible ? "PLAYING" : c.hang ? "HANG" : live && now - c.lastVoice < 800 ? "VOICE" : live ? "LISTENING" : "-";
            out.add(v);
        }
        out.sort(Comparator.comparing((CallView v) -> !v.onSpeaker).thenComparing(v -> !v.audible).thenComparingDouble(v -> v.mhz));
        return out;
    }

    // ------------------------------------------------------------------ tuners
    List<String[]> tunerRows() {
        List<String[]> r = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (ChannelDecoder d : e.ccDecoders) r.add(tunerRow("Control T1", d, now));
        for (Tuner t : e.tuners) for (ChannelDecoder d : t.decoders) r.add(tunerRow("Voice T" + t.id, d, now));
        return r;
    }

    String[] tunerRow(String role, ChannelDecoder d, long now) {
        boolean lk = d.locked(now);
        double err = d.frequencyError();
        String fe = !lk ? "- (no signal)" : d.freqHz > 0 ? String.format(Locale.ROOT, "%+.0f Hz (%+.1f ppm)", err, err / (d.freqHz / 1e6)) : String.format(Locale.ROOT, "%+.0f Hz", err);
        return new String[]{role, mhz(d.freqHz), lk ? "LOCKED  " + d.demod().name() : "searching", String.format(Locale.ROOT, "%.1f dB", d.power), fe};
    }

    /** Measured ppm error of a tuner: a locked decoder on it, else what earlier calls measured (NaN if unknown). */
    double ppmError(Tuner t) {
        if (e.mode != NativeEngine.Mode.RTL || t == null) return Double.NaN;
        long now = System.currentTimeMillis();
        if (t == e.ccTuner) for (ChannelDecoder d : e.ccDecoders) if (d.locked(now) && d.freqHz > 0) return d.frequencyError() / (d.freqHz / 1e6);
        for (ChannelDecoder v : t.decoders) if (v.locked(now) && v.syncs() > 3) return v.frequencyError() / (v.freqHz / 1e6);
        return t == e.ccTuner ? Double.NaN : t.ppmErr;
    }

    double level(Tuner t) {
        double l = -99; long now = System.currentTimeMillis();
        if (t == e.ccTuner) for (ChannelDecoder d : e.ccDecoders) if (d.locked(now)) l = Math.max(l, d.power);
        for (ChannelDecoder v : t.decoders) if (!Double.isNaN(v.syncPower)) l = Math.max(l, v.syncPower);
        return l;
    }

    String who(Tuner t) { return (t == e.ccTuner ? "Control dongle" : "Voice dongle") + (e.tuners.size() > 2 ? " T" + t.id : ""); }

    Map<String, int[]> suggestions() {
        Map<String, int[]> m = new LinkedHashMap<>();
        if (e.mode != NativeEngine.Mode.RTL) return m;
        for (Tuner t : e.tuners) if (t.src instanceof RtlSdr d) {
            double p = ppmError(t);
            int ppm = !Double.isNaN(p) && Math.abs(p) >= TunerAdvice.PPM_DEADBAND ? TunerAdvice.suggestedPpm(d.ppmSetting(), p) : Integer.MIN_VALUE;
            int g = TunerAdvice.suggestedGain(d, level(t));
            if (ppm != Integer.MIN_VALUE || g >= 0) m.put(d.info().key(), new int[]{ppm, g});
        }
        return m;
    }

    /** Filter bank use and processing load of a wideband tuner, plus the Airspy's USB health. */
    String widebandLine(Tuner t) {
        int bands = 0; Set<Integer> used = new HashSet<>();
        for (ChannelDecoder d : t.decoders) used.add(d.band);
        if (t == e.ccTuner) for (ChannelDecoder d : e.ccDecoders) used.add(d.band);
        bands = used.size();
        String s = String.format(Locale.ROOT, "%d sub-bands of %.1f kHz, %d in use, %d channel decoders; processing %.0f %% of one CPU core",
                t.bank.M, t.bank.SPACING / 1e3, bands, t.decoders.size() + (t == e.ccTuner ? e.ccDecoders.size() : 0), 100 * t.dspLoad());
        if (t.src.usbStream() != null) s += "; " + usbLine(t.src.usbStream());
        if (t.src instanceof p25monitor.sdr.SoapySdr d) s += String.format(Locale.ROOT, "; receiver: %d blocks, %d dropped%s, %d driver overflows", d.blocks, d.droppedBlocks, dropCause(d.droppedBlocks, d.guard), d.overflows);
        return s;
    }

    static String usbLine(AirspyStream st) {
        double lostMs = st.droppedSamples / st.complexRate * 1000;
        return String.format(Locale.ROOT, "USB: %d blocks received, %d dropped (%d samples = %.0f ms lost)%s, %d USB errors, queue peak %d",
                st.blocks, st.droppedBlocks, st.droppedSamples, lostMs, dropCause(st.droppedBlocks, st.guard), st.usbErrors, st.queuePeak);
    }

    /**
     * Why USB blocks were dropped: while the sample processing was stopped or restarting (a crash, not the CPU) versus
     * while it was running but too slow (the PC is not keeping up).
     */
    static String dropCause(long dropped, p25monitor.sdr.ProcessingGuard g) {
        if (dropped <= 0) return "";
        long stopped = g == null ? 0 : Math.min(dropped, g.droppedWhileStopped), slow = dropped - stopped;
        if (slow == 0) return " - all while sample processing was stopped/restarting (not CPU overload)";
        if (stopped == 0) return " - THE PC IS NOT KEEPING UP";
        return " - " + stopped + " while sample processing was stopped/restarting, " + slow + " because THE PC IS NOT KEEPING UP";
    }

    /** Sample processing health of a tuner: stopped now, restarts (with the last failure and its stack), decoder errors. */
    static String processingLine(Tuner t) {
        p25monitor.sdr.ProcessingGuard g = t.src.guard();
        List<String> parts = new ArrayList<>();
        if (g != null) {
            long idle = (System.nanoTime() - g.lastProgressNanos) / 1_000_000L;
            if (t.src.running() && idle > 2000) parts.add("PROCESSING STOPPED - nothing processed for " + idle / 1000 + " s" + (g.recovering ? " (restarting)" : ""));
            if (g.restarts > 0) parts.add(g.restarts + " processing restart" + (g.restarts == 1 ? "" : "s") + (g.stallRestarts > 0 ? " (" + g.stallRestarts + " by the stall watchdog)" : "")
                    + ", last " + new java.text.SimpleDateFormat("HH:mm:ss").format(new Date(g.lastRestartAt)) + ": " + g.lastFailure);
        }
        if (t.decoderErrors > 0) parts.add(t.decoderErrors + " decoder error" + (t.decoderErrors == 1 ? "" : "s") + " contained (last " + t.lastDecoderError + ")");
        if (parts.isEmpty()) return g == null ? "" : "running, no restarts";
        return String.join("; ", parts);
    }

    List<String> dongleRows() {
        List<String> out = new ArrayList<>();
        for (Tuner t : e.tuners) if (t.src instanceof AirspyR2 a) {
            AirspyStream st = a.stream();
            out.add(String.format(Locale.ROOT, "T%d %s %s: %.1f MS/s, gain %s, bias-T %s, firmware %s; ", t.id, t == e.ccTuner ? "control" : "voice", a.info().label().trim(),
                    a.sampleRate() / 1e6, a.gain().describe(), a.biasTee() ? "ON" : "off", a.firmware.isBlank() ? "?" : a.firmware)
                    + (st == null ? "not streaming" : String.format(Locale.ROOT, "ADC clipping %.3f %%, peak %.1f dBFS, level %.1f dBFS; %s, %s; ", 100 * st.clipFraction, st.peakDb, st.rmsDb, a.usbMode, usbLine(st)))
                    + t.window());
        }
        for (Tuner t : e.tuners) if (t.src instanceof p25monitor.sdr.SoapySdr d) out.add(String.format(Locale.ROOT, "T%d %s %s: %s; ADC clipping %.3f %%, peak %.1f dBFS; %d blocks, %d dropped%s, %d driver overflows, %d read errors, queue peak %d; %s",
                t.id, t == e.ccTuner ? "control" : "voice", d.info().tagged(), d.configReport, 100 * d.clipFraction, d.peakDb, d.blocks, d.droppedBlocks, dropCause(d.droppedBlocks, d.guard),
                d.overflows, d.readErrors, d.queuePeak, t.window()));
        for (Tuner t : e.tuners) if (t.src instanceof RtlSdr d) out.add(String.format(Locale.ROOT, "T%d %s %s: PPM %d, gain %s, ADC clipping %.3f %%, peak %.1f dB; USB %s, %d blocks dropped%s, queue peak %d; %s",
                t.id, t == e.ccTuner ? "control" : "voice", d.info().label().trim(), d.ppmSetting(),
                d.gainReport.isEmpty() ? (d.gainSetting() < 0 ? "auto" : d.gainSetting() / 10.0 + " dB") : d.gainReport,
                100 * d.clipFraction, d.peakDb, d.usbMode, d.droppedBlocks, dropCause(d.droppedBlocks, d.guard), d.queuePeak, t.window()));
        return out;
    }

    String gainAdvice() {
        if (e.mode != NativeEngine.Mode.RTL) return "";
        List<String> out = new ArrayList<>();
        for (Tuner t : e.tuners) if (t.src instanceof RtlSdr d) { String a = TunerAdvice.gainAdvice(who(t), d, level(t)); if (!a.isEmpty()) out.add(a); }
        for (Tuner t : e.tuners) if (t.src instanceof AirspyR2 a && a.stream() != null) { String g = TunerAdvice.airspyGainAdvice("Airspy T" + t.id, a.gain(), a.stream()); if (!g.isEmpty()) out.add(g); }
        for (Tuner t : e.tuners) if (t.src instanceof p25monitor.sdr.SoapySdr d && d.running()) {
            if (d.clipFraction > 0.001) out.add(String.format(Locale.ROOT, "%s T%d is overloaded (%.2f %% of samples clipped): lower its gain on the Engine page.", d.driver().product, t.id, 100 * d.clipFraction));
            else if (d.peakDb < -40 && d.blocks > 50) out.add(String.format(Locale.ROOT, "%s T%d signal is very weak (peak %.0f dBFS): raise its gain on the Engine page.", d.driver().product, t.id, d.peakDb));
        }
        return String.join("  ", out);
    }

    String ppmAdvice() {
        if (e.mode != NativeEngine.Mode.RTL) return "";
        List<String> out = new ArrayList<>();
        for (Tuner t : e.tuners) if (t.src instanceof RtlSdr d) {
            double p = ppmError(t);
            if (Double.isNaN(p)) continue;
            out.add(Math.abs(p) < TunerAdvice.PPM_DEADBAND ? String.format(Locale.ROOT, "%s PPM is right (%+.1f ppm residual, corrected automatically).", who(t), p)
                    : String.format(Locale.ROOT, "%s: measured %+.1f ppm (signals appear %s) - set its PPM to %d (now %d).", who(t), p, p > 0 ? "high" : "low", TunerAdvice.suggestedPpm(d.ppmSetting(), p), d.ppmSetting()));
        }
        String r = String.join("  ", out);
        return r + (r.contains("set its PPM") ? "  Corrected automatically meanwhile." : "");
    }
}
