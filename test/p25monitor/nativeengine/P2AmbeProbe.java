package p25monitor.nativeengine;

import p25monitor.p25.*;
import java.nio.file.*;
import java.util.*;

/** Dev probe: JMBE AMBE+2 error counts of the frames in a Phase 2 dibit capture (MSB-first vs reversed bits). */
public final class P2AmbeProbe {
    public static void main(String[] a) throws Exception {
        java.net.URLClassLoader cl = new java.net.URLClassLoader(java.util.stream.Stream.concat(java.util.stream.Stream.of(Path.of(a[0])), Files.list(Path.of("lib"))).map(p -> { try { return p.toUri().toURL(); } catch (Exception x) { throw new RuntimeException(x); } }).toArray(java.net.URL[]::new));
        Object lib = cl.loadClass("jmbe.JMBEAudioLibrary").getDeclaredConstructor().newInstance();
        System.out.println("supports AMBE: " + lib.getClass().getMethod("supports", String.class).invoke(lib, "AMBE 3600 x 2450"));
        Class<?> fc = cl.loadClass("jmbe.codec.ambe.AMBEFrame");
        java.lang.reflect.Method err = fc.getMethod("getErrors");
        byte[] d = Files.readAllBytes(Path.of(a[1]));
        List<byte[]> frames = new ArrayList<>();
        P2Framer f = new P2Framer(new P2Framer.Listener() { public void voice(int ch, byte[][] fr, int duid) { if (ch == 0) frames.addAll(Arrays.asList(fr)); } });
        f.scrambler = new P2Scrambler(0x92715, 0x1F6, 0x01A);
        for (byte b : d) f.symbol(b & 3);
        for (int rev = 0; rev < 2; rev++) {
            int[] h0 = new int[6], h1 = new int[6];
            for (byte[] x : frames) {
                byte[] y = x.clone();
                if (rev == 1) for (int i = 0; i < 9; i++) y[i] = (byte) (Integer.reverse(x[i] & 0xff) >>> 24);
                int[] e = (int[]) err.invoke(fc.getConstructor(byte[].class).newInstance((Object) y));
                h0[Math.min(5, e[0])]++; h1[Math.min(5, e[1])]++;
            }
            System.out.println((rev == 0 ? "MSB-first" : "reversed ") + " C0 errors " + Arrays.toString(h0) + "  C1 errors " + Arrays.toString(h1));
        }
        Object codec = lib.getClass().getMethod("getAudioConverter", String.class).invoke(lib, "AMBE 3600 x 2450");
        java.lang.reflect.Method ga = codec.getClass().getMethod("getAudio", byte[].class);
        float[] pcm = new float[frames.size() * 160]; int o = 0; double e = 0;
        for (byte[] x : frames) { float[] s = (float[]) ga.invoke(codec, (Object) x); System.arraycopy(s, 0, pcm, o, s.length); o += s.length; for (float v : s) e += v * v; }
        System.out.printf("%d frames -> %d samples, rms %.4f%n", frames.size(), o, Math.sqrt(e / o));
        byte[] w = p25monitor.nativeengine.EngineLibs.pcm16(pcm, o);
        try (var out = Files.newOutputStream(Path.of(a[2]))) {
            java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes()).putInt(36 + w.length).put("WAVEfmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(w.length);
            out.write(h.array()); out.write(w);
        }
    }
}
