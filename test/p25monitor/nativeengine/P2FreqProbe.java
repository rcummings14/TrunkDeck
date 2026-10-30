package p25monitor.nativeengine;
import p25monitor.p25.*;
import java.nio.file.*;
import java.util.*;
public final class P2FreqProbe {
    public static void main(String[] a) throws Exception {
        java.net.URLClassLoader cl = new java.net.URLClassLoader(java.util.stream.Stream.concat(java.util.stream.Stream.of(Path.of(a[0])), Files.list(Path.of("lib"))).map(p -> { try { return p.toUri().toURL(); } catch (Exception x) { throw new RuntimeException(x); } }).toArray(java.net.URL[]::new));
        Object lib = cl.loadClass("jmbe.JMBEAudioLibrary").getDeclaredConstructor().newInstance();
        Object codec = lib.getClass().getMethod("getAudioConverter", String.class).invoke(lib, "AMBE 3600 x 2450");
        java.lang.reflect.Method ga = codec.getClass().getMethod("getAudio", byte[].class);
        Class<?> fc = cl.loadClass("jmbe.codec.ambe.AMBEFrame");
        Map<String, Integer> cnt = new HashMap<>(); Map<String, byte[]> by = new HashMap<>();
        P2Framer f = new P2Framer(new P2Framer.Listener() { public void voice(int ch, byte[][] fr, int duid) { for (byte[] x : fr) { String k = HexFormat.of().formatHex(x); cnt.merge(k, 1, Integer::sum); by.put(k, x); } } });
        f.scrambler = new P2Scrambler(0x92715, 0x1F6, 0x01A);
        for (byte b : Files.readAllBytes(Path.of(a[1]))) f.symbol(b & 3);
        System.out.println("distinct frames " + cnt.size());
        cnt.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(4).forEach(en -> {
            try {
                byte[] x = by.get(en.getKey()); double e = 0; float[] s = null;
                for (int i = 0; i < 5; i++) s = (float[]) ga.invoke(codec, (Object) x);
                for (float v : s) e += v * v;
                System.out.println(en.getKey() + " x" + en.getValue() + " errors " + Arrays.toString((int[]) fc.getMethod("getErrors").invoke(fc.getConstructor(byte[].class).newInstance((Object) x))) + " rms " + Math.sqrt(e / s.length));
            } catch (Exception ex) { ex.printStackTrace(); }
        });
    }
}
