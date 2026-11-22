package p25monitor.nxdn;

import java.util.*;

/** The test transmitter (NxdnCoding, NxdnFrame) checks itself: channel coding, frames and layer 3 messages. */
public final class NxdnTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("NxdnTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static float[] soft(boolean[] bits) { float[] s = new float[bits.length]; for (int i = 0; i < bits.length; i++) s[i] = bits[i] ? 1f : -1f; return s; }

    static void run() {
        Random r = new Random(7);
        // scrambler: PN9 has period 511, only even positions are touched
        boolean[] sc = NxdnCoding.scramble(2 * 600);
        boolean periodic = true, oddClear = true;
        for (int i = 0; i < 1200; i++) { if (i % 2 == 1 && sc[i]) oddClear = false; if (i % 2 == 0 && i + 1022 < 1200 && sc[i] != sc[i + 1022]) periodic = false; }
        check(oddClear && periodic, "scrambler touches even bits only, period 511");

        // CRC: a filled message checks, one flipped bit does not
        boolean[] m = new boolean[32];
        for (int i = 0; i < 26; i++) m[i] = r.nextBoolean();
        NxdnCoding.crcFill(m, 26, 6, NxdnCoding.CRC6);
        boolean ok = NxdnCoding.crcOk(m, 26, 6, NxdnCoding.CRC6);
        m[3] = !m[3];
        check(ok && !NxdnCoding.crcOk(m, 26, 6, NxdnCoding.CRC6), "CRC-6 detects a flipped bit");

        // coded lengths match the field sizes
        check(NxdnCoding.encode(new boolean[32], NxdnCoding.P_SACCH).length == 60, "SACCH codes to 60 bits");
        check(NxdnCoding.encode(new boolean[92], NxdnCoding.P_FACCH1).length == 144, "FACCH1 codes to 144 bits");
        check(NxdnCoding.encode(new boolean[171], NxdnCoding.P_CAC).length == 300, "CAC codes to 300 bits");

        // Viterbi corrects scattered errors
        boolean[] info = new boolean[92];
        for (int i = 0; i < 80; i++) info[i] = r.nextBoolean();
        float[] coded = soft(NxdnCoding.encode(info, NxdnCoding.P_FACCH1));
        for (int i = 5; i < coded.length; i += 29) coded[i] = -coded[i];
        int[] err = new int[1];
        boolean[] back = NxdnCoding.decode(coded, NxdnCoding.P_FACCH1, 92, err);
        check(Arrays.equals(Arrays.copyOf(back, 80), Arrays.copyOf(info, 80)) && err[0] == 5, "Viterbi corrects 5 scattered errors (" + err[0] + ")");

        // control frame: VCALL_ASSGN through CAC
        boolean[] l3 = NxdnMessage.call(144, NxdnMessage.VCALL_ASSGN, 0x80, NxdnMessage.CT_GROUP_CONFERENCE, 1234, 501);
        NxdnCoding.write(l3, 62, 37, 10);
        boolean[] cac = new boolean[155];
        NxdnCoding.write(cac, 2, 9, 6);
        System.arraycopy(l3, 0, cac, 8, 144);
        boolean[] cf = NxdnFrame.buildControl(cac);
        float[] cs = soft(cf);
        for (int i = 20; i < 364; i += 41) cs[i] = -cs[i];
        NxdnFrame f = NxdnFrame.decode(cs);
        check(f.lichOk && f.control() && f.cacOk, "control frame: LICH and CAC CRC pass with 9 bit errors");
        NxdnMessage msg = new NxdnMessage(Arrays.copyOfRange(f.cac, 8, 152), true);
        check(msg.isAssignment() && msg.source() == 1234 && msg.destination() == 501 && msg.channel() == 37 && msg.emergency() && msg.group(),
                "assignment fields: " + msg.describe(0));

        // traffic frame: voice/voice, then FACCH1 VCALL
        byte[][] vf = new byte[4][9];
        for (byte[] v : vf) r.nextBytes(v);
        boolean[] tf = NxdnFrame.buildTraffic(0x37, NxdnFrame.sacch(3, 9, null), null, null, vf);
        NxdnFrame t = NxdnFrame.decode(soft(tf));
        boolean same = t.voice.length == 4;
        for (int k = 0; same && k < 4; k++) same = Arrays.equals(vf[k], t.voice[k]);
        check(t.lichOk && t.sacchOk && same, "voice frame: 4 AMBE frames come back unchanged");
        boolean[] call = NxdnMessage.call(80, NxdnMessage.VCALL, 0, NxdnMessage.CT_INDIVIDUAL, 77, 88);
        NxdnCoding.write(call, 56, 3, 2);
        NxdnFrame t2 = NxdnFrame.decode(soft(NxdnFrame.buildTraffic(0x31, NxdnFrame.sacch(0, 9, null), call, call, null)));
        NxdnMessage vc = t2.facch1.isEmpty() ? null : new NxdnMessage(t2.facch1.get(0), false);
        check(t2.facch1.size() == 2 && vc.isCall() && !vc.group() && vc.source() == 77 && vc.destination() == 88 && vc.cipher() == 3,
                "FACCH1 VCALL: " + (vc == null ? "none" : vc.describe(0)));
        boolean[] bad = NxdnFrame.buildTraffic(0x31, NxdnFrame.sacch(0, 9, null), call, call, null);
        bad[0] = !bad[0];
        check(!NxdnFrame.decode(soft(bad)).lichOk, "LICH bit error fails its parity");
    }
}
