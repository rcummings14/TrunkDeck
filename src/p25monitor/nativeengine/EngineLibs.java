package p25monitor.nativeengine;

import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import javax.sound.sampled.AudioFormat;

/**
 * Optional libraries loaded at runtime (never compiled into TrunkDeck): the JMBE IMBE voice codec (patented; built on
 * the user's machine from its open-source code, or a jar the user chooses), its helper libraries, and the LAME MP3
 * encoder (java-lame, LGPL) - all in app/lib of the install folder.
 */
public final class EngineLibs {
    private EngineLibs() {}

    static Path engineLib() {
        String o = System.getProperty("trunkdeck.lib");
        if (o != null) return Paths.get(o);
        return Paths.get(System.getProperty("trunkdeck.home", "."), "app", "lib");
    }

    static List<URL> jars(String... prefixes) {
        List<URL> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(engineLib(), "*.jar")) {
            for (Path p : ds) for (String pre : prefixes) if (p.getFileName().toString().startsWith(pre)) out.add(p.toUri().toURL());
        } catch (Exception ignored) {}
        return out;
    }

    // ------------------------------------------------------------------ JMBE
    private static ClassLoader jmbeLoader;
    private static Object jmbeLibrary;
    private static String jmbeError, jmbeVersion;
    private static Path jmbeJar;

    /** Where TrunkDeck looks for the codec: explicit setting ("Choose jar..."), then its own build in profile/jmbe. */
    public static Path findJmbe(Path profile, String configured) {
        List<Path> c = new ArrayList<>();
        if (configured != null && !configured.isBlank()) c.add(Paths.get(configured));
        Path own = profile.resolve("jmbe");
        List<Path> found = new ArrayList<>();
        if (Files.isDirectory(own)) try (DirectoryStream<Path> ds = Files.newDirectoryStream(own, "jmbe-*.jar")) { for (Path p : ds) if (isCodecJar(p)) found.add(p); } catch (Exception ignored) {}
        found.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());   // newest version first
        c.addAll(found);
        for (Path p : c) if (Files.isRegularFile(p)) return p;
        return null;
    }

    /** jmbe-x.y.z.jar is the codec; jmbe-api-*.jar (an interface-only jar some programs ship) is not. */
    public static boolean isCodecJar(Path p) { String n = p.getFileName().toString(); return n.startsWith("jmbe-") && n.endsWith(".jar") && !n.startsWith("jmbe-api"); }

    public static synchronized boolean loadJmbe(Path jar) {
        if (jar == null) { jmbeError = "JMBE codec library not installed"; return false; }
        if (jar.equals(jmbeJar) && jmbeLibrary != null) return true;
        try {
            List<URL> urls = new ArrayList<>();
            urls.add(jar.toUri().toURL());
            urls.addAll(jars("jmbe-api", "JTransforms", "JLargeArrays", "slf4j-api", "commons-math3"));
            ClassLoader cl = new URLClassLoader(urls.toArray(new URL[0]), EngineLibs.class.getClassLoader());
            Object lib = cl.loadClass("jmbe.JMBEAudioLibrary").getDeclaredConstructor().newInstance();
            jmbeVersion = (String) lib.getClass().getMethod("getVersion").invoke(lib);
            boolean imbe = (boolean) lib.getClass().getMethod("supports", String.class).invoke(lib, "IMBE");
            if (!imbe) throw new IllegalStateException("this JMBE build has no IMBE codec");
            boolean ambe = false;
            try { ambe = (boolean) lib.getClass().getMethod("supports", String.class).invoke(lib, "AMBE 3600 x 2450"); } catch (Throwable ignored) {}
            jmbeAmbe = ambe;
            jmbeLoader = cl; jmbeLibrary = lib; jmbeJar = jar; jmbeError = null;
            return true;
        } catch (Throwable t) {
            jmbeError = "Could not load JMBE from " + jar + ": " + (t instanceof InvocationTargetException ite ? ite.getCause() : t);
            jmbeLibrary = null;
            return false;
        }
    }

    /** True if the loaded JMBE also has the AMBE+2 half-rate codec (JMBE 2.x) needed for Phase 2 audio. */
    static volatile boolean jmbeAmbe;
    public static synchronized String jmbeStatus() {
        if (jmbeLibrary == null) return jmbeError == null ? "not loaded" : jmbeError;
        return jmbeVersion + (jmbeAmbe ? "  - IMBE (Phase 1) + AMBE+2 (Phase 2)" : "  - IMBE only: Phase 2 calls will be silent (use Build from source for JMBE 2.x)") + "  (" + jmbeJar + ")";
    }
    /** jmbeStatus() without the jar path (the UI shows the path separately, with an ellipsis and a Copy button). */
    public static synchronized String jmbeSummary() {
        if (jmbeLibrary == null) return jmbeStatus();
        return jmbeVersion + (jmbeAmbe ? "  - IMBE (Phase 1) + AMBE+2 (Phase 2)" : "  - IMBE only: Phase 2 calls will be silent (use Build from source for JMBE 2.x)");
    }
    /** The loaded JMBE jar, or "" . */
    public static synchronized String jmbeJarPath() { return jmbeLibrary == null || jmbeJar == null ? "" : jmbeJar.toString(); }
    public static synchronized boolean jmbeReady() { return jmbeLibrary != null; }

    /** One IMBE decoder instance (stateful: one per call). */
    /**
     * A voice codec instance (one per call: codecs keep state between frames). Phase 1 uses IMBE; Phase 2 (TDMA) uses
     * JMBE's AMBE+2 half-rate codec ({@link Ambe}), one per time-slot call.
     */
    public interface VoiceCodec {
        /** 8 kHz audio of one 20 ms frame (160 samples), or null. */
        float[] decode(byte[] frame);
        /** {total corrected bits, errors in the first codeword, repeat flag, mute flag} of this frame, or null if unknown. */
        int[] stats(byte[] frame);
        default String codecName() { return "IMBE"; }
    }

    public static final class Imbe implements VoiceCodec {
        final Object codec; final Method getAudio, reset;
        Constructor<?> frameCtor, paramsCtor; Method getParams, errTotal, errC0, repeat, mute; java.lang.reflect.Field fErrTotal, fErrors;
        Object prevParams;
        Imbe(Object c) throws Exception {
            codec = c; getAudio = c.getClass().getMethod("getAudio", byte[].class); reset = c.getClass().getMethod("reset"); getAudio.setAccessible(true); reset.setAccessible(true);
            try { // JMBE's own FEC statistics for each frame (Golay/Hamming corrected bit count)
                ClassLoader cl = c.getClass().getClassLoader();
                Class<?> fr = cl.loadClass("jmbe.codec.imbe.IMBEFrame"), mp = cl.loadClass("jmbe.codec.imbe.IMBEModelParameters");
                frameCtor = fr.getConstructor(byte[].class); paramsCtor = mp.getConstructor();
                getParams = fr.getMethod("getModelParameters", mp);
                errTotal = cl.loadClass("jmbe.codec.MBEModelParameters").getMethod("getErrorCountTotal");
                try { errC0 = mp.getMethod("getErrorCountCoset0"); repeat = mp.getMethod("repeatRequired"); mute = mp.getMethod("requiresMuting"); }
                catch (Throwable t) { errC0 = null; }
                prevParams = paramsCtor.newInstance();
                try { fErrTotal = fr.getDeclaredField("mErrorCountTotal"); fErrTotal.setAccessible(true); fErrors = fr.getDeclaredField("mErrors"); fErrors.setAccessible(true); }
                catch (Throwable t) { fErrTotal = null; fErrors = null; }
            } catch (Throwable t) { frameCtor = null; }
        }
        /** 18-byte IMBE frame -> 160 samples at 8 kHz, range -1..1. */
        public float[] decode(byte[] frame) { try { return (float[]) getAudio.invoke(codec, (Object) frame); } catch (Exception e) { return new float[160]; } }
        /** Bit errors corrected by the IMBE frame's Golay/Hamming codes (0 = clean, about 14 = random noise), or -1 if unknown. */
        public int errors(byte[] frame) {
            if (frameCtor == null) return -1;
            try { Object p = getParams.invoke(frameCtor.newInstance((Object) frame), prevParams); prevParams = p; return (Integer) errTotal.invoke(p); }
            catch (Throwable t) { return -1; }
        }
        /**
         * FEC statistics of one frame, as the TIA-102.BABA decoder rules use them: {total corrected bits eT,
         * errors in the most important Golay word e0, 1 if JMBE will repeat the previous frame (e0 >= 2 and
         * eT >= 10 + 40 x error rate), 1 if JMBE mutes (smoothed error rate > 8.75 %)}. Null if unknown.
         */
        public int[] stats(byte[] frame) {
            if (frameCtor == null) return null;
            try {
                Object fr = frameCtor.newInstance((Object) frame);
                Object p = getParams.invoke(fr, prevParams); prevParams = p;
                // This frame's own counts. (The model parameters' counts are copied from the previous frame when JMBE
                // repeats one, so 1.1.2 reported runs of identical, stale numbers.)
                int eT, e0;
                if (fErrTotal != null) { eT = fErrTotal.getInt(fr); int[] e = (int[]) fErrors.get(fr); e0 = e != null && e.length > 0 ? e[0] : -1; }
                else { eT = (Integer) errTotal.invoke(p); e0 = errC0 == null ? -1 : (Integer) errC0.invoke(p); }
                if (errC0 == null) return new int[]{eT, e0, 0, 0};
                return new int[]{eT, e0, (Boolean) repeat.invoke(p) ? 1 : 0, (Boolean) mute.invoke(p) ? 1 : 0};
            } catch (Throwable t) { return null; }
        }
        /** Total corrected-bit count of the model parameters from the last stats() call (what 1.1.2 used; stale on repeats). */
        public int errorsOfLastModel() { try { return prevParams == null ? -1 : (Integer) errTotal.invoke(prevParams); } catch (Throwable t) { return -1; } }
        public void reset() { try { reset.invoke(codec); prevParams = paramsCtor == null ? null : paramsCtor.newInstance(); } catch (Exception ignored) {} }
    }

    public static synchronized Imbe newImbe() {
        if (jmbeLibrary == null) return null;
        try {
            Object c = jmbeLibrary.getClass().getMethod("getAudioConverter", String.class).invoke(jmbeLibrary, "IMBE");
            return new Imbe(c);
        } catch (Exception e) { return null; }
    }

    /**
     * AMBE+2 half-rate (Phase 2 TDMA) decoder from JMBE 2.0 (codec "AMBE 3600 x 2450"). Input: one 72-bit voice frame as
     * sent in a 4V/2V burst after descrambling (9 bytes, first bit = MSB of byte 0); JMBE does the deinterleaving, the
     * Golay(24,12) / Golay(23,12) error correction and the PN demodulation of the second codeword itself.
     */
    public static final class Ambe implements VoiceCodec {
        final Object codec; final Method getAudio, reset; Constructor<?> frameCtor; Method errors;
        Ambe(Object c) throws Exception {
            codec = c; getAudio = c.getClass().getMethod("getAudio", byte[].class); reset = c.getClass().getMethod("reset");
            try { Class<?> fr = c.getClass().getClassLoader().loadClass("jmbe.codec.ambe.AMBEFrame"); frameCtor = fr.getConstructor(byte[].class); errors = fr.getMethod("getErrors"); }
            catch (Throwable t) { frameCtor = null; }
        }
        public float[] decode(byte[] frame) { try { return (float[]) getAudio.invoke(codec, (Object) frame); } catch (Exception e) { return new float[160]; } }
        /** {corrected bits in both Golay words, errors in the first (most important) word, 0, 1 if the frame is beyond repair}. */
        public int[] stats(byte[] frame) {
            if (frameCtor == null) return null;
            try {
                int[] e = (int[]) errors.invoke(frameCtor.newInstance((Object) frame));
                int e0 = e.length > 0 ? e[0] : 0, e1 = e.length > 1 ? e[1] : 0;
                return new int[]{e0 + e1, e0, 0, e0 >= 4 ? 1 : 0};
            } catch (Throwable t) { return null; }
        }
        public String codecName() { return "AMBE+2"; }
        public void reset() { try { reset.invoke(codec); } catch (Exception ignored) {} }
    }

    public static synchronized Ambe newAmbe() {
        if (jmbeLibrary == null) return null;
        try {
            if (!(boolean) jmbeLibrary.getClass().getMethod("supports", String.class).invoke(jmbeLibrary, "AMBE 3600 x 2450")) return null;
            return new Ambe(jmbeLibrary.getClass().getMethod("getAudioConverter", String.class).invoke(jmbeLibrary, "AMBE 3600 x 2450"));
        } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------------ LAME MP3 (8 kHz mono)
    private static Constructor<?> lameCtor;
    private static Object monoMode;
    private static boolean lameTried;

    public static synchronized boolean mp3Available() {
        if (!lameTried) {
            lameTried = true;
            try {
                ClassLoader cl = new URLClassLoader(jars("java-lame").toArray(new URL[0]), EngineLibs.class.getClassLoader());
                Class<?> enc = cl.loadClass("net.sourceforge.lame.lowlevel.LameEncoder");
                Class<?> mode = cl.loadClass("net.sourceforge.lame.mp3.MPEGMode");
                monoMode = Enum.valueOf((Class) mode, "MONO");
                lameCtor = enc.getConstructor(AudioFormat.class, int.class, mode, int.class, boolean.class);
            } catch (Throwable t) { lameCtor = null; }
        }
        return lameCtor != null;
    }

    /** Streaming MP3 encoder for 8 kHz 16-bit mono PCM. */
    public static final class Mp3 {
        final Object enc; final Method encode, finish; final byte[] out; final int pcmChunk;
        public final int sampleRate;
        public Mp3(int kbps) throws Exception { this(kbps, 8000); }
        /** Mono CBR MP3 at kbps from 16-bit PCM at sampleRate (8000 for feeds/OpenMHz; 22050 for Broadcastify Calls). */
        public Mp3(int kbps, int sampleRate) throws Exception {
            if (!mp3Available()) throw new IllegalStateException("MP3 encoder (app/lib/java-lame jar) not found - run the installer again");
            this.sampleRate = sampleRate;
            enc = lameCtor.newInstance(new AudioFormat(sampleRate, 16, 1, true, false), kbps, monoMode, 5, false);
            if (sampleRate != 8000) keepSampleRate(enc, sampleRate, kbps);
            encode = enc.getClass().getMethod("encodeBuffer", byte[].class, int.class, int.class, byte[].class);
            finish = enc.getClass().getMethod("encodeFinish", byte[].class);
            pcmChunk = (int) enc.getClass().getMethod("getPCMBufferSize").invoke(enc);
            out = new byte[(int) enc.getClass().getMethod("getMP3BufferSize").invoke(enc)];
        }
        /**
         * LAME picks its own output rate for low bitrates (22050 Hz in, 16 kb/s -> 16000 Hz out). Broadcastify Calls wants
         * 22050 Hz, so the output rate is set to the input rate on the encoder's LAME flags and LAME's parameters are set up again.
         */
        static void keepSampleRate(Object enc, int rate, int kbps) throws Exception {
            java.lang.reflect.Field f = enc.getClass().getDeclaredField("lame");
            f.setAccessible(true);
            Object lame = f.get(enc);
            Object flags = lame.getClass().getMethod("getFlags").invoke(lame);
            flags.getClass().getMethod("setOutSampleRate", int.class).invoke(flags, rate);
            flags.getClass().getMethod("setBitRate", int.class).invoke(flags, kbps);
            flags.getClass().getField("compression_ratio").setFloat(flags, 0f);   // else the first set-up's ratio overrides the bitrate
            lame.getClass().getMethod("initParams").invoke(lame);
            int out = (int) flags.getClass().getMethod("getOutSampleRate").invoke(flags), br = (int) flags.getClass().getMethod("getBitRate").invoke(flags);
            if (out != rate || br != kbps) throw new IllegalStateException("MP3 encoder would not keep " + rate + " Hz / " + kbps + " kb/s (gives " + out + " Hz / " + br + " kb/s)");
        }

        /** Encodes PCM (little-endian 16-bit) and returns the MP3 bytes produced so far. */
        public byte[] encode(byte[] pcm, int len) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            try {
                for (int o = 0; o < len; o += pcmChunk) {
                    int n = (int) encode.invoke(enc, pcm, o, Math.min(pcmChunk, len - o), out);
                    bo.write(out, 0, n);
                }
            } catch (Exception ignored) {}
            return bo.toByteArray();
        }
        public byte[] finish() { try { int n = (int) finish.invoke(enc, out); return Arrays.copyOf(out, n); } catch (Exception e) { return new byte[0]; } }
    }

    public static byte[] pcm16(float[] s, int n) {
        byte[] b = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            int v = Math.round(Math.max(-1f, Math.min(1f, s[i])) * 32767f);
            b[2 * i] = (byte) v; b[2 * i + 1] = (byte) (v >> 8);
        }
        return b;
    }
}
