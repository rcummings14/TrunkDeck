package p25monitor.nativeengine;

import p25monitor.sdr.ProcessingGuard;
import java.util.*;

/**
 * A read-only snapshot of the engine's health counters for the Simple view's status lights (1.4.5). Only reads
 * counters the engine and the streams already keep; it never changes anything.
 */
public final class EngineHealth {
    public boolean running;
    public String state = "", problem = "";
    /** Receiver: tuners, sample-processing restarts (all sources), USB blocks dropped, of them while processing was stopped. */
    public int tuners;
    public long restarts, lastRestartAt, droppedBlocks, droppedWhileStopped, usbErrors;
    public boolean processingStopped;
    public String lastFailure = "";
    /** Control channel: last good control message (ms), messages so far. */
    public long lastCcMessageAt, ccMessages;
    /** Conventional system: no control channel. {@code lastChannelAt} is the last P25 frame on a saved channel. */
    public boolean conventional;
    public long lastChannelAt;

    public enum Kind { FEED, CALLS, OPENMHZ, RDIO }
    public static final class Stream {
        public Kind kind; public String name = "";
        // feeds
        public boolean connected; public long connectedSince, reconnects; public String state = "", lastDrop = "";
        // per-call uploads
        public long ok, duplicate, failed, dropped, lastOk; public String lastError = "";
    }
    public final List<Stream> streams = new ArrayList<>();

    static EngineHealth of(NativeEngine e) {
        EngineHealth h = new EngineHealth();
        h.running = e.running;
        h.state = String.valueOf(e.state);
        h.problem = e.problem == null ? "" : e.problem;
        h.lastCcMessageAt = e.lastTsbkAt;
        h.ccMessages = e.msgTotal;
        h.conventional = e.conventional;
        if (e.conventional) {
            long last = 0;
            for (Tuner t : e.tuners) for (ChannelDecoder d : e.decodersOn(t)) last = Math.max(last, d.lastFrameAt);
            h.lastChannelAt = last;
        }
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Tuner t : e.tuners) {
            h.tuners++;
            if (!seen.add(t.src)) continue;   // tuners of one wideband source share it
            ProcessingGuard g = t.src.guard();
            if (g != null) {
                h.restarts += g.restarts; h.lastRestartAt = Math.max(h.lastRestartAt, g.lastRestartAt); h.droppedWhileStopped += g.droppedWhileStopped;
                if (!g.lastFailure.isEmpty()) h.lastFailure = g.lastFailure;
                if (t.src.running() && (System.nanoTime() - g.lastProgressNanos) / 1_000_000L > 2000) h.processingStopped = true;
            }
            var us = t.src.usbStream();
            if (us != null) { h.droppedBlocks += us.droppedBlocks; h.usbErrors += us.usbErrors; }
            if (t.src instanceof p25monitor.sdr.RtlSdr d) h.droppedBlocks += d.droppedBlocks;
            if (t.src instanceof p25monitor.sdr.SoapySdr d) h.droppedBlocks += d.droppedBlocks;
        }
        Streaming st = e.streaming;
        if (st != null) {
            for (Streaming.Feed f : st.feeds) {
                Stream s = new Stream(); s.kind = Kind.FEED; s.name = f.cfg.name;
                s.connectedSince = f.connectedSince; s.connected = f.connectedSince > 0; s.reconnects = f.drops; s.state = f.state; s.lastDrop = f.lastDrop;
                h.streams.add(s);
            }
            for (BroadcastifyCalls c : st.calls) {
                Stream s = new Stream(); s.kind = Kind.CALLS; s.name = c.cfg.name;
                s.ok = c.ok; s.duplicate = c.duplicate; s.failed = c.failed; s.dropped = c.dropped; s.lastError = c.lastError;
                h.streams.add(s);
            }
            for (OpenMhzUploader o : st.openmhz) {
                Stream s = new Stream(); s.kind = Kind.OPENMHZ; s.name = o.cfg.name;
                s.ok = o.uploaded; s.failed = o.failed; s.dropped = o.dropped; s.lastOk = o.lastOk; s.lastError = o.lastError;
                h.streams.add(s);
            }
            for (RdioScannerUploader r : st.rdio) {
                Stream s = new Stream(); s.kind = Kind.RDIO; s.name = r.cfg.name;
                s.ok = r.uploaded; s.failed = r.failed; s.dropped = r.dropped; s.lastOk = r.lastOk; s.lastError = r.lastError;
                h.streams.add(s);
            }
        }
        return h;
    }
}
