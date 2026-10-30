package p25monitor;

import java.awt.Desktop;
import java.io.File;
import java.lang.reflect.*;

/**
 * Plays recorded calls. WAV recordings play inside TrunkDeck (Java sound). MP3 recordings play inside TrunkDeck only
 * if the Java runtime has JavaFX media (loaded by reflection; the runtime the installers provide does not include
 * it), otherwise they open in the computer's own audio player.
 */
public final class AudioPlayer {
    private static Object player;           // javafx.scene.media.MediaPlayer
    private static boolean fxReady, fxFailed;
    private static double volume = 0.8;

    private AudioPlayer() {}

    public static synchronized boolean inAppAvailable() {
        if (fxReady) return true;
        if (fxFailed) return false;
        try {
            Class<?> platform = Class.forName("javafx.application.Platform");
            Class.forName("javafx.scene.media.MediaPlayer");
            try {
                platform.getMethod("startup", Runnable.class).invoke(null, (Runnable) () -> {});
            } catch (InvocationTargetException ite) {
                if (!(ite.getCause() instanceof IllegalStateException)) throw ite; // already started is fine
            }
            platform.getMethod("setImplicitExit", boolean.class).invoke(null, false);
            fxReady = true;
        } catch (Throwable t) {
            fxFailed = true;
        }
        return fxReady;
    }

    private static javax.sound.sampled.Clip clip;

    /** True if this file plays inside TrunkDeck (false: it opens in the system player). */
    public static boolean playsInApp(File f) { return f.getName().toLowerCase().endsWith(".wav") || inAppAvailable(); }

    public static synchronized void play(File f, Runnable onEnd) {
        stop();
        if (f.getName().toLowerCase().endsWith(".wav")) {
            try {
                javax.sound.sampled.AudioInputStream in = javax.sound.sampled.AudioSystem.getAudioInputStream(f);
                javax.sound.sampled.Clip c = javax.sound.sampled.AudioSystem.getClip();
                c.open(in);
                try {
                    var g = (javax.sound.sampled.FloatControl) c.getControl(javax.sound.sampled.FloatControl.Type.MASTER_GAIN);
                    g.setValue((float) Math.max(g.getMinimum(), 20 * Math.log10(Math.max(1e-4, volume))));
                } catch (Exception ignored) {}
                c.addLineListener(ev -> { if (ev.getType() == javax.sound.sampled.LineEvent.Type.STOP && onEnd != null) onEnd.run(); });
                c.start();
                clip = c;
                return;
            } catch (Exception e) { /* fall through to the system player */ }
        }
        if (!inAppAvailable()) {
            try { Desktop.getDesktop().open(f); } catch (Exception ignored) {}
            return;
        }
        runFx(() -> {
            try {
                Class<?> mediaC = Class.forName("javafx.scene.media.Media");
                Class<?> playerC = Class.forName("javafx.scene.media.MediaPlayer");
                Object media = mediaC.getConstructor(String.class).newInstance(f.toURI().toString());
                Object p = playerC.getConstructor(mediaC).newInstance(media);
                playerC.getMethod("setVolume", double.class).invoke(p, volume);
                if (onEnd != null) playerC.getMethod("setOnEndOfMedia", Runnable.class).invoke(p, onEnd);
                playerC.getMethod("play").invoke(p);
                synchronized (AudioPlayer.class) { player = p; }
            } catch (Throwable t) {
                try { Desktop.getDesktop().open(f); } catch (Exception ignored) {}
            }
        });
    }

    public static synchronized void stop() {
        if (clip != null) { try { clip.stop(); clip.close(); } catch (Exception ignored) {} clip = null; }
        Object p = player;
        player = null;
        if (p != null) runFx(() -> { try { p.getClass().getMethod("dispose").invoke(p); } catch (Throwable ignored) {} });
    }

    public static synchronized void setVolume(double v) {
        volume = v;
        Object p = player;
        if (p != null) runFx(() -> { try { p.getClass().getMethod("setVolume", double.class).invoke(p, v); } catch (Throwable ignored) {} });
    }

    private static void runFx(Runnable r) {
        try { Class.forName("javafx.application.Platform").getMethod("runLater", Runnable.class).invoke(null, r); }
        catch (Throwable t) { r.run(); }
    }
}
