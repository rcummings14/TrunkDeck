package p25monitor.nxdn;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.dsp.filter.FilterFactory;
import io.github.dsheirer.dsp.filter.decimate.DecimationFilterFactory;
import io.github.dsheirer.dsp.filter.decimate.IRealDecimationFilter;
import io.github.dsheirer.dsp.filter.fir.real.IRealFilter;
import io.github.dsheirer.dsp.filter.fir.real.RealFIRFilter;
import io.github.dsheirer.dsp.fm.FmDemodulatorFactory;
import io.github.dsheirer.dsp.fm.IDemodulator;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.FeedbackDecoder;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.NXDNMessageFramer;
import io.github.dsheirer.module.decode.nxdn.NXDNMessageProcessor;
import io.github.dsheirer.module.decode.nxdn.NXDNSymbolProcessor;
import io.github.dsheirer.module.decode.nxdn.layer2.LICH;
import io.github.dsheirer.module.decode.nxdn.layer2.RFChannel;
import io.github.dsheirer.module.decode.nxdn.layer2.SACCHFragment;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNLayer3Message;
import io.github.dsheirer.module.decode.nxdn.layer3.call.Audio;
import io.github.dsheirer.module.decode.nxdn.layer3.type.TransmissionMode;
import java.util.List;

/**
 * NXDN channel decoder for a 48 kHz complex channel. The decoding chain is SDRTrunk's (Dennis Sheirer,
 * GPL-3.0-or-later), included as source in io.github.dsheirer - see README-TRUNKDECK.txt there: decimation, baseband
 * low-pass filter, FM discriminator, root raised cosine (inverse sinc) matched filter, 4FSK symbol processor with sync
 * detection, timing and equalizer, then the frame decoder (LICH, CAC, SACCH, FACCH1, convolutional code, CRC,
 * descrambling) and message processor (SACCH superframe assembly, layer 3 messages).
 *
 * <p>NXDN48 (2400 symbols/s) and NXDN96 (4800 symbols/s) both run; the first to decode a CRC-good layer 3 message
 * owns the channel and only its calls and voice are passed on, so no setting is needed. Voice is passed on only while
 * the owner keeps decoding CRC-good messages (FACCH1, SACCH superframes or CAC), since a voice frame alone can pass its
 * Golay check by chance. Layer 3 messages are handed on as
 * {@link NxdnMessage} (the octet layout is the standard's, the same on both sides), so call handling, Type-C channel
 * resolution and the message log are unchanged. Voice frames are the raw 72-bit AMBE+2 words. Untested on air.
 */
public final class NxdnDecoder {
    /** What the decoder reports. */
    public interface Listener {
        /** A voice call header or assignment. {@code control}: it came from a control channel (an assignment). */
        void call(NxdnMessage m, boolean control);
        /** Two or four 9-byte AMBE+2 frames, in order. */
        void voice(byte[][] frames);
        /** Transmission release or disconnect, or lost sync during a call ({@code m} is then null). */
        void release(NxdnMessage m);
        /** Every decoded layer 3 message, for the message log. */
        void message(NxdnMessage m, String text);
    }

    public static final int FS = 48000;
    private static final double RECEIVE_FILTER_ALPHA = 0.2;
    /** How long the owning mode keeps the channel without a CRC-good message (input samples). */
    private static final long HOLD_SAMPLES = FS;

    private final Listener out;
    private final Lane[] lanes;
    private Lane owner;
    private long samples;
    private int callSrc = -1, callDst = -1;
    private long messages, voiceFrames;

    public NxdnDecoder(Listener out) {
        this.out = out;
        lanes = new Lane[]{new Lane(TransmissionMode.M4800, "NXDN48"), new Lane(TransmissionMode.M9600, "NXDN96")};
    }

    /** True while one mode is decoding NXDN frames. */
    public synchronized boolean synced() { return owner != null && samples - owner.lastValid < HOLD_SAMPLES; }

    /** "NXDN48" or "NXDN96" while synced, else null. */
    public synchronized String mode() { return synced() ? owner.name : null; }

    public synchronized String status() {
        return "nxdn " + (owner == null ? "searching" : owner.name) + " messages=" + messages + " voice frames=" + voiceFrames;
    }

    public synchronized void baseband(float[] i, float[] q, int n) {
        if (n <= 0) return;
        for (Lane l : lanes) if (owner == null || owner == l || !synced()) l.baseband(i, q, n);
        samples += n;
        if (owner != null && !synced()) { owner = null; endCall(null); }
    }

    // ------------------------------------------------------------------ decoded messages -> listener events

    private void onMessage(Lane lane, IMessage m) {
        if (m == null || !m.isValid()) return;
        // A CRC-good SACCH fragment keeps the owner synced through a long call; it does not claim the channel.
        if (m instanceof SACCHFragment) { if (owner == lane) lane.lastValid = samples; return; }
        boolean voice = m instanceof Audio;
        if (!voice && !(m instanceof NXDNLayer3Message)) return;
        if (voice) {
            // An AMBE frame can pass its Golay check by chance, so voice never claims or holds the channel: it is
            // passed on only while the mode that owns it is synced on CRC-checked messages.
            if (owner != lane || !synced()) return;
            List<byte[]> frames = ((Audio) m).getAudioFrames();
            if (frames.isEmpty()) return;
            voiceFrames += frames.size();
            out.voice(frames.toArray(new byte[0][]));
            return;
        }
        lane.lastValid = samples;
        if (owner == null || owner != lane && !synced()) owner = lane;
        if (owner != lane) return;
        NXDNLayer3Message l3 = (NXDNLayer3Message) m;
        CorrectedBinaryMessage cbm = l3.getMessage();
        if (cbm == null || cbm.size() < 8) return;
        boolean[] bits = new boolean[cbm.size()];
        for (int k = 0; k < bits.length; k++) bits[k] = cbm.get(k);
        LICH lich = l3.getLICH();
        boolean control = lich != null && lich.getRFChannel() == RFChannel.RCCH;
        messages++;
        handle(new NxdnMessage(bits, control));
    }

    private void handle(NxdnMessage m) {
        if (m.isAssignment()) {
            out.message(m, m.describe(0));
            out.call(m, true);
            return;
        }
        if (m.isCall()) {
            if (m.source() != callSrc || m.destination() != callDst) {
                callSrc = m.source(); callDst = m.destination();
                out.message(m, m.describe(0));
                out.call(m, false);
            }
            return;
        }
        if (!m.control && m.isRelease()) { out.message(m, m.describe(0)); endCall(m); return; }
        if (m.type != NxdnMessage.IDLE) out.message(m, m.describe(0));
    }

    private void endCall(NxdnMessage m) {
        if (callSrc < 0) return;
        callSrc = callDst = -1;
        out.release(m);
    }

    /** One transmission mode's demodulator and frame decoder. */
    private final class Lane {
        final String name;
        final int decimation;
        final IRealDecimationFilter decimateI, decimateQ;
        final IRealFilter basebandI, basebandQ, receive;
        final IDemodulator demodulator = FmDemodulatorFactory.getFmDemodulator();
        final NXDNSymbolProcessor symbols;
        /** Samples held back so every block given to the decimators is a whole number of output samples. */
        final float[] holdI, holdQ;
        int held;
        long lastValid = Long.MIN_VALUE / 2;

        Lane(TransmissionMode mode, String name) {
            this.name = name;
            DecodeConfigNXDN config = new DecodeConfigNXDN(mode);
            // The same front end as the reference decoder's setSampleRate(): decimate to under 10 samples per symbol.
            float samplesPerSymbol = (float) FS / mode.getSymbolRate();
            int d = 1;
            while (samplesPerSymbol >= 10.0f) { d *= 2; samplesPerSymbol /= 2; }
            decimation = d;
            holdI = new float[d];
            holdQ = new float[d];
            decimateI = DecimationFilterFactory.getRealDecimationFilter(d);
            decimateQ = DecimationFilterFactory.getRealDecimationFilter(d);
            double rate = (double) FS / d;
            receive = new RealFIRFilter(FilterFactory.getRootRaisedCosineInverseSincFilter(rate, mode.getSymbolRate(), RECEIVE_FILTER_ALPHA, 12, 4));
            float[] taps = FilterFactory.getRemezLowPass(rate, config.getPassFrequency(), config.getStopFrequency());
            basebandI = FilterFactory.getRealFilter(taps);
            basebandQ = FilterFactory.getRealFilter(taps.clone());
            NXDNMessageProcessor processor = new NXDNMessageProcessor(config);
            processor.setMessageListener(m -> onMessage(this, m));
            NXDNMessageFramer framer = new NXDNMessageFramer(processor, mode);
            symbols = new NXDNSymbolProcessor(new Feedback(rate), framer, mode);
            symbols.setSamplesPerSymbol(samplesPerSymbol);
        }

        void baseband(float[] i, float[] q, int n) {
            int total = held + n, use = total - total % decimation;
            if (use == 0) {
                System.arraycopy(i, 0, holdI, held, n);
                System.arraycopy(q, 0, holdQ, held, n);
                held += n;
                return;
            }
            float[] bi = new float[use], bq = new float[use];
            System.arraycopy(holdI, 0, bi, 0, held);
            System.arraycopy(holdQ, 0, bq, 0, held);
            int take = use - held;
            System.arraycopy(i, 0, bi, held, take);
            System.arraycopy(q, 0, bq, held, take);
            held = n - take;
            System.arraycopy(i, take, holdI, 0, held);
            System.arraycopy(q, take, holdQ, 0, held);
            float[] di = basebandI.filter(decimateI.decimateReal(bi));
            float[] dq = basebandQ.filter(decimateQ.decimateReal(bq));
            symbols.process(receive.filter(demodulator.demodulate(di, dq)));
        }
    }

    /** Opens the protected sample rate setter. */
    private static final class Feedback extends FeedbackDecoder {
        Feedback(double rate) { setDecimatedSampleRate(rate); }
    }
}
