package p25monitor.nativeengine;

import java.io.*;
import java.nio.file.*;

/** Writes finished calls to the shared recordings folder (MP3 when the LAME encoder is available, else WAV). */
final class Recorder {
    static Path write(float[] pcm, int n, String stamp, int tg, int src) throws IOException {
        Path dir = p25monitor.App.recordingDir();
        Files.createDirectories(dir);
        String base = stamp + "_TrunkDeck_TO_" + tg + (src > 0 ? "_FROM_" + src : "");
        byte[] pcm16 = EngineLibs.pcm16(pcm, n);
        if (EngineLibs.mp3Available()) {
            try {
                EngineLibs.Mp3 m = new EngineLibs.Mp3(16);
                Path p = dir.resolve(base + ".mp3");
                try (OutputStream o = new BufferedOutputStream(Files.newOutputStream(p))) { o.write(m.encode(pcm16, pcm16.length)); o.write(m.finish()); }
                return p;
            } catch (Exception ignored) {}
        }
        Path p = dir.resolve(base + ".wav");
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(p)))) {
            o.writeBytes("RIFF"); o.writeInt(Integer.reverseBytes(36 + pcm16.length)); o.writeBytes("WAVEfmt ");
            o.writeInt(Integer.reverseBytes(16)); o.writeShort(Short.reverseBytes((short) 1)); o.writeShort(Short.reverseBytes((short) 1));
            o.writeInt(Integer.reverseBytes(8000)); o.writeInt(Integer.reverseBytes(16000)); o.writeShort(Short.reverseBytes((short) 2)); o.writeShort(Short.reverseBytes((short) 16));
            o.writeBytes("data"); o.writeInt(Integer.reverseBytes(pcm16.length)); o.write(pcm16);
        }
        return p;
    }
}
