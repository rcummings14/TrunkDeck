package p25monitor.dmr;

import java.util.*;

/**
 * Test-only DMR transmitter: builds repeater bursts (voice header, superframes with embedded LC and talker alias,
 * terminator, idle) with the encoder classes in this test package, so the decoder can be checked on known signals.
 */
final class DmrSignal {
    private DmrSignal() {}

    static final int FS = 48000, SPS = 10, DEV = 648;

    static int[] dibitsOf(boolean[] bits) {
        int[] d = new int[bits.length / 2];
        for (int i = 0; i < d.length; i++) d[i] = (bits[i * 2] ? 2 : 0) | (bits[i * 2 + 1] ? 1 : 0);
        return d;
    }

    static final int CC = 5, TG1 = 101, TG2 = 202, SRC1 = 1234, SRC2 = 5678;

    static boolean[] header(int tg, int src) { return DmrBurst.build(DmrSync.Kind.BS_DATA, CC, DmrBurst.VOICE_LC, DmrBurst.linkControl(0, 0, tg, src, false)); }
    static boolean[] term(int tg, int src) { return DmrBurst.build(DmrSync.Kind.BS_DATA, CC, DmrBurst.TERMINATOR, DmrBurst.terminator(0, 0, tg, src, false)); }
    static boolean[] idle() { return DmrBurst.build(DmrSync.Kind.BS_DATA, CC, DmrBurst.IDLE, null); }

    static boolean[] lc72(int flco, int tg, int src) {
        boolean[] full = DmrBurst.linkControl(flco, 0, tg, src, false);
        return Arrays.copyOf(full, 72);
    }

    /** Talker alias LCs (header, block 1) for a short 7-bit text. */
    static boolean[][] aliasLcs(String text) {
        boolean[] stream = new boolean[217];
        for (int i = 0; i < text.length(); i++) DmrBits.write(stream, i * 7, text.charAt(i), 7);
        boolean[] h = new boolean[72];
        DmrBits.write(h, 2, 4, 6);
        DmrBits.write(h, 16, 0, 2);
        DmrBits.write(h, 18, text.length(), 5);
        System.arraycopy(stream, 0, h, 23, 49);
        boolean[] b1 = new boolean[72];
        DmrBits.write(b1, 2, 5, 6);
        System.arraycopy(stream, 49, b1, 16, 56);
        return new boolean[][]{h, b1};
    }

    /** One superframe of a slot: A, B-E carrying the embedded LC, F. */
    static List<boolean[]> superframe(boolean[] embLc, byte[] frame) {
        List<boolean[]> l = new ArrayList<>();
        l.add(DmrVoice.burst(frame, frame, frame, true, DmrSync.Kind.BS_VOICE, 0, null));
        boolean[][] frag = DmrEmbedded.fragments(embLc);
        int[] lcss = {DmrEmbedded.LCSS_FIRST, DmrEmbedded.LCSS_CONT, DmrEmbedded.LCSS_CONT, DmrEmbedded.LCSS_LAST};
        for (int k = 0; k < 4; k++) l.add(DmrVoice.burst(frame, frame, frame, false, DmrSync.Kind.NONE, DmrEmbedded.emb(CC, 0, lcss[k]), frag[k]));
        l.add(DmrVoice.burst(frame, frame, frame, false, DmrSync.Kind.NONE, DmrEmbedded.emb(CC, 0, 0), null));
        return l;
    }

    static List<boolean[]> call(int tg, int src, int superframes, String alias) {
        List<boolean[]> l = new ArrayList<>();
        l.add(header(tg, src)); l.add(header(tg, src)); l.add(header(tg, src));
        boolean[][] al = alias == null ? null : aliasLcs(alias);
        for (int s = 0; s < superframes; s++) {
            boolean[] emb = lc72(0, tg, src);
            if (al != null && s % 3 == 1) emb = al[0];
            if (al != null && s % 3 == 2) emb = al[1];
            l.addAll(superframe(emb, DmrAmbe.SILENCE));
        }
        l.add(term(tg, src)); l.add(term(tg, src));
        return l;
    }

    static final class Rec implements DmrDecoder.Listener {
        final int[] frames = new int[3];
        final int[] groupSlotTg = new int[3];
        int wrongSlot, ends, alias, badFrames, units;
        String aliasText;
        public void group(long hz, int slot, int cc, int tg, int src, byte[][] ambe) {
            if (ambe.length == 0) return;
            int want = slot == 1 ? TG1 : TG2;
            if (tg != want) wrongSlot++;
            frames[slot] += ambe.length;
            for (byte[] fr : ambe) if (!DmrAmbe.decode(fr).ok()) badFrames++;
        }
        public void unit(long hz, int slot, int cc, int t, int s) { units++; }
        public void encrypted(long hz, int slot, int cc) {}
        public void end(long hz, int slot) { ends++; }
        public void grant(DmrTrunk.Grant g) {}
        public void rest(int r, int s, long h, boolean m) {}
        public void alias(int src, String text) { alias++; aliasText = src + ":" + text; }
    }
}
