package p25monitor.dmr;

import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.decimate.DecimationFilterFactory;
import io.github.dsheirer.dsp.filter.decimate.IRealDecimationFilter;
import io.github.dsheirer.dsp.filter.fir.real.IRealFilter;
import io.github.dsheirer.dsp.filter.fir.real.RealFIRFilter;
import io.github.dsheirer.dsp.fm.FmDemodulatorFactory;
import io.github.dsheirer.dsp.fm.IDemodulator;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.FeedbackDecoder;
import io.github.dsheirer.module.decode.dmr.DMRCrcMaskManager;
import io.github.dsheirer.module.decode.dmr.DMRMessageFramer;
import io.github.dsheirer.module.decode.dmr.DMRMessageProcessor;
import io.github.dsheirer.module.decode.dmr.DMRSymbolProcessor;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.channel.DMRAbsoluteChannel;
import io.github.dsheirer.module.decode.dmr.channel.DMRChannel;
import io.github.dsheirer.module.decode.dmr.message.IServiceOptionsProvider;
import io.github.dsheirer.module.decode.dmr.message.data.DataMessage;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.CSBKMessage;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.Opcode;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.CapacityPlusNeighbors;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.CapacityPlusSiteStatus;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.motorola.ConnectPlusVoiceChannelUser;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.hytera.HyteraXPTSiteState;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.grant.BroadcastTalkgroupVoiceChannelGrant;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.grant.ChannelGrant;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.grant.DuplexPrivateVoiceChannelGrant;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.grant.PrivateVoiceChannelGrant;
import io.github.dsheirer.module.decode.dmr.message.data.csbk.standard.grant.TalkgroupVoiceChannelGrant;
import io.github.dsheirer.module.decode.dmr.message.data.header.PiHeader;
import io.github.dsheirer.module.decode.dmr.message.data.header.VoiceHeader;
import io.github.dsheirer.module.decode.dmr.message.data.lc.LCMessage;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.TalkerAliasComplete;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.motorola.CapacityPlusWideAreaVoiceChannelUser;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.hytera.HyteraXptChannelGrant;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.CapacityPlusRestChannel;
import io.github.dsheirer.module.decode.dmr.message.data.lc.shorty.HyteraXPTChannel;
import io.github.dsheirer.module.decode.dmr.message.data.terminator.Terminator;
import io.github.dsheirer.module.decode.dmr.message.type.ServiceOptions;
import io.github.dsheirer.module.decode.dmr.message.voice.VoiceEMBMessage;
import io.github.dsheirer.module.decode.dmr.message.voice.VoiceMessage;
import java.util.List;

/**
 * DMR channel decoder: half-band decimation to 24 kHz, baseband low-pass filter, FM discriminator, root raised cosine
 * matched filter, 4FSK symbol processor (sync detection, timing and equalizer), burst framer and message processor
 * (BPTC, embedded LC, CSBK, multi-block control, CRC masks). The decoding chain is Dennis Sheirer's
 * (GPL-3.0-or-later), included as source in io.github.dsheirer - see README-TRUNKDECK.txt there. This class turns its
 * messages into {@link Listener} events, so calls, grants and audio take TrunkDeck's usual paths.
 *
 * <p>Voice frames are the raw 72-bit AMBE+2 words, corrected and concealed by {@link DmrAmbe.Concealer}.
 * Trunking follows the system's flavour ({@link DmrTrunk}): Tier III and Connect Plus grants are reported,
 * Capacity Plus rest channel and Hytera XPT free repeater changes are reported, and an XPT channel grant names the
 * repeater a call has gone to. Untested on air.
 */
public final class DmrDecoder {
    /** What the decoder reports. Calls are per timeslot (1 or 2). */
    public interface Listener {
        void group(long hz, int slot, int colorCode, int talkgroup, int source, byte[][] ambe);
        void unit(long hz, int slot, int colorCode, int target, int source);
        /** Voice frames of a clear private (unit-to-unit) call. Same frames as {@link #group}. */
        default void unitAudio(long hz, int slot, int colorCode, int target, int source, byte[][] ambe) {}
        void encrypted(long hz, int slot, int colorCode);
        void end(long hz, int slot);
        void grant(DmrTrunk.Grant grant);
        void rest(int repeater, int slot, long hz, boolean moved);
        void alias(int source, String text);
        /** A CRC-good control block. This is what turns the status to LIVE, the way a P25 TSBK does. */
        default void control(String text) {}
        /** A control block failed its CRC. */
        default void controlError() {}
        /** The call on this slot has its emergency bit set (voice link control). */
        default void emergency(long hz, int slot) {}
        /**
         * Hytera XPT channel grant: a call for {@code talkgroup} is on repeater {@code repeater} ({@code hz}, 0 when that
         * repeater is not listed). Its timeslot is not in the message; the call itself starts when that repeater is heard.
         */
        default void xptCall(int repeater, long hz, int talkgroup, int source) {}
    }

    private static final int SYMBOL_RATE = 4800;
    private static final double RECEIVE_FILTER_ALPHA = 0.2;
    private static final int RECEIVE_FILTER_SYMBOL_SPAN = 12;
    private static final double BASEBAND_PASS_HZ = 5100.0, BASEBAND_STOP_HZ = 6500.0;
    /** How long after the last valid burst the decoder still counts as synchronised (input samples). */
    private static final long SYNC_HOLD_SAMPLES = 48_000;

    private final long hz;
    private final int wantColor, wantSlot;
    private final DmrTrunk trunk;
    private final long baseHz;
    private final List<Long> channels;
    private final Listener out;

    private final IRealDecimationFilter decimateI, decimateQ;
    private final IRealFilter basebandI, basebandQ, receive;
    private final IDemodulator demodulator;
    private final DMRMessageFramer framer;
    private final DMRSymbolProcessor symbols;
    private final DMRMessageProcessor processor;
    private float carryI, carryQ;
    private boolean carry;

    /** Index 1 and 2. Slot 0 (CACH short LC) has no call state. */
    private final Slot[] slots = { null, new Slot(), new Slot() };
    private long samples, lastValidAt = -SYNC_HOLD_SAMPLES;
    private long messages, valid, colourDrops, slotDrops, csbkOk, csbkBad, voiceBursts;
    private volatile double carrierHz = Double.NaN;
    /** The message being handled had its colour code checked (or any colour code is wanted). */
    private boolean colourChecked;

    /**
     * @param hz         channel frequency
     * @param colorCode  wanted colour code, or -1 for any
     * @param timeslot   1 or 2 to hear only that timeslot, 0 for both
     * @param trunk      trunking state (flavour, rest channel, Tier III channel table), or null on a conventional channel
     * @param baseHz     first listed frequency (Tier III logical channel 0)
     * @param channels   the system's listed frequencies (Connect Plus / Capacity Plus repeater N is the Nth)
     * @param sampleRate rate of the complex samples given to {@link #baseband} (48000 from TrunkDeck's channelizer)
     */
    public DmrDecoder(long hz, int colorCode, int timeslot, DmrTrunk trunk, long baseHz, List<Long> channels,
                      Listener out, double sampleRate) {
        this.hz = hz;
        this.wantColor = colorCode;
        this.wantSlot = timeslot;
        this.trunk = trunk;
        this.baseHz = baseHz;
        this.channels = channels;
        this.out = out;

        // The same front end as the reference DMR decoder's setSampleRate(): decimate to about 5 samples per symbol.
        float samplesPerSymbol = (float) sampleRate / SYMBOL_RATE;
        if (samplesPerSymbol <= 4.0f) throw new IllegalArgumentException("sample rate " + sampleRate + " is below " + SYMBOL_RATE * 4);
        int decimation = 1;
        while (samplesPerSymbol >= 10.0f) { decimation *= 2; samplesPerSymbol /= 2; }
        decimateI = DecimationFilterFactory.getRealDecimationFilter(decimation);
        decimateQ = DecimationFilterFactory.getRealDecimationFilter(decimation);
        double rate = sampleRate / decimation;
        receive = new RealFIRFilter(FilterFactory.getRootRaisedCosineFilter(rate, SYMBOL_RATE, RECEIVE_FILTER_ALPHA, RECEIVE_FILTER_SYMBOL_SPAN));
        float[] taps = FilterFactory.getRemezLowPass(rate, BASEBAND_PASS_HZ, BASEBAND_STOP_HZ);
        basebandI = FilterFactory.getRealFilter(taps);
        basebandQ = FilterFactory.getRealFilter(taps.clone());
        demodulator = FmDemodulatorFactory.getFmDemodulator();

        Feedback feedback = new Feedback(rate);
        feedback.setFrequencyCorrectionListener(f -> carrierHz = f);
        DMRCrcMaskManager crc = new DMRCrcMaskManager(false);
        framer = new DMRMessageFramer(crc);
        symbols = new DMRSymbolProcessor(feedback, framer);
        // A trunked system's channels are repeaters (base station bursts only); a conventional channel may be direct mode.
        if (trunk != null) symbols.setBaseStationMode(true);
        processor = new DMRMessageProcessor(new DecodeConfigDMR(), crc);
        processor.setMessageListener(this::onMessage);
        framer.setListener(processor);
        symbols.setSamplesPerSymbol(samplesPerSymbol);
        framer.start();
    }

    /** Opens the protected sample rate setter. */
    private static final class Feedback extends FeedbackDecoder {
        Feedback(double rate) { setDecimatedSampleRate(rate); }
    }

    public long hz() { return hz; }

    public void baseband(float[] i, float[] q, int n) {
        if (n <= 0) return;
        // the half-band decimators take an even number of samples: one odd sample waits for the next block.
        int have = n + (carry ? 1 : 0);
        int even = have & ~1;
        float[] bi = new float[even], bq = new float[even];
        int k = 0, from = 0;
        if (carry && even > 0) { bi[0] = carryI; bq[0] = carryQ; k = 1; carry = false; }
        for (; k < even; k++, from++) { bi[k] = i[from]; bq[k] = q[from]; }
        if (from < n) { carryI = i[from]; carryQ = q[from]; carry = true; }
        samples += n;
        if (even == 0) return;
        framer.setTimestamp(System.currentTimeMillis());
        float[] di = basebandI.filter(decimateI.decimateReal(bi));
        float[] dq = basebandQ.filter(decimateQ.decimateReal(bq));
        symbols.process(receive.filter(demodulator.demodulate(di, dq)));
    }

    public boolean synced() { return samples - lastValidAt < SYNC_HOLD_SAMPLES; }

    /**
     * The carrier's offset from the channel centre (Hz, positive = signal above), as the symbol processor measures it
     * while decoding; NaN before it has. The processor reports the correction it applies, the opposite sign.
     */
    public double carrierErrorHz() { double c = carrierHz; return Double.isNaN(c) ? Double.NaN : -c; }

    public String status() {
        return String.format(java.util.Locale.ROOT, "dmr messages=%d valid=%d voice=%d colourDrop=%d slotDrop=%d csbk ok=%d bad=%d carrier=%s",
                messages, valid, voiceBursts, colourDrops, slotDrops, csbkOk, csbkBad,
                Double.isNaN(carrierHz) ? "-" : String.format(java.util.Locale.ROOT, "%+.0fHz", carrierHz));
    }

    public void prime(int slot, int talkgroup, int source) {
        if (slot != 1 && slot != 2) return;
        Slot s = slots[slot];
        s.primedTg = talkgroup;
        if (s.active && s.group && s.target == talkgroup) {
            if (source > 0 && s.source <= 0) s.source = source;
            return;
        }
        s.start(true, talkgroup, source);
    }

    // ------------------------------------------------------------------ decoded messages -> listener events

    private void onMessage(IMessage m) {
        messages++;
        if (m == null) return;
        int slot = m.getTimeslot();
        int cc = colourCode(m, slot);
        if (wantColor >= 0 && cc >= 0 && cc != wantColor) { colourDrops++; return; }
        if ((wantSlot == 1 || wantSlot == 2) && (slot == 1 || slot == 2) && slot != wantSlot) { slotDrops++; return; }
        // Only a message whose colour code was checked keeps the channel synced. A CACH short LC carries none, so a
        // neighbouring repeater's CACH would otherwise keep a channel that has drifted onto it looking locked.
        colourChecked = wantColor < 0 || cc >= 0;
        if (m instanceof CSBKMessage csbk) { onCsbk(csbk, slot); return; }
        if (!m.isValid()) return;
        valid++;
        if (colourChecked) lastValidAt = samples;
        if (m instanceof VoiceMessage voice) { onVoice(voice, slot, cc); return; }
        if (m instanceof PiHeader) { encrypted(slot, cc); return; }
        if (m instanceof VoiceHeader header) { onLc(header.getLCMessage(), slot, cc, true); return; }
        if (m instanceof Terminator t) {
            LCMessage lc = t.getLCMessage();
            if (lc != null && lc.isValid()) onLc(lc, slot, cc, false);
            if (slot == 1 || slot == 2) end(slot);
            return;
        }
        if (m instanceof LCMessage lc) onLc(lc, slot, cc, false);
    }

    /** The burst's colour code, remembered per slot: the voice sync burst and the CACH short LC carry none. */
    private int colourCode(IMessage m, int slot) {
        int cc = -1;
        if (m instanceof DataMessage d && d.getSlotType() != null && d.getSlotType().isValid()) cc = d.getSlotType().getColorCode();
        else if (m instanceof VoiceEMBMessage v && v.getEMB() != null && v.getEMB().isValid()) cc = v.getEMB().getColorCode();
        if (slot == 1 || slot == 2) {
            if (cc >= 0) slots[slot].colour = cc;
            else if (m instanceof VoiceMessage) cc = slots[slot].colour;
        }
        return cc;
    }

    private void onVoice(VoiceMessage voice, int slot, int cc) {
        if (slot != 1 && slot != 2) return;
        voiceBursts++;
        Slot s = slots[slot];
        // The EMB privacy indicator (protected by its quadratic residue code) marks an encrypted transmission.
        if (voice instanceof VoiceEMBMessage v && v.getEMB() != null && v.getEMB().isValid() && v.getEMB().isEncrypted()) {
            if (!s.privacy) encrypted(slot, cc);
            return;
        }
        if (!s.active || s.privacy || out == null) return;
        List<byte[]> raw = voice.getAMBEFrames();
        if (raw == null || raw.isEmpty()) return;
        byte[][] frames = new byte[raw.size()][];
        for (int k = 0; k < frames.length; k++) frames[k] = s.concealer.process(raw.get(k));
        int colour = cc >= 0 ? cc : wantColor;
        if (s.group) out.group(hz, slot, colour, s.target, s.source, frames);
        else out.unitAudio(hz, slot, colour, s.target, s.source, frames);
    }

    /** Voice channel user link control (header, embedded or terminator), talker alias and Capacity Plus rest channel. */
    private void onLc(LCMessage lc, int slot, int cc, boolean header) {
        if (lc == null || !lc.isValid()) return;
        if (lc instanceof CapacityPlusRestChannel r) { rest(r.getRestChannel()); return; }
        // Hytera XPT: the CACH short LC names the free repeater (where the next call starts); 0 = all busy
        if (lc instanceof HyteraXPTChannel x) { xptFree(x.getFreeRepeater()); return; }
        if (lc instanceof HyteraXptChannelGrant g) { xptGrant(g); return; }
        if (lc instanceof CapacityPlusWideAreaVoiceChannelUser w) rest(w.getRestChannel());
        if (slot != 1 && slot != 2) return;
        Slot s = slots[slot];
        if (lc instanceof TalkerAliasComplete alias) {
            if (alias.hasTalkerAliasIdentifier()) { s.aliasText = String.valueOf(alias.getTalkerAliasIdentifier().getValue()); flushAlias(s); }
            return;
        }
        if (!lc.getOpcode().name().contains("VOICE_CHANNEL_USER")) return;
        int[] ids = ids(lc.getIdentifiers());
        int talkgroup = ids[0], to = ids[1], from = ids[2];
        boolean group = talkgroup > 0 || to <= 0;
        int target = group ? talkgroup : to;
        if (target <= 0) return;
        ServiceOptions options = lc instanceof IServiceOptionsProvider p ? p.getServiceOptions() : null;
        if (header) s.privacy = false;   // a voice LC header starts a transmission; its PI header, if any, follows
        if (options != null && options.isEncrypted()) { encrypted(slot, cc); return; }
        if (s.privacy) return;
        boolean fresh = !s.active || s.group != group || s.target != target || (from > 0 && s.source != from);
        if (fresh) s.start(group, target, from > 0 ? from : s.target == target ? s.source : -1);
        flushAlias(s);
        if (out == null) return;
        if (options != null && options.isEmergency()) out.emergency(hz, slot);
        int colour = cc >= 0 ? cc : wantColor;
        if (fresh || header) {
            if (group) out.group(hz, slot, colour, target, s.source, new byte[0][]);
            else out.unit(hz, slot, colour, target, s.source);
        }
    }

    private void onCsbk(CSBKMessage csbk, int slot) {
        if (!csbk.isValid()) { csbkBad++; if (out != null) out.controlError(); return; }
        csbkOk++;
        valid++;
        if (colourChecked) lastValidAt = samples;
        if (out == null) return;
        out.control(csbk.toString());
        if (csbk.getOpcode() == Opcode.STANDARD_CLEAR) {
            // Tier III P_CLEAR on a payload channel: the call on this slot is over
            if ((slot == 1 || slot == 2) && slots[slot].active) end(slot);
            return;
        }
        if (csbk instanceof CapacityPlusSiteStatus status) { rest(status.getRestChannel()); return; }
        if (csbk instanceof CapacityPlusNeighbors neighbors) { rest(neighbors.getRestChannel()); return; }
        if (csbk instanceof HyteraXPTSiteState site) { xptFree(site.getFreeRepeater()); return; }
        if (trunk == null) return;
        if (csbk instanceof ConnectPlusVoiceChannelUser user && trunk.flavor == DmrTrunk.Flavor.CONNECT_PLUS) {
            DMRChannel ch = user.getChannel();
            int tg = user.getTalkgroup() == null ? 0 : user.getTalkgroup().getValue();
            int src = user.getRadio() == null ? 0 : user.getRadio().getValue();
            grant(new DmrTrunk.Grant(tg, src, ch.getChannelNumber(), ch.getTimeslot(), DmrTrunk.lcnFrequency(ch.getChannelNumber(), channels)));
            return;
        }
        if (csbk instanceof ChannelGrant g && trunk.flavor == DmrTrunk.Flavor.TIER3) {
            boolean emergency;
            if (g instanceof TalkgroupVoiceChannelGrant t) emergency = t.isEmergency();
            else if (g instanceof BroadcastTalkgroupVoiceChannelGrant t) emergency = t.isEmergency();
            else if (g instanceof PrivateVoiceChannelGrant t) emergency = t.isEmergency();
            else if (g instanceof DuplexPrivateVoiceChannelGrant t) emergency = t.isEmergency();
            else return;   // data channel grants are not followed
            int[] ids = ids(g.getIdentifiers());
            boolean group = ids[0] > 0;
            int target = group ? ids[0] : ids[1];
            DMRChannel ch = g.getChannel();
            if (ch == null || target <= 0) return;
            long down;
            if (ch instanceof DMRAbsoluteChannel a) {
                // Absolute channel parameters (two-block MBC): the downlink is the frequency nearer the control channel.
                long tx = a.getUplinkFrequency(), rx = a.getDownlinkFrequency();
                down = tx > 0 && Math.abs(tx - hz) < Math.abs(rx - hz) ? tx : rx;
                if (down <= 0 || Math.abs(down - hz) > 50_000_000L) return;
                trunk.lpcnHz.put(ch.getChannelNumber(), down);
            } else {
                down = trunk.tier3Hz(ch.getChannelNumber(), baseHz);
            }
            grant(new DmrTrunk.Grant(target, ids[2], ch.getChannelNumber(), ch.getTimeslot(), down, group, emergency));
        }
    }

    private void grant(DmrTrunk.Grant g) {
        trunk.lastGrant = g;
        out.grant(g);
    }

    /** Capacity Plus / XPT rest channel: repeater N (counting from 1) is the Nth listed frequency. */
    private void rest(DMRChannel rest) {
        if (rest == null || trunk == null || trunk.flavor != DmrTrunk.Flavor.CAPACITY_PLUS) return;
        rest(rest.getChannelNumber(), rest.getTimeslot());
    }

    /** Hytera XPT free repeater (counting from 1; 0 = every repeater busy). It names a repeater, not a timeslot. */
    private void xptFree(int repeater) {
        if (repeater <= 0 || trunk == null || trunk.flavor != DmrTrunk.Flavor.XPT) return;
        rest(repeater, 0);
    }

    /** Hytera XPT channel grant (full LC): the call is on that repeater now. */
    private void xptGrant(HyteraXptChannelGrant g) {
        if (trunk == null || trunk.flavor != DmrTrunk.Flavor.XPT || out == null) return;
        int repeater = g.getChannel();
        if (repeater <= 0) return;
        int tg = g.getTalkgroup() == null ? 0 : g.getTalkgroup().getValue();
        int src = g.getSourceRadio() == null ? 0 : g.getSourceRadio().getValue();
        out.xptCall(repeater, DmrTrunk.lcnFrequency(repeater, channels), tg, src);
    }

    private void rest(int repeater, int slot) {
        if (out == null) return;
        boolean moved = repeater != trunk.restRepeater || slot != trunk.restSlot;
        trunk.restRepeater = repeater;
        trunk.restSlot = slot;
        trunk.restHz = DmrTrunk.lcnFrequency(repeater, channels);
        trunk.restMoved = moved;
        out.rest(repeater, slot, trunk.restHz, moved);
    }

    private void encrypted(int slot, int cc) {
        if (slot != 1 && slot != 2) return;
        Slot s = slots[slot];
        s.active = false;
        s.privacy = true;
        if (out != null) out.encrypted(hz, slot, cc >= 0 ? cc : wantColor);
    }

    private void end(int slot) {
        slots[slot].end();
        if (out != null) out.end(hz, slot);
    }

    private void flushAlias(Slot s) {
        if (s.aliasText == null || out == null || !s.active || s.source <= 0 || s.aliasText.equals(s.aliasSent)) return;
        out.alias(s.source, s.aliasText);
        s.aliasSent = s.aliasText;
        s.aliasText = null;
    }

    /** {talkgroup (TO), radio (TO), radio (FROM)}, 0 when absent. */
    private static int[] ids(List<Identifier> list) {
        int[] r = new int[3];
        if (list == null) return r;
        for (Identifier id : list) {
            if (id instanceof TalkgroupIdentifier t && id.getRole() == Role.TO && t.getValue() != null) r[0] = t.getValue();
            else if (id instanceof RadioIdentifier radio && radio.getValue() != null) {
                if (id.getRole() == Role.TO) r[1] = radio.getValue();
                else if (id.getRole() == Role.FROM) r[2] = radio.getValue();
            }
        }
        return r;
    }

    private static final class Slot {
        boolean active, group, privacy;
        int target, source = -1, primedTg, colour = -1;
        String aliasText, aliasSent;
        final DmrAmbe.Concealer concealer = new DmrAmbe.Concealer();

        void start(boolean group, int target, int source) {
            if (!active || this.source != source) concealer.reset();
            active = true;
            this.group = group;
            this.target = target;
            this.source = source;
        }

        void end() {
            active = false;
            privacy = false;
            target = 0;
            source = -1;
            concealer.reset();
            aliasText = null;
            aliasSent = null;
        }
    }
}
