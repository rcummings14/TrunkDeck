package p25monitor.nativeengine;

import p25monitor.*;
import p25monitor.dsp.PolyphaseBank;
import p25monitor.p25.*;
import p25monitor.sdr.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/**
 * The TrunkDeck engine: P25 trunking (Phase 1 control channel, Phase 1 and Phase 2 voice), decoded in this program.
 * <p>All receivers (RTL-SDR dongles, an I/Q recording, simulated dongles) form a tuner pool. The control tuner decodes
 * the active control channel; every voice grant inside ANY tuner's usable window is decoded at the same time,
 * each call with its own channel decoder and IMBE codec (Phase 1), or its own time slot of a shared TDMA channel decoder
 * and AMBE+2 codec (Phase 2, {@link TdmaChannels}). A second dongle roams: it is retuned for a grant outside all
 * windows, but only while it carries no active call (see {@link CallManager}).
 * <p>Every call is recorded / uploaded on its own; the speaker plays one call at a time by priority, a mix, or one held
 * talkgroup ({@link SpeakerRouter}); each stream feed gets its own queued (or mixed) program ({@link Streaming}).
 * Encrypted calls (either phase) are flagged and not decoded.
 */
public final class NativeEngine {
    public enum Mode { RTL, FILE, SIMULATED }
    public enum SpeakerMode { PRIORITY, MIX, HOLD, QUEUE }

    public interface Listener {
        void callStarted(CallEvent e);
        void callUpdated(CallEvent e);
        void messages(int count, String last);
        /** A call that was already listed is now the one on the speaker. */
        default void callFollowed(CallEvent e) {}
    }

    public static final class Msg { public final long time; public final double mhz; public final String text; Msg(long t, double m, String s) { time = t; mhz = m; text = s; } }
    public interface FrameTap { void frame(int tg, long freq, byte[] imbe); }

    public static final class TunerSettings {
        public String role = "auto"; // auto, control, voice, off
        public int ppm = 0, gainTenths = 402;
        public boolean biasTee;
        public static TunerSettings load(Properties p, String key) {
            TunerSettings t = new TunerSettings();
            t.role = p.getProperty("native.tuner." + key + ".role", "auto");
            t.ppm = Integer.parseInt(p.getProperty("native.tuner." + key + ".ppm", "0"));
            t.gainTenths = Integer.parseInt(p.getProperty("native.tuner." + key + ".gain", "402"));
            t.biasTee = Boolean.parseBoolean(p.getProperty("native.tuner." + key + ".bias", "false"));
            return t;
        }
        public void save(Properties p, String key) {
            p.setProperty("native.tuner." + key + ".role", role);
            p.setProperty("native.tuner." + key + ".ppm", String.valueOf(ppm));
            p.setProperty("native.tuner." + key + ".gain", String.valueOf(gainTenths));
            p.setProperty("native.tuner." + key + ".bias", String.valueOf(biasTee));
        }
    }

    /**
     * Per-Airspy settings ("native.tuner.airspy-SERIAL.*"): role (auto = control + every voice channel in its window),
     * sample rate (10 or 2.5 MS/s), gain ("lin:N", "sens:N" N = 0-21, or "man:LNA,MIX,VGA"), bias-T (off by default),
     * USB packing, and a fixed centre in MHz (blank = chosen automatically).
     */
    public static final class AirspySettings {
        public String role = "auto", gain = "lin:16", center = "";
        public double rate = 10e6;
        public boolean biasTee, packing;
        public static AirspySettings load(Properties p, String key) {
            AirspySettings a = new AirspySettings();
            String k = "native.tuner." + key + ".";
            a.role = p.getProperty(k + "role", "auto");
            try { a.rate = Double.parseDouble(p.getProperty(k + "rate", "10000000")); } catch (Exception e) { a.rate = 10e6; }
            if (a.rate != 2.5e6) a.rate = 10e6;
            a.gain = AirspyR2.Gain.parse(p.getProperty(k + "gain", "lin:16")).toString();
            a.biasTee = Boolean.parseBoolean(p.getProperty(k + "bias", "false"));
            a.packing = Boolean.parseBoolean(p.getProperty(k + "packing", "false"));
            a.center = p.getProperty(k + "center", "").trim();
            return a;
        }
        public void save(Properties p, String key) {
            String k = "native.tuner." + key + ".";
            p.setProperty(k + "role", role);
            p.setProperty(k + "rate", String.valueOf((long) rate));
            p.setProperty(k + "gain", gain);
            p.setProperty(k + "bias", String.valueOf(biasTee));
            p.setProperty(k + "packing", String.valueOf(packing));
            p.setProperty(k + "center", center);
        }
        /** Fixed centre in Hz, or 0 for automatic. */
        public long centerHz() { try { double m = Double.parseDouble(center.replace(',', '.')); return m > 24 && m < 1800 ? Math.round(m * 1e6) : 0; } catch (Exception e) { return 0; } }
    }

    /**
     * Per-receiver settings of an SDRplay / HackRF / LimeSDR / USRP (SoapySDR, 1.7.0, untested with real hardware):
     * "native.tuner.soapy-DRIVER-SERIAL.*" role, sample rate, gain code (see {@link SoapyDriver#gainCodes}), PPM,
     * bias-T (SDRplay only), antenna (blank = sensible default) and a fixed centre in MHz (blank = automatic).
     */
    public static final class SoapySettings {
        public String role = "auto", gain = "default", center = "", antenna = "";
        public double rate;
        public int ppm;
        public boolean biasTee;
        public static SoapySettings load(Properties p, String key, SoapyDriver d) {
            SoapySettings a = new SoapySettings();
            String k = "native.tuner." + key + ".";
            a.role = p.getProperty(k + "role", "auto");
            try { a.rate = Double.parseDouble(p.getProperty(k + "rate", String.valueOf((long) d.defaultRate))); } catch (Exception e) { a.rate = d.defaultRate; }
            boolean ok = false; for (double r : d.rates) if (r == a.rate) ok = true;
            if (!ok) a.rate = d.defaultRate;
            a.gain = p.getProperty(k + "gain", "default");
            if (!d.gainCodes().contains(a.gain) && !a.gain.matches("db:-?[0-9.]+|agc(:[0-9])?")) a.gain = "default";
            try { a.ppm = Math.max(-200, Math.min(200, Integer.parseInt(p.getProperty(k + "ppm", "0").trim()))); } catch (Exception e) { a.ppm = 0; }
            a.biasTee = d.hasBiasTee() && Boolean.parseBoolean(p.getProperty(k + "bias", "false"));
            a.center = p.getProperty(k + "center", "").trim();
            a.antenna = p.getProperty(k + "antenna", "").trim();
            return a;
        }
        public void save(Properties p, String key) {
            String k = "native.tuner." + key + ".";
            p.setProperty(k + "role", role);
            p.setProperty(k + "rate", String.valueOf((long) rate));
            p.setProperty(k + "gain", gain);
            p.setProperty(k + "ppm", String.valueOf(ppm));
            p.setProperty(k + "bias", String.valueOf(biasTee));
            p.setProperty(k + "center", center);
            if (!antenna.isEmpty()) p.setProperty(k + "antenna", antenna); else p.remove(k + "antenna");
        }
        public long centerHz() { try { double m = Double.parseDouble(center.replace(',', '.')); return m > 24 && m < 6000 ? Math.round(m * 1e6) : 0; } catch (Exception e) { return 0; } }
    }

    /** SoapySDR receivers on/off (Engine page): "native.soapy" = true (default) / false. */
    public static boolean soapyEnabled(Properties p) { return !"false".equalsIgnoreCase(p.getProperty("native.soapy", "true").trim()); }

    public static final int IMBE_NOISE = VoiceCallDecoder.IMBE_NOISE, GAIN_FLOOR = TunerAdvice.GAIN_FLOOR;
    public static final double PPM_DEADBAND = TunerAdvice.PPM_DEADBAND;
    public static int suggestedPpm(int current, double measuredPpm) { return TunerAdvice.suggestedPpm(current, measuredPpm); }
    public static int suggestedGain(int g, int[] gains, double clip, double level) { return TunerAdvice.suggestedGain(g, gains, clip, level); }

    // ------------------------------------------------------------------ state (package-private: shared with the helpers)
    final App app;
    final Listener listener;
    final ControlChannel cc = new ControlChannel(this);
    final CallManager calls = new CallManager(this);
    final VoiceCallDecoder voice = new VoiceCallDecoder(this);
    final SpeakerRouter speaker = new SpeakerRouter(this);
    final EngineDiagnostics diag = new EngineDiagnostics(this);
    RadioSystem sys;
    Mode mode;
    volatile boolean running, huntable;
    volatile String state = "STOPPED", problem;
    final ConcurrentLinkedDeque<String> log = new ConcurrentLinkedDeque<>();
    final ConcurrentLinkedDeque<Msg> msgs = new ConcurrentLinkedDeque<>();
    final List<RtlSdr> dongles = new ArrayList<>();
    final List<AirspyR2> airspys = new ArrayList<>();
    final List<SoapySdr> soapys = new ArrayList<>();
    /** Wideband control tuner: centre fixed by the user (0 = planned automatically), and whether the IDEN_UP plan ran. */
    volatile long fixedWideCenter;
    volatile boolean widePlanned;
    /** Threads that decode channels of wideband tuners in parallel (created on demand). */
    java.util.concurrent.ForkJoinPool widePool;
    /** The tuner pool: [0] is the control tuner, the rest roam for voice. */
    final CopyOnWriteArrayList<Tuner> tuners = new CopyOnWriteArrayList<>();
    volatile Tuner ccTuner;
    IqSource ccSource;
    volatile long ccCenter;
    final CopyOnWriteArrayList<ChannelDecoder> ccDecoders = new CopyOnWriteArrayList<>();
    final ChannelMap map = new ChannelMap();
    final Map<String, ActiveCall> callMap = new ConcurrentHashMap<>();
    final Map<String, Long> recentlyEnded = new ConcurrentHashMap<>();
    AudioOut audio = new AudioOut();
    Streaming streaming;
    ScheduledExecutorService house;
    List<Long> ccFreqs = new ArrayList<>();
    List<List<Long>> ccGroups = new ArrayList<>();
    int ccGroup;
    long lastTsbkAt, startedAt, lastHuntAt, lastCcPlanAt, huntCount, usageDecayAt;
    volatile boolean lsmPreferred;
    volatile String ccDemodLogged = "";
    volatile long retunes, follows, phase2Calls;
    final java.util.concurrent.atomic.AtomicBoolean ambeMissingLogged = new java.util.concurrent.atomic.AtomicBoolean();
    final TdmaChannels tdma = new TdmaChannels(this);
    final Map<String, Long> undecodable = new ConcurrentHashMap<>();
    final Set<Long> voiceOnCcLogged = ConcurrentHashMap.newKeySet();
    volatile String[][] lastDiag;
    int iqRecorded;
    /** Voice channel usage seen in grants (frequency -> decayed count): where tuners are placed. */
    final Map<Long, Double> usage = new ConcurrentHashMap<>();
    /** Missed calls by reason, and the most recent ones. */
    final Map<String, Long> missed = new ConcurrentSkipListMap<>();
    final ConcurrentLinkedDeque<String> missedRecent = new ConcurrentLinkedDeque<>();
    volatile int peakConcurrent;
    /** Test hook: every voice frame handed to the speaker {callTg, lastLcTgOnChannel, imbeErrors, muted(0/1)}. */
    public volatile java.util.function.Consumer<int[]> playTap;
    /** Test hook: every decoded voice frame of every call. */
    public volatile FrameTap frameTap;
    /** Test hook: every tuner retune {tuner id, active calls on it at that moment, new centre}. */
    public volatile java.util.function.Consumer<long[]> retuneTap;
    /** Test hook: replaces the sound device. */
    public AudioOut.Sink audioSinkOverride;
    // site info
    public volatile int nac = -1, sysId = -1, rfss = -1, site = -1;
    public volatile long wacn = -1, ccFreqHz;
    public final Map<String, String> adjacent = new ConcurrentSkipListMap<>();
    public volatile long msgTotal, tsbkBad;
    int pendingMsgs;
    String lastMsg;
    final SimpleDateFormat ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    final SimpleDateFormat fileTs = new SimpleDateFormat("yyyyMMdd_HHmmss");
    Path file; double fileRate; long fileCenter; double fileOffset; boolean fileRealtime = true;
    List<IqSource> customSources;

    public NativeEngine(App app, Listener l) { this.app = app; this.listener = l; }

    public void log(String s) {
        log.add(new SimpleDateFormat("HH:mm:ss").format(new Date()) + "  " + s);
        while (log.size() > 800) log.pollFirst();
    }

    public List<String> recentLog() { return new ArrayList<>(log); }
    public List<Msg> recentMessages() { return new ArrayList<>(msgs); }
    public boolean isRunning() { return running; }
    public String state() { return state; }
    public String problem() { return problem; }
    public Mode mode() { return mode; }
    public void setFile(Path p, double rate, long center, double offsetHz, boolean realtime) { file = p; fileRate = rate; fileCenter = center; fileOffset = offsetHz; fileRealtime = realtime; }

    // ------------------------------------------------------------------ start / stop
    /** Runs the engine on caller-supplied sources (simulations / tests): cc = control tuner, voice = a roaming tuner. */
    public synchronized void startCustom(RadioSystem s, IqSource cc, IqSource voice) throws Exception {
        List<IqSource> l = new ArrayList<>(); l.add(cc); if (voice != null) l.add(voice);
        startCustom(s, l);
    }

    /** sources[0] = control tuner, the rest roam for voice. */
    public synchronized void startCustom(RadioSystem s, List<IqSource> sources) throws Exception {
        customSources = sources;
        try { start(s, Mode.SIMULATED); } finally { customSources = null; }
    }

    /** How many times the engine was started (tests: proves a UI action did not restart it). */
    public volatile int starts;

    public synchronized void start(RadioSystem s, Mode m) throws Exception {
        starts++;
        if (running) stop(true);   // a restart: streams stay connected (1.4.4)
        undecodable.clear(); tdma.clear(); ambeMissingLogged.set(false); voiceOnCcLogged.clear(); retunes = 0; follows = 0; phase2Calls = 0; lastDiag = null; iqRecorded = 0;
        lsmPreferred = "lsm".equalsIgnoreCase(s.nativeModulation); ccDemodLogged = "";
        usage.clear(); missed.clear(); missedRecent.clear(); peakConcurrent = 0; tuners.clear(); huntCount = 0; fixedWideCenter = 0; widePlanned = false;
        SpeakerMode sm;
        try { sm = SpeakerMode.valueOf(app.settings.getProperty("native.speaker", "PRIORITY")); } catch (Exception e) { sm = SpeakerMode.PRIORITY; }
        int hold;
        try { hold = Integer.parseInt(app.settings.getProperty("native.holdTg", "-1")); } catch (Exception e) { hold = -1; }
        speaker.setQueueMaxDelay(speakerQueueMaxDelay() * 1000L);
        speaker.setMode(sm, hold);
        speaker.speaker = null; speaker.last = null;
        if (audioSinkOverride != null) audio = new AudioOut(audioSinkOverride);
        sys = s; mode = m; problem = null;
        callMap.clear(); recentlyEnded.clear(); adjacent.clear(); msgs.clear();
        nac = -1; sysId = -1; rfss = -1; site = -1; wacn = -1; msgTotal = 0; tsbkBad = 0; ccFreqHz = 0;
        startedAt = System.currentTimeMillis(); lastTsbkAt = 0; lastHuntAt = 0; lastCcPlanAt = startedAt; usageDecayAt = startedAt;
        List<Long> ccs = new ArrayList<>();
        for (double f : s.controlChannelsMHz) ccs.add(Math.round(f * 1e6));
        ccFreqs = ccs;
        int expectedNac = parseNac(s.nac);
        if (EngineLibs.loadJmbe(EngineLibs.findJmbe(app.profile, app.settings.getProperty("native.jmbe", "")))) log("Voice codec: " + EngineLibs.jmbeStatus());
        else log("Voice codec not available (" + EngineLibs.jmbeStatus() + ") - calls are followed and logged but stay silent. See the Engine page.");
        if (!audio.open()) log(audio.error());
        audio.setVolume(speakerVolume() / 100f); audio.setMuted(speakerMuted());   // the local speaker only (streams and recordings get full audio)
        boolean feedMix = Boolean.parseBoolean(app.settings.getProperty("native.feedMix", "false"));
        long feedDelay;
        try { feedDelay = 1000L * Math.max(5, Math.min(600, Integer.parseInt(app.settings.getProperty("native.feedMaxDelay", "30")))); } catch (Exception e) { feedDelay = 30_000; }
        updateTalkgroups(s.talkgroups);
        Streaming kept = streaming;
        if (kept != null && keptStreams) {   // restart (Apply, another system): feeds stay connected, only changes are applied
            for (String l : kept.reconfigure(app.streams, feedMix ? CallMixer.Mode.MIX : CallMixer.Mode.QUEUE, feedDelay)) log("Streaming: " + l);
        } else {
            if (kept != null) kept.stop();
            streaming = new Streaming(app.streams, this::log, feedMix ? CallMixer.Mode.MIX : CallMixer.Mode.QUEUE, feedDelay);
        }
        keptStreams = false;
        streaming.unitTags(id -> { RadioSystem rs = sys; return rs == null ? "" : rs.radios.alias(id); });
        streaming.rdioUnitTags(id -> { RadioSystem rs = sys; return rs == null ? "" : rs.radios.alias(id); });
        streaming.rdioSystemLabel(() -> { RadioSystem rs = sys; return rs == null ? "" : rs.name; });
        streaming.feedRadioNames(id -> { RadioSystem rs = sys; return rs == null ? "" : rs.radios.alias(id); });
        if (streaming.any()) { streaming.start(); log("Streaming: " + String.join(", ", streaming.status())); }

        switch (m) {
            case RTL -> startRtl(ccs);
            case FILE -> startFile(ccs);
            case SIMULATED -> startSimulated(s, ccs, expectedNac);
        }
        ccSource = ccTuner.src;
        if (!tuners.contains(ccTuner)) tuners.add(0, ccTuner);
        cc.buildDecoders(expectedNac);
        running = true;
        state = "LOOKING FOR CONTROL CHANNEL";
        speaker.start();
        for (Tuner t : tuners) startTuner(t);
        house = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "engine-housekeeping"); t.setDaemon(true); return t; });
        house.scheduleAtFixedRate(this::housekeeping, 250, 250, TimeUnit.MILLISECONDS);
        // end of a transmission without a terminator (fade, lost TDU): checked every 50 ms so uploads start promptly
        house.scheduleAtFixedRate(() -> { try { calls.transmissionTick(System.currentTimeMillis()); } catch (Throwable t) { log("transmission check: " + t); } }, 50, 50, TimeUnit.MILLISECONDS);
        StringBuilder w = new StringBuilder();
        for (Tuner t : tuners) w.append(w.length() > 0 ? "; " : "").append("T").append(t.id).append(t == ccTuner ? " control " : " voice ").append(t.window());
        log("TrunkDeck engine started (" + m + "), " + tuners.size() + " tuner(s): " + w);
    }

    void startFile(List<Long> ccs) throws Exception {
        IqFileSource f = new IqFileSource(file, fileRate, fileCenter);
        f.realtime = fileRealtime;
        ccTuner = new Tuner(1, "I/Q file", f, Tuner.Role.CONTROL);
        ccCenter = f.centerHz(); ccTuner.center = ccCenter;
        List<Long> inWin = new ArrayList<>();
        if (ccCenter > 0) for (long c : ccs) if (Math.abs(c - ccCenter) < f.sampleRate() * 0.45) inWin.add(c);
        if (inWin.isEmpty()) { ccCenter = 0; ccTuner.center = 0; inWin.add(Math.round(fileOffset)); }
        ccGroups = List.of(inWin); ccFreqs = inWin; ccGroup = 0;
        log("I/Q file " + f.name() + ": " + (int) f.sampleRate() + " S/s, " + String.format("%.1f s", f.seconds()) +
                (ccCenter > 0 ? ", centre " + mhz(ccCenter) + " MHz" : ", decoding the channel at offset " + (long) fileOffset + " Hz"));
    }

    void startSimulated(RadioSystem s, List<Long> ccs, int expectedNac) {
        if (customSources != null) {
            IqSource src = customSources.get(0);
            ccTuner = new Tuner(1, src.name(), src, Tuner.Role.CONTROL);
            tuners.add(ccTuner);
            for (int i = 1; i < customSources.size(); i++) tuners.add(new Tuner(i + 1, customSources.get(i).name(), customSources.get(i), Tuner.Role.ROAMING));
            ccGroups = groupChannels(ccs, (long) (src.sampleRate() * 0.8));
            ccGroup = 0;
            ccCenter = src.centerHz(); ccTuner.center = ccCenter;
            boolean fits = false;
            for (long f : ccs) if (ccTuner.covers(f) || Math.abs(f - ccCenter) < src.sampleRate() * 0.45) fits = true;
            if (ccCenter <= 0 || !fits) { long c = centerFor(ccGroups.get(0)); if (src.tune(c)) { ccCenter = c; ccTuner.center = c; } }
            StringBuilder n = new StringBuilder();
            for (IqSource x : customSources) n.append(n.length() > 0 ? " + " : "").append(x.name());
            log("Custom signal sources: " + n);
            return;
        }
        SyntheticSource ss = new SyntheticSource(s, expectedNac < 0 ? 0x293 : expectedNac);
        ccTuner = new Tuner(1, "built-in test signal", ss, Tuner.Role.CONTROL);
        ccCenter = ss.centerHz(); ccTuner.center = ccCenter;
        ccGroups = List.of(List.of(ss.ccHz())); ccFreqs = List.of(ss.ccHz()); ccGroup = 0;
        log("Built-in test signal: simulated P25 system around " + mhz(ss.ccHz()) + " MHz (not a real radio signal)");
    }

    void startTuner(Tuner t) throws Exception {
        final boolean isCc = t == ccTuner;
        if (t.wideband()) { startWideTuner(t, isCc); return; }
        t.src.start(new IqSource.Listener() {
            public void samples(float[] i, float[] q, int n) {
                if (isCc) for (ChannelDecoder d : ccDecoders) runDecoder(t, d, i, q, n);
                for (ChannelDecoder d : t.decoders) runDecoder(t, d, i, q, n);
            }
            public void gap() {
                if (isCc) for (ChannelDecoder d : ccDecoders) gapDecoder(t, d);
                for (ChannelDecoder d : t.decoders) gapDecoder(t, d);
            }
            public void reset() { gap(); }
        });
    }

    /**
     * Runs one decoder on a block. An exception is contained to that decoder: logged with its stack, the decoder is
     * re-synced (and replaced if it keeps failing), the other decoders and the tuner carry on.
     */
    void runDecoder(Tuner t, ChannelDecoder d, float[] i, float[] q, int n) {
        if (d.faulted) return;
        try { d.process(i, q, n); } catch (Throwable x) { decoderFailed(t, d, x); }
    }

    void gapDecoder(Tuner t, ChannelDecoder d) {
        if (d.faulted) return;
        try { d.gap(); } catch (Throwable x) { decoderFailed(t, d, x); }
    }

    volatile boolean faultedDecoders;

    void decoderFailed(Tuner t, ChannelDecoder d, Throwable x) {
        Throwable r = p25monitor.sdr.ProcessingGuard.root(x);
        long now = System.currentTimeMillis(), count;
        String what;
        synchronized (t) {
            count = ++t.decoderErrors;
            d.faults = now - d.lastFaultAt > 10_000 ? 1 : d.faults + 1; d.lastFaultAt = now;
            what = "Tuner T" + t.id + " decoder " + mhz(d.freqHz) + " MHz failed: " + p25monitor.sdr.ProcessingGuard.describe(r, 8);
            t.lastDecoderError = new SimpleDateFormat("HH:mm:ss").format(new Date(now)) + " " + what;
        }
        if (d.faults >= 3) { d.faulted = true; faultedDecoders = true; what += " - failed 3 times in 10 s: replacing it"; }
        else {
            what += " - decoder re-synced";
            try { d.gap(); } catch (Throwable y) { d.faulted = true; faultedDecoders = true; what += " (re-sync failed too: replacing it)"; }
        }
        if (count <= 20 || count % 100 == 0) log(what + (count > 1 ? " [decoder error #" + count + "]" : ""));
    }

    /** Housekeeping: decoders that kept failing are replaced (control channel rebuilt; a voice call ended, re-followed from the next grant). */
    void replaceFaultedDecoders() {
        if (!faultedDecoders) return;
        faultedDecoders = false;
        synchronized (this) {
            boolean rebuild = false;
            for (ChannelDecoder d : ccDecoders) if (d.faulted) rebuild = true;
            if (rebuild && sys != null && ccTuner != null) { log("Control-channel decoders rebuilt after repeated errors"); cc.buildDecoders(parseNac(sys.nac)); }
            for (ActiveCall a : new ArrayList<>(callMap.values())) { ChannelDecoder v = a.vd; if (v != null && v.faulted && !a.ended) calls.endCall(a, "decoder error"); }
            for (Tuner t : tuners) for (ChannelDecoder d : t.decoders) if (d.faulted) {
                boolean used = false; for (ActiveCall a : callMap.values()) if (a.vd == d && !a.ended) used = true;
                if (!used) { t.decoders.remove(d); tdma.channels.values().removeIf(ch -> ch.vd == d); }
            }
        }
    }

    /** Stall watchdog and processing restarts of every tuner (log messages with the failure's stack). */
    void watchTuners() {
        if (!soapys.isEmpty() || !SoapySdr.driverLog.isEmpty()) drainSoapyLog();
        long stall = Long.getLong("trunkdeck.stallMs", 5000);
        for (Tuner t : tuners) {
            try {
                t.src.watchdog(stall);
                p25monitor.sdr.ProcessingGuard g = t.src.guard();
                if (g != null) for (String m; (m = g.events.poll()) != null; ) log("Tuner T" + t.id + ": " + m);
            } catch (Throwable x) { log("Tuner T" + t.id + " watchdog: " + x); }
        }
    }

    /**
     * Wideband tuner: the filter bank splits each block into 312.5 kHz sub-bands (only those a decoder needs), then
     * every decoder runs on its sub-band - in parallel on a few threads when there are several.
     */
    void startWideTuner(Tuner t, boolean isCc) throws Exception {
        final PolyphaseBank b = t.bank;
        final boolean[] need = new boolean[b.M];
        final int threads = Integer.getInteger("trunkdeck.wideThreads", Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
        t.src.start(new IqSource.Listener() {
            public void samples(float[] i, float[] q, int n) {
                long t0 = System.nanoTime();
                ChannelDecoder[] cc = isCc ? ccDecoders.toArray(new ChannelDecoder[0]) : new ChannelDecoder[0];
                ChannelDecoder[] vd = t.decoders.toArray(new ChannelDecoder[0]);
                java.util.Arrays.fill(need, false);
                long c = t.center;
                for (ChannelDecoder d : cc) need[d.band = b.band(d.centerHz - c)] = true;
                for (ChannelDecoder d : vd) need[d.band = b.band(d.centerHz - c)] = true;
                b.process(i, q, n, need);
                int m = b.outN;
                int all = cc.length + vd.length;
                if (threads > 1 && all >= 3) {
                    ChannelDecoder[] list = new ChannelDecoder[all];
                    System.arraycopy(cc, 0, list, 0, cc.length); System.arraycopy(vd, 0, list, cc.length, vd.length);
                    widePool().submit(() -> java.util.Arrays.stream(list).parallel().forEach(d -> runDecoder(t, d, b.outI[d.band], b.outQ[d.band], m))).join();
                } else {
                    for (ChannelDecoder d : cc) runDecoder(t, d, b.outI[d.band], b.outQ[d.band], m);
                    for (ChannelDecoder d : vd) runDecoder(t, d, b.outI[d.band], b.outQ[d.band], m);
                }
                t.dspNanos += System.nanoTime() - t0; t.dspSamples += n;
            }
            public void gap() {
                if (isCc) for (ChannelDecoder d : ccDecoders) gapDecoder(t, d);
                for (ChannelDecoder d : t.decoders) gapDecoder(t, d);
            }
            /** After a processing failure: filter bank history cleared, every decoder re-synced. */
            public void reset() { b.reset(); gap(); }
        });
    }

    synchronized java.util.concurrent.ForkJoinPool widePool() {
        if (widePool == null) {
            int n = Integer.getInteger("trunkdeck.wideThreads", Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
            widePool = new java.util.concurrent.ForkJoinPool(n, pool -> { var w = java.util.concurrent.ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool); w.setName("wideband-decoder-" + w.getPoolIndex()); w.setDaemon(true); return w; }, null, false);
        }
        return widePool;
    }

    /**
     * Real receivers: every Airspy R2 and RTL-SDR dongle found, plus (1.7.0, untested with real hardware) SDRplay,
     * HackRF, LimeSDR and USRP receivers through SoapySDR. The control tuner is the one set to "control", else an
     * Airspy set to "auto" (it covers the control channel and the voice channels around it at once), else a SoapySDR
     * receiver set to "auto" (wideband like the Airspy), else an RTL dongle set to "auto". Every other receiver roams for voice.
     */
    void startRtl(List<Long> ccs) throws Exception {
        List<RtlSdr.Info> found = new ArrayList<>();
        List<AirspyR2.Info> spies = new ArrayList<>();
        List<SoapySdr.Info> soapy = new ArrayList<>();
        String rtlErr = null, usbErr = null, soapyErr = null;
        SoapySdr.enabled = soapyEnabled(app.settings);
        long t0 = System.currentTimeMillis();
        // SoapySDR receivers are searched at the same time as the RTL / Airspy ones (a USRP network search takes ~0.5 s)
        java.util.concurrent.FutureTask<List<SoapySdr.Info>> soapyFind = new java.util.concurrent.FutureTask<>(() -> SoapySdr.list());
        if (SoapySdr.enabled) { Thread th = new Thread(soapyFind, "soapy-search"); th.setDaemon(true); th.start(); }
        try { found = RtlSdr.list(); } catch (Throwable t) { rtlErr = t.getMessage(); }
        try { spies = AirspyR2.list(); } catch (Throwable t) { usbErr = t.getMessage(); }
        log("librtlsdr: " + (rtlErr == null ? RtlSdr.libraryInfo() : rtlErr) + "; " + found.size() + " RTL-SDR dongle(s) found");
        log("libusb (Airspy): " + (usbErr == null ? LibUsb.get().from + " " + LibUsb.get().version() : usbErr) + "; " + spies.size() + " Airspy receiver(s) found");
        for (AirspyR2.Info a : spies) if (!a.note.isBlank()) log("Airspy " + a.label());
        if (SoapySdr.enabled) {
            try { soapy = soapyFind.get(); } catch (java.util.concurrent.ExecutionException t) { soapyErr = t.getCause().getMessage(); } catch (Throwable t) { soapyErr = String.valueOf(t.getMessage()); }
            drainSoapyLog();
            if (soapyErr == null) {
                log("SoapySDR (SDRplay / HackRF / LimeSDR / USRP, untested): " + SoapySdr.libraryInfo() + "; " + soapy.size() + " receiver(s) found in " + (System.currentTimeMillis() - t0) + " ms");
                for (String st : SoapySdr.status()) if (!st.contains("driver ready")) log("SoapySDR " + st);
            } else log("SoapySDR receivers (SDRplay / HackRF / LimeSDR / USRP): not available - " + soapyErr.split("\n")[0]);
        } else log("SoapySDR receivers switched off (Engine page)");
        List<AirspyR2.Info> usable = new ArrayList<>();
        for (AirspyR2.Info a : spies) if (!AirspySettings.load(app.settings, a.key()).role.equals("off")) usable.add(a);
        List<SoapySdr.Info> soapyUsable = new ArrayList<>();
        for (SoapySdr.Info a : soapy) if (!SoapySettings.load(app.settings, a.key(), a.driver).role.equals("off")) soapyUsable.add(a);
        // the widest receiver first: it becomes the control tuner when several are set to "auto"
        soapyUsable.sort(Comparator.comparingDouble((SoapySdr.Info a) -> -SoapySettings.load(app.settings, a.key(), a.driver).rate));
        if (found.isEmpty() && usable.isEmpty() && soapyUsable.isEmpty()) {
            problem = "NO TUNER FOUND";
            if (found.isEmpty() && rtlErr != null && spies.isEmpty() && soapy.isEmpty()) throw new IllegalStateException(rtlErr);
            throw new IllegalStateException((spies.isEmpty() && soapy.isEmpty() ? "No RTL-SDR dongle, Airspy or other receiver found. Plug it in" : "Every receiver is set to \"off\" (Engine page \u2192 Tuners). Plug in another or change its role")
                    + (System.getProperty("os.name", "").toLowerCase().contains("win") ? " and install the WinUSB driver with Zadig (Setup & Help)." : " and check the udev rules (Setup & Help).")
                    + (soapyErr != null && SoapySdr.enabled ? " SDRplay / HackRF / LimeSDR / USRP: " + soapyErr.split("\n")[0] : ""));
        }
        AirspyR2.Info ca = null; RtlSdr.Info ci = null; SoapySdr.Info cs = null;
        for (AirspyR2.Info a : usable) if (AirspySettings.load(app.settings, a.key()).role.equals("control")) { ca = a; break; }
        if (ca == null) for (SoapySdr.Info a : soapyUsable) if (SoapySettings.load(app.settings, a.key(), a.driver).role.equals("control")) { cs = a; break; }
        if (ca == null && cs == null) for (RtlSdr.Info d : found) if (TunerSettings.load(app.settings, d.key()).role.equals("control")) { ci = d; break; }
        if (ca == null && cs == null && ci == null) for (AirspyR2.Info a : usable) if (AirspySettings.load(app.settings, a.key()).role.equals("auto")) { ca = a; break; }
        if (ca == null && cs == null && ci == null) for (SoapySdr.Info a : soapyUsable) if (SoapySettings.load(app.settings, a.key(), a.driver).role.equals("auto")) { cs = a; break; }
        if (ca == null && cs == null && ci == null) for (RtlSdr.Info d : found) if (TunerSettings.load(app.settings, d.key()).role.equals("auto")) { ci = d; break; }
        if (ca == null && cs == null && ci == null) throw new IllegalStateException("No receiver is assigned to the control channel (Engine page \u2192 Tuners).");
        int id = 2;
        if (ca != null || cs != null) {
            IqSource src; String label; long fixed;
            if (ca != null) { AirspySettings as = AirspySettings.load(app.settings, ca.key()); src = openAirspy(ca); label = ca.label().trim(); fixed = as.centerHz(); }
            else { SoapySettings ss = SoapySettings.load(app.settings, cs.key(), cs.driver); src = openSoapy(cs); label = cs.tagged(); fixed = ss.centerHz(); }
            ccTuner = new Tuner(1, label, src, Tuner.Role.CONTROL);
            tuners.add(ccTuner);
            ccSource = src;
            if (ccTuner.wideband()) {
                ccGroups = groupChannels(ccs, (long) (2 * ccTuner.halfSpan()));
                ccGroup = 0;
                fixedWideCenter = fixed;
                long c0 = fixedWideCenter > 0 ? fixedWideCenter : TunerPlanner.planWide(ccs, Map.of(), List.of(), x -> false, ccTuner.halfSpan(), ccTuner.dcGuard, 0);
                cc.retuneTo(c0 > 0 ? c0 : centerFor(ccGroups.get(0)), fixedWideCenter > 0 ? "fixed centre from the Engine page" : "start: covers the control channels; re-centred for the voice band once the site's channel plan is known");
                log("Control tuner T1: " + label + " (" + String.format(Locale.ROOT, "%.1f MS/s", src.sampleRate() / 1e6) + ", wideband: control channel + every voice channel in "
                        + String.format(Locale.ROOT, "%.1f MHz", 2 * ccTuner.halfSpan() / 1e6) + ")");
            } else {   // a SoapySDR receiver at a rate the filter bank cannot split: decoded like an RTL dongle
                ccGroups = groupChannels(ccs, (long) (2 * ccTuner.halfSpan()));
                ccGroup = 0;
                long c0 = TunerPlanner.plan(ccs, Map.of(), x -> false, ccTuner.halfSpan(), ccTuner.dcGuard, 0);
                cc.retuneTo(c0 > 0 ? c0 : centerFor(ccGroups.get(0)), "start");
                log("Control tuner T1: " + label + " (" + String.format(Locale.ROOT, "%.3f MS/s", src.sampleRate() / 1e6) + ", not a wideband rate: decoded like an RTL dongle)");
            }
        } else {
            ccGroups = groupChannels(ccs, 2_000_000);
            ccGroup = 0;
            RtlSdr d = openDongle(ci);
            ccTuner = new Tuner(1, ci.label().trim(), d, Tuner.Role.CONTROL);
            tuners.add(ccTuner);
            ccSource = d;
            long c0 = TunerPlanner.plan(ccs, Map.of(), x -> false, ccTuner.halfSpan(), ccTuner.dcGuard, 0);  // main + alternate if they fit
            cc.retuneTo(c0 > 0 ? c0 : centerFor(ccGroups.get(0)), "start");
            log("Control tuner T1: " + ci.label() + " (" + d.tunerName() + ")");
        }
        for (AirspyR2.Info v : usable) {
            if (v == ca) continue;
            String role = AirspySettings.load(app.settings, v.key()).role;
            if (role.equals("control")) continue;
            AirspyR2 r = openAirspy(v);
            tuners.add(new Tuner(id++, v.label().trim(), r, Tuner.Role.ROAMING));
            log("Voice tuner T" + (id - 1) + ": " + v.label() + " (wideband, roams to cover voice grants outside the other windows)");
        }
        for (SoapySdr.Info v : soapyUsable) {
            if (v == cs) continue;
            String role = SoapySettings.load(app.settings, v.key(), v.driver).role;
            if (role.equals("control")) continue;
            SoapySdr r;
            try { r = openSoapy(v); } catch (RuntimeException e) { log("Voice tuner " + v.tagged() + " not used: " + e.getMessage()); continue; }   // a voice receiver that fails does not stop the engine
            Tuner t = new Tuner(id++, v.tagged(), r, Tuner.Role.ROAMING);
            tuners.add(t);
            log("Voice tuner T" + (id - 1) + ": " + v.tagged() + " (" + (t.wideband() ? "wideband, " : "") + "roams to cover voice grants outside the other windows)");
        }
        for (RtlSdr.Info v : found) {
            if (v == ci) continue;
            String role = TunerSettings.load(app.settings, v.key()).role;
            if (role.equals("off") || role.equals("control")) continue;
            RtlSdr r = openDongle(v);
            tuners.add(new Tuner(id++, v.label().trim(), r, Tuner.Role.ROAMING));
            log("Voice tuner T" + (id - 1) + ": " + v.label() + " (" + r.tunerName() + ") - roams to cover voice grants outside the other windows");
        }
        if (tuners.size() == 1) log(ccTuner.wideband() ? (ccTuner.src instanceof AirspyR2 ? "One Airspy" : "One wideband receiver") + ": every voice channel inside its window is decoded (" + ccTuner.window() + ")."
                : "Only one dongle: voice channels are decoded only when they fall inside the control dongle's window (about 2 MHz).");
        for (Tuner t : tuners) if (t.src instanceof SoapySdr) { log("Note: " + SoapyDriver.UNTESTED_NOTE); break; }
    }

    SoapySdr openSoapy(SoapySdr.Info i) {
        SoapySettings s = SoapySettings.load(app.settings, i.key(), i.driver);
        SoapySdr d = new SoapySdr(i);
        d.open(s.rate, s.gain, s.ppm, s.biasTee, s.antenna);
        soapys.add(d);
        drainSoapyLog();
        log("Opened " + i.tagged() + ": " + d.configReport);
        return d;
    }

    /** SoapySDR and driver messages (warnings / errors) into the engine log. */
    void drainSoapyLog() {
        int n = 0;
        for (String m; (m = SoapySdr.driverLog.poll()) != null; ) if (n++ < 40) log("SoapySDR " + m);
    }

    AirspyR2 openAirspy(AirspyR2.Info i) {
        AirspySettings s = AirspySettings.load(app.settings, i.key());
        AirspyR2 a = new AirspyR2(i);
        a.open();
        airspys.add(a);
        try { a.configure(s.rate, AirspyR2.Gain.parse(s.gain), s.biasTee, s.packing); }
        catch (RuntimeException e) { a.stop(); airspys.remove(a); throw e; }
        log("Opened " + i.label() + ": firmware " + (a.firmware.isBlank() ? "?" : a.firmware) + ", " + String.format(Locale.ROOT, "%.1f MS/s", s.rate / 1e6)
                + ", gain " + a.gain().describe() + (s.biasTee ? ", bias-T ON" : ", bias-T off") + (s.packing ? ", USB packing on" : ""));
        return a;
    }

    RtlSdr openDongle(RtlSdr.Info i) throws Exception {
        TunerSettings t = TunerSettings.load(app.settings, i.key());
        RtlSdr d = new RtlSdr(i);
        d.setSampleRate(2_400_000);
        d.open();
        d.configure(t.ppm, t.gainTenths, t.biasTee);
        dongles.add(d);
        log("Opened " + i.label() + ": PPM " + t.ppm + ", gain " + (t.gainTenths < 0 ? "auto" : t.gainTenths / 10.0 + " dB") + (t.biasTee ? ", bias-T on" : ""));
        return d;
    }

    static List<List<Long>> groupChannels(List<Long> ccs, long span) {
        List<Long> s = new ArrayList<>(ccs);
        Collections.sort(s);
        List<List<Long>> g = new ArrayList<>();
        for (long f : s) {
            if (!g.isEmpty() && f - g.get(g.size() - 1).get(0) <= span * 0.8) g.get(g.size() - 1).add(f);
            else { List<Long> n = new ArrayList<>(); n.add(f); g.add(n); }
        }
        if (g.isEmpty()) g.add(new ArrayList<>(List.of(0L)));
        return g;
    }

    /** Centre frequency for a group of channels: midpoint, moved so no channel sits on the dongle's DC spike. */
    static long centerFor(List<Long> g) {
        long lo = g.get(0), hi = g.get(g.size() - 1), c = (lo + hi) / 2;
        boolean clash = true;
        for (int tries = 0; tries < 20 && clash; tries++) {
            clash = false;
            for (long f : g) if (Math.abs(f - c) < 120_000) { clash = true; break; }
            if (clash) c -= 150_000;
        }
        return c;
    }

    // ------------------------------------------------------------------ live settings (1.4.4)
    /**
     * The talkgroup settings the engine works with: copies of the system's talkgroups, replaced as a whole (copy on
     * write) whenever the user edits one, so the decoder threads never see a half-edited list or a set being changed.
     */
    volatile Map<Integer, Talkgroup> tgs = Map.of();

    /** The engine's copy of talkgroup tg, or null if it is not in the list. */
    Talkgroup tg(int tg) { return tgs.get(tg); }

    /** The talkgroup settings the running engine uses (a copy; tests and diagnostics). */
    public Talkgroup engineTalkgroup(int tg) { Talkgroup t = tgs.get(tg); return t == null ? null : t.copy(); }

    /**
     * A talkgroup was edited (mute, favorite, record, priority, streams, name ...): takes effect at once, without a
     * restart. Call on the thread that owns the list (the UI); the engine keeps its own copies.
     */
    public void updateTalkgroups(Collection<Talkgroup> list) {
        Map<Integer, Talkgroup> m = new HashMap<>();
        for (Talkgroup t : list) m.putIfAbsent(t.decimal, t.copy());   // first match, as RadioSystem.find
        Map<Integer, Talkgroup> snap = Collections.unmodifiableMap(m);
        tgs = snap;   // new calls see the edit at once
        // calls in progress: re-checked under the engine lock on the engine's own thread, so the UI never waits for it
        liveExec().execute(() -> { synchronized (this) { if (running && tgs == snap) calls.talkgroupsChanged(); } });
    }

    private final ThreadPoolExecutor live = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
            r -> { Thread t = new Thread(r, "tg-live"); t.setDaemon(true); return t; });
    { live.allowCoreThreadTimeOut(true); }
    private ExecutorService liveExec() { return live; }

    /** Tests: waits until the last talkgroup edit has reached the calls in progress. */
    public void awaitLive() throws Exception { liveExec().submit(() -> {}).get(5, TimeUnit.SECONDS); }

    /**
     * Streams page edits while running (1.4.4): only streams whose own settings changed are (re)started; every other
     * feed keeps its connection and listeners. Returns what changed.
     */
    public synchronized List<String> reconfigureStreams() {
        if (!running) return List.of();
        boolean feedMix = Boolean.parseBoolean(app.settings.getProperty("native.feedMix", "false"));
        long feedDelay;
        try { feedDelay = 1000L * Math.max(5, Math.min(600, Integer.parseInt(app.settings.getProperty("native.feedMaxDelay", "30")))); } catch (Exception e) { feedDelay = 30_000; }
        CallMixer.Mode mode = feedMix ? CallMixer.Mode.MIX : CallMixer.Mode.QUEUE;
        List<String> out;
        if (streaming == null) { streaming = new Streaming(app.streams, this::log, mode, feedDelay); out = streaming.any() ? List.of("started") : List.of(); }
        else out = streaming.reconfigure(app.streams, mode, feedDelay);
        streaming.unitTags(id -> { RadioSystem rs = sys; return rs == null ? "" : rs.radios.alias(id); });
        streaming.rdioUnitTags(id -> { RadioSystem rs = sys; return rs == null ? "" : rs.radios.alias(id); });
        streaming.rdioSystemLabel(() -> { RadioSystem rs = sys; return rs == null ? "" : rs.name; });
        streaming.feedRadioNames(id -> { RadioSystem rs = sys; return rs == null ? "" : rs.radios.alias(id); });
        if (streaming.any()) streaming.start();
        for (String l : out) log("Streaming: " + l);
        return out;
    }

    /** Stops monitoring and disconnects every stream (STOP button, exit). */
    public synchronized void stop() { stop(false); }

    /** The streams kept running by the last {@link #stop(boolean) stop(true)} (reused by the next start). */
    boolean keptStreams;

    /**
     * Stops monitoring. keepStreams (restart for Apply / switching systems): feeds stay connected to their servers and
     * send silence until the engine runs again, uploads in progress complete; the next {@link #start} only applies what
     * changed (1.4.4). Otherwise every stream is stopped.
     */
    public synchronized void stop(boolean keepStreams) {
        if (!running && ccSource == null) {
            if (!keepStreams && streaming != null) { streaming.stop(); streaming = null; keptStreams = false; }
            return;
        }
        running = false;
        if (house != null) house.shutdownNow();
        for (ActiveCall a : new ArrayList<>(callMap.values())) calls.endCall(a, "engine stopped");
        for (Tuner t : tuners) t.src.stop();
        for (RtlSdr d : dongles) d.stop();
        dongles.clear();
        for (AirspyR2 a : airspys) a.stop();
        airspys.clear();
        for (SoapySdr d : soapys) d.stop();
        soapys.clear();
        if (widePool != null) { widePool.shutdownNow(); widePool = null; }
        tuners.clear();
        ccTuner = null; ccSource = null; ccDecoders.clear(); tdma.clear();
        speaker.stop();
        if (streaming != null) {
            if (keepStreams) keptStreams = true;
            else { streaming.stop(); streaming = null; keptStreams = false; }
        }
        audio.close();
        state = "STOPPED";
        log("Engine stopped");
    }

    static int parseNac(String s) {
        if (s == null || s.isBlank()) return -1;
        try { return Integer.parseInt(s.trim().replaceFirst("(?i)^0x", ""), 16) & 0xfff; } catch (Exception e) { return -1; }
    }

    static String mhz(long hz) { return String.format(Locale.ROOT, "%.5f", hz / 1e6); }

    ChannelDecoder.Modulation modulation() { return ChannelDecoder.Modulation.parse(sys == null ? null : sys.nativeModulation); }

    boolean preferLsm() {
        long now = System.currentTimeMillis();
        for (ChannelDecoder d : ccDecoders) if (d.locked(now) && d.decided()) return d.lsmActive();
        return lsmPreferred;
    }

    /** Frequency offset of tuner t at f: from a locked decoder on it, else what earlier calls measured (NaN if unknown). */
    double tunerAnchor(Tuner t, ChannelDecoder self, long f) {
        long now = System.currentTimeMillis();
        if (t == ccTuner) for (ChannelDecoder o : ccDecoders) if (o.locked(now) && o.freqHz > 0) return o.frequencyError() * f / o.freqHz;
        for (ChannelDecoder o : t.decoders) if (o != self && o.locked(now) && o.freqHz > 0 && o.syncs() > 3) return o.frequencyError() * f / o.freqHz;
        return Double.isNaN(t.ppmErr) ? Double.NaN : t.ppmErr * f / 1e6;
    }

    // ------------------------------------------------------------------ housekeeping
    void housekeeping() {
        watchTuners();
        try { replaceFaultedDecoders(); } catch (Throwable t) { log("replacing decoders: " + t); }
        try {
            long now = System.currentTimeMillis();
            int n; String last;
            synchronized (this) { n = pendingMsgs; pendingMsgs = 0; last = lastMsg; }
            if (n > 0) listener.messages(n, last);
            calls.housekeep(now);
            tdma.refreshScrambler();
            synchronized (this) { cc.plan(now); }
            boolean anyLock = false;
            for (ChannelDecoder d : ccDecoders) if (d.locked(now)) anyLock = true;
            for (ChannelDecoder d : ccDecoders) if (d.locked(now) && d.decided()) {
                String k = d.demod().name();
                if (!k.equals(ccDemodLogged)) {
                    ccDemodLogged = k; lsmPreferred = d.lsmActive();
                    log("Control channel " + mhz(d.freqHz) + ": " + d.demodSummary());
                    if (d.lsmActive() && sys != null && "c4fm".equalsIgnoreCase(sys.nativeModulation))
                        log("This site decodes better as LSM/CQPSK (simulcast). Consider Systems > Demodulator: Auto or LSM.");
                }
                break;
            }
            for (Tuner t : tuners) if (t.src.error() != null && problem == null) { problem = t == ccTuner ? "TUNER ERROR" : "VOICE TUNER ERROR"; log("Tuner T" + t.id + " error: " + t.src.error()); }
            if (mode == Mode.FILE && ccSource != null && !ccSource.running()) { if (!"FILE FINISHED".equals(state)) log("End of I/Q file. Decoded " + msgTotal + " control messages (" + tsbkBad + " failed CRC)."); state = "FILE FINISHED"; return; }
            ActiveCall cur = speaker.speaker;
            int dec = calls.decoding();
            if (cur != null) state = (cur.encrypted ? "ENCRYPTED " : cur.hang ? "HANG " : "VOICE ") + (cur.conventional ? "" : "TG ") + cur.tg + (dec > 1 ? "  +" + (dec - 1) : "");
            else if (dec > 0) state = dec + " CALL" + (dec == 1 ? "" : "S") + " RECORDING";
            else if (now - lastTsbkAt < 3000) state = "CONTROL CHANNEL LOCKED";
            else if (anyLock) state = "SYNC - NO CONTROL DATA";
            else state = "LOOKING FOR CONTROL CHANNEL";
            // no control data for 6 s: the site may have moved to its alternate control channel
            if ((mode == Mode.RTL || huntable) && now - Math.max(lastTsbkAt, Math.max(startedAt, lastHuntAt)) > 6000) cc.hunt(now);
        } catch (Throwable t) { log("housekeeping: " + t); }
    }

    // ------------------------------------------------------------------ public API for the UI
    public void setSpeakerMode(SpeakerMode m, int tg) {
        speaker.setMode(m, tg);
        app.settings.setProperty("native.speaker", m.name());
        app.settings.setProperty("native.holdTg", String.valueOf(tg));
        log("Speaker: " + (m == SpeakerMode.PRIORITY ? "one call at a time by priority" : m == SpeakerMode.MIX ? "mix all calls"
                : m == SpeakerMode.QUEUE ? "queue - every call, one after another (max delay " + speaker.queueMaxDelay() / 1000 + " s)" : "hold TG " + tg));
    }
    public SpeakerMode speakerMode() { return speaker.mode; }

    /** Speaker queue limit in seconds (5-600, default 30). Saved as "native.speakerQueueMaxDelay". Applied live. */
    public int speakerQueueMaxDelay() {
        try { return (int) Math.max(5, Math.min(600, Long.parseLong(app.settings.getProperty("native.speakerQueueMaxDelay", "30").trim()))); } catch (Exception ex) { return 30; }
    }
    public void setSpeakerQueueMaxDelay(int s) {
        s = Math.max(5, Math.min(600, s));
        app.settings.setProperty("native.speakerQueueMaxDelay", String.valueOf(s));
        speaker.setQueueMaxDelay(s * 1000L);
    }
    /** Speaker QUEUE: {calls waiting, ms behind live, calls skipped by the limit}; null in the other modes. */
    public long[] speakerQueueStatus() { return speaker.queueStatus(); }

    // ------------------------------------------------------------------ speaker volume / mute (local speaker ONLY)
    /**
     * Speaker volume 0-100 % (Monitor page). Applied live, and only to the sound card: AudioOut scales its own copy of
     * each frame on its playback thread, after Broadcastify Calls, Feeds, OpenMHz and the recordings have taken the
     * decoded audio unchanged. Saved as "speaker.volume" (1.3.4 and earlier used the Recordings page "volume" setting).
     */
    public int speakerVolume() {
        String v = app.settings.getProperty("speaker.volume", app.settings.getProperty("volume", "80"));
        try { return Math.max(0, Math.min(100, Integer.parseInt(v.trim()))); } catch (Exception e) { return 80; }
    }
    public void setSpeakerVolume(int pct) {
        pct = Math.max(0, Math.min(100, pct));
        app.settings.setProperty("speaker.volume", String.valueOf(pct));
        audio.setVolume(pct / 100f);
    }
    /** Speaker mute (Monitor page, key M). Saved as "speaker.muted". Streams and recordings are not affected. */
    public boolean speakerMuted() { return Boolean.parseBoolean(app.settings.getProperty("speaker.muted", "false")); }
    public void setSpeakerMuted(boolean m) {
        app.settings.setProperty("speaker.muted", String.valueOf(m));
        audio.setMuted(m);
    }
    public int holdTg() { return speaker.holdTg; }

    /** All current calls for the Monitor view: the one on the speaker first. */
    public List<CallView> activeCalls() { return diag.activeCalls(); }

    /**
     * Display state from the audio side: 2 = audio of the call on the speaker is being heard (queued, jitter buffer or
     * still in the sound card's buffer), 1 = that call is in hang time, 0 = idle. Lit until its last frame has played.
     */
    public int displayState() { return speaker.displayState(); }

    /**
     * Control-channel signal level in dB of the control tuner's full scale, or NaN when that tuner is not reporting one.
     * Only the control-channel decoders on the control tuner are read (not a voice or roaming receiver). The number is
     * the channel power those decoders already measure from the samples; RTL-SDR, Airspy and SoapySDR all feed that path.
     */
    public double controlSignalDb() {
        if (!running || ccTuner == null) return Double.NaN;
        ChannelDecoder[] ds = ccDecoders.toArray(new ChannelDecoder[0]);   // one snapshot; a rebuild must not leave a 0 dB hole
        if (ds.length == 0) return Double.NaN;
        long[] hz = new long[ds.length];
        double[] db = new double[ds.length];
        boolean[] lk = new boolean[ds.length];
        long now = System.currentTimeMillis();
        for (int i = 0; i < ds.length; i++) {
            hz[i] = ds[i].freqHz;
            db[i] = ds[i].signalDb;
            lk[i] = ds[i].locked(now);
        }
        return SignalMeter.pick(ccFreqHz, hz, db, lk);
    }

    /** One line for the UI: what is being heard right now, plus how many other calls are recording. */
    public String nowFollowing() {
        ActiveCall c = speaker.speaker;
        int dec = calls.decoding(), others = dec - (c != null && c.vd != null ? 1 : 0);
        String more = others > 0 ? "   (+" + others + " more call" + (others == 1 ? "" : "s") + " decoding)" : "";
        if (c == null) return dec > 0 ? dec + " call" + (dec == 1 ? "" : "s") + " decoding" : "";
        String alias = c.alias().isEmpty() ? "" : " " + c.alias();
        long[] q = speaker.queueStatus();
        if (q != null) {   // QUEUE: the call being played may be behind live
            boolean late = q[1] >= 1500;
            return (late ? String.format(java.util.Locale.ROOT, "QUEUED +%d s  ", Math.round(q[1] / 1000.0)) : "NOW HEARING  ") + (c.conventional ? "" : "TG ") + c.tg + alias + "   " + mhz(c.freq) + " MHz"
                    + (c.src > 0 ? "   RADIO " + radioLabel(c.src) : "") + (q[0] > 0 ? "   (" + q[0] + " waiting)" : "");
        }
        return (c.hang ? "HANG  " : c.encrypted ? "ENCRYPTED  " : "NOW HEARING  ") + (c.conventional ? "" : "TG ") + c.tg + alias + "   " + mhz(c.freq) + " MHz" + (c.src > 0 ? "   RADIO " + radioLabel(c.src) : "") + more;
    }

    /** "EXAMPLE UNIT 7 (123456)" from the system's radio names, else the ID. */
    public String radioLabel(int src) { RadioSystem s = sys; return s == null || src <= 0 ? String.valueOf(src) : s.radioLabel(src); }

    /** Logs who is talking when a call's source radio is learned or changes (one line per transmission). */
    void logTalker(ActiveCall c) {
        if (c.src <= 0 || c.src == c.loggedSrc) return;
        c.loggedSrc = c.src;
        log((c.conventional ? "" : "TG ") + c.tg + (c.alias().isEmpty() ? "" : " (" + c.alias() + ")") + ": radio " + radioLabel(c.src));
    }

    public String[][] voiceDiagnostics() { return diag.voiceDiagnostics(); }
    public synchronized List<String[]> tunerRows() { return diag.tunerRows(); }
    public synchronized Map<String, int[]> suggestions() { return diag.suggestions(); }
    public synchronized List<String> dongleRows() { return diag.dongleRows(); }
    public synchronized String gainAdvice() { return diag.gainAdvice(); }
    public synchronized String ppmAdvice() { return diag.ppmAdvice(); }
    public int peakConcurrent() { return peakConcurrent; }
    public Map<String, Long> missedCounts() { return new TreeMap<>(missed); }
    public long retunes() { return retunes; }
    /** Let a simulated or file source hunt for the control channel like a real dongle (tests). */
    public void setHuntable(boolean on) { huntable = on; }
    /** Test hook: (feed name, talkgroups in each 100 ms feed block). Set after start(). */
    public void setFeedTap(java.util.function.BiConsumer<String, int[]> tap) { Streaming s = streaming; if (s != null) s.feedTap = tap; }
    /** Per tuner: {id, centre Hz, half span Hz, active calls, retunes}. */
    public synchronized List<long[]> tunerWindows() {
        List<long[]> r = new ArrayList<>();
        for (Tuner t : tuners) r.add(new long[]{t.id, t.center, (long) t.halfSpan(), calls.callsOn(t), t.retunes});
        return r;
    }

    public String siteSummary() {
        StringBuilder s = new StringBuilder();
        if (nac >= 0) s.append(String.format("NAC %03X", nac));
        if (wacn >= 0) s.append(String.format("   WACN %05X", wacn));
        if (sysId >= 0) s.append(String.format("   SYSTEM %03X", sysId));
        if (rfss >= 0) s.append("   RFSS ").append(rfss).append("   SITE ").append(site);
        if (ccFreqHz > 0) s.append("   CC ").append(mhz(ccFreqHz));
        return s.length() == 0 ? "waiting for control channel data" : s.toString();
    }

    public List<String> bandPlan() {
        List<String> out = new ArrayList<>();
        List<ChannelMap.Band> b = new ArrayList<>(map.bands());
        b.sort(Comparator.comparingInt(x -> x.id));
        for (ChannelMap.Band x : b) out.add(String.format(Locale.ROOT, "Band %2d: base %s MHz, spacing %.2f kHz, %s", x.id, mhz(x.baseHz), x.spacingHz / 1000.0,
                x.slots > 1 ? "TDMA (Phase 2) " + x.slots + " slots" : "FDMA (Phase 1)"));
        return out;
    }

    /** Health counters for the Simple view's status lights (read-only, 1.4.5). */
    public EngineHealth health() { return EngineHealth.of(this); }
    public List<String> streamStatus() { return streaming == null ? List.of() : streaming.status(); }

    /** Feed calibration: sends the test tone on the feed with this name; returns false if no such feed is running. */
    public boolean feedTestTone(String name) { Streaming s = streaming; return s != null && s.testTone(name) > 0; }

    /** Send test tag (1.5.1): a test now-playing title on the running feed with this name; null if no such feed is running. */
    public java.util.concurrent.CompletableFuture<String> feedTestTag(String name, String text) { Streaming s = streaming; return s == null ? null : s.testTag(name, text); }

    /** Recent Broadcastify Calls uploads of the stream with this name (newest first), or empty. */
    public List<String> streamRecent(String name) {
        Streaming s = streaming;
        if (s != null) for (BroadcastifyCalls c : s.broadcastifyCalls()) if (c.cfg.name.equals(name)) return c.recent();
        return List.of();
    }

    /** The running Broadcastify Calls uploaders (tests). */
    public List<BroadcastifyCalls> broadcastifyCalls() { Streaming s = streaming; return s == null ? List.of() : s.broadcastifyCalls(); }
}
