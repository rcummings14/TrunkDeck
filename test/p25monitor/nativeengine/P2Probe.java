package p25monitor.nativeengine;

import p25monitor.p25.*;
import java.nio.file.*;
import java.util.*;

/** Dev probe: runs a DSD-FME dibit capture through the Phase 2 framer and prints what it finds. */
public final class P2Probe {
    public static void main(String[] a) throws Exception {
        byte[] d = Files.readAllBytes(Path.of(a[0]));
        int wacn = Integer.parseInt(a[1], 16), sys = Integer.parseInt(a[2], 16), nac = Integer.parseInt(a[3], 16);
        int[] frames = new int[2]; List<String> macs = new ArrayList<>();
        P2Framer f = new P2Framer(new P2Framer.Listener() {
            public void voice(int ch, byte[][] fr, int duid) { frames[ch] += fr.length; }
            public void mac(int ch, P2Mac m) { if (macs.size() < 60 || m.type == P2Mac.PTT) macs.add("TS" + (ch + 1) + " " + m); }
        });
        f.scrambler = new P2Scrambler(wacn, sys, nac);
        for (byte b : d) f.symbol(b & 3);
        System.out.println("fragments " + f.fragments + " locks " + f.lockCount + " ISCH ok/bad " + f.ischOk + "/" + f.ischBad + " bursts " + f.bursts + " voice bursts " + f.voiceBursts);
        System.out.println("DUIDs " + Arrays.toString(f.duidCount));
        System.out.println("MAC ok/bad " + f.macOk + "/" + f.macBad + " scrambled MAC ok/bad " + f.scrambledMacOk + "/" + f.scrambledMacBad);
        System.out.println("voice frames TS1 " + frames[0] + " TS2 " + frames[1]);
        for (String s : macs.subList(0, Math.min(80, macs.size()))) System.out.println("  " + s);
    }
}
