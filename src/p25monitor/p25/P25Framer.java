package p25monitor.p25;

import p25monitor.dsp.C4fmDemod;

/**
 * Assembles P25 Phase 1 data units from demodulated symbols: removes status symbols (every 36th dibit counted from
 * the start of frame sync), decodes the NID (BCH) with NAC check, then TSDU (up to 3 trellis-coded TSBKs), LDU1/LDU2
 * (9 IMBE voice frames + RS/Hamming protected link control / encryption sync), HDU, TDU and TDULC.
 */
public final class P25Framer implements C4fmDemod.Sink {
    public static final int DUID_HDU = 0x0, DUID_TDU = 0x3, DUID_LDU1 = 0x5, DUID_TSDU = 0x7, DUID_LDU2 = 0xA,
            DUID_PDU = 0xC, DUID_TDULC = 0xF;

    public interface Listener {
        default void nid(int nac, int duid, int bitErrors) {}
        default void tsbk(Tsbk t) {}
        default void tsbkError() {}
        /** imbe: 9 frames x 18 bytes (raw 144 channel bits, JMBE deinterleaves/decodes). lc: LDU1 link control or null. */
        default void ldu(int nac, int duid, byte[][] imbe, LinkControl lc, EncryptionSync ess) {}
        default void hdu(int nac) {}
        default void terminator(int nac, int duid) {}
        /** A frame sync whose NID could not be decoded (a lost data unit). */
        default void nidError() {}
    }

    static final int[] IMBE_OFFSETS = {112, 256, 440, 624, 808, 992, 1176, 1360, 1536};
    static final int[] LC_OFFSETS = {400, 584, 768, 952, 1136, 1320};

    final Listener listener;
    public volatile int expectedNac = -1;     // -1 = accept any
    public volatile boolean enforceNac = false;
    final byte[] bits = new byte[1800];
    final float[] levels = new float[900];
    int nbits, dibitIndex, need, duid, nac, blocks;
    boolean active;
    // statistics
    public volatile long tsbkOk, tsbkBad, nidOk, nidBad, frames;
    /** Running FEC statistics (bits checked / bits corrected by the NID BCH, TSBK trellis and LDU Hamming codes): a live
     *  bit-error-rate estimate used to compare demodulators. A failed NID counts as 16 errors in 64 bits. */
    public volatile long fecBits, fecErrors;
    final ReedSolomon rs2412 = new ReedSolomon(24, 12), rs2416 = new ReedSolomon(24, 16);

    public P25Framer(Listener l) { listener = l; }

    /** Stream gap: drops the frame being assembled. Returns true if it was a voice frame (LDU). */
    public boolean abort() {
        boolean voice = active && (duid == DUID_LDU1 || duid == DUID_LDU2);
        active = false;
        return voice;
    }

    @Override public void sync(float quality) {
        active = true;
        dibitIndex = 24;
        long fs = 0x5575F5FF77FFL;
        for (int i = 0; i < 48; i++) bits[i] = (byte) ((fs >> (47 - i)) & 1);
        nbits = 48;
        need = 112;
        blocks = 0;
        duid = -1;
    }

    @Override public void symbol(int dibit, float level) {
        if (!active) return;
        int idx = dibitIndex++;
        if ((idx + 1) % 36 == 0) return; // status symbol
        levels[nbits >> 1] = level;
        bits[nbits++] = (byte) (dibit >> 1);
        bits[nbits++] = (byte) (dibit & 1);
        if (nbits >= need) complete();
        if (nbits >= bits.length - 2) active = false;
    }

    void complete() {
        if (duid < 0) { // NID
            long w = 0;
            for (int i = 48; i < 112; i++) w = (w << 1) | bits[i];
            int r = Fec.nidDecode(w, expectedNac, 9);
            if (r < 0) { nidBad++; fecBits += 64; fecErrors += 16; active = false; listener.nidError(); return; }
            int data = r >>> 8;
            nac = data >> 4; duid = data & 0xf;
            if (enforceNac && expectedNac >= 0 && nac != expectedNac) { nidBad++; active = false; return; }
            nidOk++; frames++;
            fecBits += 64; fecErrors += r & 0xff;
            listener.nid(nac, duid, r & 0xff);
            switch (duid) {
                case DUID_TSDU -> need = 112 + 196;
                case DUID_LDU1, DUID_LDU2 -> need = 1680;
                case DUID_HDU -> need = 112 + 648;
                case DUID_TDU, DUID_TDULC -> { listener.terminator(nac, duid); active = false; }
                default -> active = false; // PDU etc. not decoded
            }
            return;
        }
        switch (duid) {
            case DUID_TSDU -> {
                int[] err = new int[1];
                byte[] b = Fec.trellisDecode(bits, 112 + blocks * 196, err);
                fecBits += 196; fecErrors += Math.min(err[0], 98);
                if (b == null || !Fec.crc16Ok(b)) b = Fec.trellisDecodeSoft(levels, (112 + blocks * 196) / 2);
                blocks++;
                if (b == null || !Fec.crc16Ok(b)) {
                    tsbkBad++;
                    listener.tsbkError();
                    if (blocks < 3) need += 196; else active = false;
                    return;
                }
                tsbkOk++;
                Tsbk t = new Tsbk(b, nac);
                listener.tsbk(t);
                if (t.lastBlock || blocks >= 3) active = false; else need += 196;
            }
            case DUID_LDU1, DUID_LDU2 -> { ldu(); active = false; }
            case DUID_HDU -> { listener.hdu(nac); active = false; }
            default -> active = false;
        }
    }

    void ldu() {
        byte[][] frames = new byte[9][18];
        for (int f = 0; f < 9; f++) {
            int o = IMBE_OFFSETS[f];
            for (int i = 0; i < 144; i++) if (bits[o + i] != 0) frames[f][i >> 3] |= (byte) (0x80 >> (i & 7));
        }
        int[] hex = new int[24];
        int[] e = new int[1];
        int w = 0;
        for (int g = 0; g < 6; g++) for (int j = 0; j < 4; j++) {
            int o = LC_OFFSETS[g] + j * 10, cw = 0;
            for (int i = 0; i < 10; i++) cw = (cw << 1) | bits[o + i];
            hex[w++] = Fec.hamming10Decode(cw, e);
            fecErrors += e[0];
        }
        fecBits += 240;
        LinkControl lc = null;
        EncryptionSync ess = null;
        if (duid == DUID_LDU1) {
            int[] c = hex.clone();
            if (rs2412.decode(c) >= 0) lc = new LinkControl(c);
        } else {
            int[] c = hex.clone();
            if (rs2416.decode(c) >= 0) ess = new EncryptionSync(c);
        }
        listener.ldu(nac, duid, frames, lc, ess);
    }

    /** Packs hexbits (6 bits each) into bytes, MSB first. */
    static byte[] pack(int[] hex, int count) {
        byte[] b = new byte[(count * 6 + 7) / 8];
        for (int i = 0; i < count * 6; i++) if (((hex[i / 6] >> (5 - i % 6)) & 1) != 0) b[i >> 3] |= (byte) (0x80 >> (i & 7));
        return b;
    }

    /** LDU1 link control word (72 bits). */
    public static final class LinkControl {
        public final byte[] b;
        public final int lcf, mfid;
        public LinkControl(int[] hex) { b = pack(hex, 12); lcf = b[0] & 0xff; mfid = b[1] & 0xff; }
        int u(int i) { return b[i] & 0xff; }
        public int opcode() { return lcf & 0x3f; }
        public boolean isProtected() { return (lcf & 0x80) != 0; }
        public boolean isGroupVoice() { return opcode() == 0 && mfid == 0 || opcode() == 0 && mfid == 0x90; }
        public boolean isUnitToUnit() { return opcode() == 3 && mfid == 0; }
        public int serviceOptions() { return u(2); }
        public boolean emergency() { return (u(2) & 0x80) != 0; }
        public boolean encrypted() { return (u(2) & 0x40) != 0; }
        public int talkgroup() { return (u(4) << 8) | u(5); }
        public int target() { return (u(3) << 16) | (u(4) << 8) | u(5); }
        public int source() { return (u(6) << 16) | (u(7) << 8) | u(8); }
        public String toString() {
            if (isGroupVoice()) return "LC GROUP VOICE TG:" + talkgroup() + " SRC:" + source() + (encrypted() ? " ENCRYPTED" : "") + (emergency() ? " EMERGENCY" : "");
            if (isUnitToUnit()) return "LC UNIT-TO-UNIT TO:" + target() + " SRC:" + source();
            return String.format("LC LCF:%02X MFID:%02X", lcf, mfid);
        }
    }

    /** LDU2 encryption sync: message indicator (72), algorithm id (8), key id (16). */
    public static final class EncryptionSync {
        public final byte[] b;
        public EncryptionSync(int[] hex) { b = pack(hex, 16); }
        public int algorithm() { return b[9] & 0xff; }
        public int keyId() { return ((b[10] & 0xff) << 8) | (b[11] & 0xff); }
        /** 0x80 = unencrypted; 0x00 is also seen from some radios when idle. */
        public boolean encrypted() { int a = algorithm(); return a != 0x80 && a != 0x00; }
        public String algorithmName() {
            return switch (algorithm()) {
                case 0x80 -> "CLEAR"; case 0x81 -> "DES-OFB"; case 0x83 -> "3DES"; case 0x84 -> "AES-256"; case 0x85 -> "AES-128";
                case 0xAA -> "ADP/RC4"; case 0x9F -> "DES-XL"; case 0x01, 0x02, 0x03, 0x04, 0x05 -> "TYPE-1/2"; default -> String.format("ALG %02X", algorithm());
            };
        }
    }

    // ---------------------------------------------------------------- test-signal helpers (encoder side)
    /** Builds the dibits of a complete frame from its status-free bit vector (sync+NID+body), inserting status symbols. */
    public static int[] toDibitsWithStatus(byte[] clean, int nClean, int statusDibit) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        int ci = 0, idx = 0;
        while (ci < nClean) {
            if ((idx + 1) % 36 == 0) out.add(statusDibit);
            else { int hi = clean[ci++], lo = ci < nClean ? clean[ci++] : 0; out.add((hi << 1) | lo); }
            idx++;
        }
        while ((idx) % 36 != 0) { out.add((idx + 1) % 36 == 0 ? statusDibit : 0); idx++; } // pad to the next status boundary
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    public static byte[] header(int nac, int duid) {
        byte[] b = new byte[1800];
        long fs = 0x5575F5FF77FFL;
        for (int i = 0; i < 48; i++) b[i] = (byte) ((fs >> (47 - i)) & 1);
        long nid = Fec.nidEncode((nac << 4) | duid);
        for (int i = 0; i < 64; i++) b[48 + i] = (byte) ((nid >>> (63 - i)) & 1);
        return b;
    }

    /** TSDU frame (1..3 TSBKs; the last-block flag is set on the final one and CRCs are filled in). */
    public static int[] buildTsdu(int nac, byte[]... tsbks) {
        byte[] b = header(nac, DUID_TSDU);
        int n = 112;
        for (int i = 0; i < tsbks.length; i++) {
            byte[] t = tsbks[i].clone();
            if (i == tsbks.length - 1) t[0] |= (byte) 0x80; else t[0] &= 0x7f;
            Fec.crc16Fill(t);
            byte[] enc = Fec.trellisEncode(t);
            System.arraycopy(enc, 0, b, n, 196);
            n += 196;
        }
        return toDibitsWithStatus(b, n, 2);
    }

    /** LDU1/LDU2 frame with the given 9 IMBE frames (18 bytes each) and 12 (LC) or 16 (ESS) data hexbits. */
    public static int[] buildLdu(int nac, int duid, byte[][] imbe, int[] dataHex) {
        byte[] b = header(nac, duid);
        for (int f = 0; f < 9; f++) for (int i = 0; i < 144; i++) b[IMBE_OFFSETS[f] + i] = (byte) ((imbe[f][i >> 3] >> (7 - (i & 7))) & 1);
        int[] cw = (duid == DUID_LDU1 ? new ReedSolomon(24, 12) : new ReedSolomon(24, 16)).encode(dataHex);
        int w = 0;
        for (int g = 0; g < 6; g++) for (int j = 0; j < 4; j++) {
            int h = Fec.hamming10Encode(cw[w++]);
            for (int i = 0; i < 10; i++) b[LC_OFFSETS[g] + j * 10 + i] = (byte) ((h >> (9 - i)) & 1);
        }
        return toDibitsWithStatus(b, 1680, 2);
    }

    public static int[] buildTdu(int nac) {
        byte[] b = header(nac, DUID_TDU);
        return toDibitsWithStatus(b, 112 + 28, 2);
    }

    /** Splits bytes into hexbits (MSB first). */
    public static int[] hexbits(byte[] b, int count) {
        int[] h = new int[count];
        for (int i = 0; i < count * 6; i++) if (((b[i >> 3] >> (7 - (i & 7))) & 1) != 0) h[i / 6] |= 1 << (5 - i % 6);
        return h;
    }
}
