package p25monitor.nativeengine;

import p25monitor.p25.*;
import p25monitor.sdr.IqFileSource;
import java.io.PrintStream;
import java.nio.file.*;
import java.util.*;

/**
 * 1.4.6 (MIT table regeneration): decodes recordings and prints every decoded item - NIDs with bit errors, TSBKs, LDU
 * voice frames and link control, HDU / terminators, FEC statistics, Phase 2 ISCH / MAC PDUs / voice frames - so that the
 * output of two builds can be compared line for line (it must be identical).
 * Usage: DecodeDump out.txt file...   (.wav I/Q through C4FM and CQPSK demodulators; .bin = Phase 2 dibit capture)
 */
public final class DecodeDump {
    static PrintStream o;
    static String hex(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(String.format("%02x", x)); return s.toString(); }
    static String bits(byte[] b) { StringBuilder s = new StringBuilder(); for (byte x : b) s.append(x); return s.toString(); }

    static P25Framer.Listener listener(String tag) {
        return new P25Framer.Listener() {
            public void nid(int nac, int duid, int e) { o.println(tag + " NID " + Integer.toHexString(nac) + " " + duid + " e" + e); }
            public void tsbk(Tsbk t) { o.println(tag + " TSBK " + hex(t.b) + " last=" + t.lastBlock); }
            public void tsbkError() { o.println(tag + " TSBK-ERR"); }
            public void ldu(int nac, int duid, byte[][] imbe, P25Framer.LinkControl lc, P25Framer.EncryptionSync es) {
                StringBuilder s = new StringBuilder(tag + " LDU" + duid + " " + Integer.toHexString(nac));
                for (byte[] fr : imbe) s.append(' ').append(hex(fr));
                s.append(" lc=").append(lc == null ? "-" : hex(lc.b)).append(" es=").append(es == null ? "-" : hex(es.b));
                o.println(s);
            }
            public void hdu(int nac) { o.println(tag + " HDU " + Integer.toHexString(nac)); }
            public void terminator(int nac, int duid) { o.println(tag + " TERM " + Integer.toHexString(nac) + " " + duid); }
            public void nidError() { o.println(tag + " NID-ERR"); }
        };
    }

    /** The engine's own channel decoder (channelizer, demodulator, framer), once per modulation setting. */
    static void wav(Path p) throws Exception {
        for (ChannelDecoder.Modulation m : ChannelDecoder.Modulation.values()) {
            String tag = p.getFileName() + "/" + m.name();
            IqFileSource src = new IqFileSource(p, 0, 0);
            P25Framer.Listener l = listener(tag);
            ChannelDecoder d = new ChannelDecoder(src.sampleRate(), 0, 0, l, 0, m, false, -1);
            src.readAll(d::process);
            o.println(tag + " END");
        }
    }

    static void bin(Path p) throws Exception {
        String tag = p.getFileName().toString();
        for (int pass = 0; pass < 2; pass++) {
            String t = tag + (pass == 0 ? "/plain" : "/scrambled");
            P2Framer f = new P2Framer(new P2Framer.Listener() {
                public void voice(int ch, byte[][] fr, int duid) { StringBuilder s = new StringBuilder(t + " VOICE ch" + ch + " d" + duid); for (byte[] x : fr) s.append(' ').append(hex(x)); o.println(s); }
                public void mac(int ch, P2Mac m) { o.println(t + " MAC ch" + ch + " " + (m.facch ? "F" : "S") + " crc=" + m.crcOk + " rs=" + m.rsFixed + " " + bits(m.bits) + " | " + m); }
                public void fragment() { o.println(t + " FRAG"); }
                public void syncLost() { o.println(t + " SYNCLOST"); }
            });
            if (pass == 1) f.scrambler = new P2Scrambler(0x92715, 0x1F6, 0x01A);
            for (byte b : Files.readAllBytes(p)) f.symbol(b & 3);
            o.println(t + " END frags=" + f.fragments + " ischOk=" + f.ischOk + " ischBad=" + f.ischBad + " macOk=" + f.macOk + " macBad=" + f.macBad + " syncs=" + f.syncs + " duid=" + Arrays.toString(f.duidCount));
        }
    }

    public static void main(String[] a) throws Exception {
        o = new PrintStream(Files.newOutputStream(Path.of(a[0])), false, "UTF-8");
        for (int i = 1; i < a.length; i++) {
            Path p = Path.of(a[i]);
            if (p.toString().endsWith(".bin")) bin(p); else wav(p);
            System.out.println("done " + p);
        }
        o.close();
    }
}
