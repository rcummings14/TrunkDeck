package p25monitor.nativeengine;
import p25monitor.dsp.*;
import p25monitor.p25.*;
import java.util.*;
public final class P2ModemProbe {
    public static void main(String[] a) {
        Random r = new Random(1);
        P2Scrambler sc = new P2Scrambler(0xA4321, 0x2D5, 0x2D5);
        P2Builder.Stream st = new P2Builder.Stream(sc, new P2Builder.CallSlot(), new P2Builder.CallSlot());
        List<Integer> dib = new ArrayList<>();
        for (int k = 0; k < 150; k++) dib.add(r.nextInt(4));
        for (int k = 0; k < 30; k++) for (int d : st.nextFragment()) dib.add(d);
        int[] dd = dib.stream().mapToInt(Integer::intValue).toArray();
        float[] x = new CqpskModulator(8).modulate(dd);
        List<Integer> got = new ArrayList<>(); long[] syncs = {0}; P2Framer fr = new P2Framer(new P2Framer.Listener() {}); fr.scrambler = sc;
        CqpskDemod dm = CqpskDemod.phase2(new C4fmDemod.Sink() { public void sync(float q) { fr.syncEvent(); syncs[0]++; if (syncs[0] < 20) System.out.print(got.size() + "(" + String.format("%.2f", q) + ") "); } public void symbol(int d, float l) { got.add(d); fr.symbol(d); } }); dm.equalizer = Boolean.parseBoolean(System.getProperty("eq", "true"));
        int n = x.length / 2; float[] xi = new float[n], xq = new float[n];
        for (int k = 0; k < n; k++) { xi[k] = x[2 * k]; xq[k] = x[2 * k + 1]; }
        dm.process(xi, xq, n);
        System.out.println();  System.out.println("framer: fragments " + fr.fragments + " isch ok " + fr.ischOk + " mac ok " + fr.macOk + " bad " + fr.macBad); System.out.println("sent " + dd.length + " got " + got.size() + " syncs " + syncs[0]);
        // align
        int best = -1, bm = -1;
        for (int off = -200; off < 400; off++) { int m = 0; for (int i = 0; i < Math.min(2000, got.size()); i++) { int j = i + off; if (j >= 0 && j < dd.length && got.get(i) == dd[j]) m++; } if (m > bm) { bm = m; best = off; } }
        System.out.println("best offset " + best + " matches " + bm + "/2000");
        int[] conf = new int[16]; for (int i = 0; i < Math.min(3000, got.size()); i++) { int j = i + best; if (j >= 0 && j < dd.length) conf[dd[j] * 4 + got.get(i)]++; }
        System.out.println("confusion sent*4+got " + Arrays.toString(conf));
    }
}
