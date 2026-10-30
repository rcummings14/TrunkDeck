package p25monitor.nativeengine;
import p25monitor.p25.*;
import java.nio.file.*;
public final class P2SeqProbe {
    public static void main(String[] a) throws Exception {
        byte[] d = Files.readAllBytes(Path.of(a[0]));
        StringBuilder sb = new StringBuilder();
        String[] nm = {"4V","r1","r2","SA","r4","r5","2V","r7","r8","FA","DA","rB","SAu","LCu","rE","FAu"};
        P2Framer f = new P2Framer(new P2Framer.Listener() {});
        f.scrambler = new P2Scrambler(0x92715, 0x1F6, 0x01A);
        f.burstTap = v -> { int sf = v >> 8, ch = v >> 5 & 1, du = v & 31; if (sf == 0) sb.append("\n"); sb.append(sf).append(":TS").append(ch + 1).append("=").append(du == 31 ? "?" : nm[du]).append(" "); };
        for (byte b : d) f.symbol(b & 3);
        String[] lines = sb.toString().split("\n");
        for (int i = 0; i < lines.length; i += Math.max(1, lines.length / 25)) System.out.println(lines[i]);
    }
}
