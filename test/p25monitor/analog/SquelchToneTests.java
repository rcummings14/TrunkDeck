package p25monitor.analog;

import p25monitor.RadioSystem;
import java.util.*;

/** Adjustable analog squelch level (saved per system) and the CTCSS tone formats people type or paste. */
public final class SquelchToneTests {
    static int pass, fail;
    static void check(boolean ok, String what) { if (ok) pass++; else fail++; System.out.println((ok ? "  ok    " : "  FAIL  ") + what); }

    public static void main(String[] a) {
        try { run(); } catch (Throwable t) { t.printStackTrace(); fail++; }
        System.out.println("SquelchToneTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    /** FM carrier with a 1 kHz tone plus noise of the given amplitude, 48 kHz I/Q. */
    static float[][] fm(int n, double noise, long seed) {
        Random r = new Random(seed);
        float[] i = new float[n], q = new float[n];
        double ph = 0;
        for (int k = 0; k < n; k++) {
            ph += 2 * Math.PI * 2500 * Math.sin(2 * Math.PI * 1000 * k / 48000.0) / 48000;
            i[k] = (float) (Math.cos(ph) + noise * r.nextGaussian());
            q[k] = (float) (Math.sin(ph) + noise * r.nextGaussian());
        }
        return new float[][]{i, q};
    }

    static float[][] noiseOnly(int n, long seed) {
        Random r = new Random(seed);
        float[] i = new float[n], q = new float[n];
        for (int k = 0; k < n; k++) { i[k] = (float) r.nextGaussian(); q[k] = (float) r.nextGaussian(); }
        return new float[][]{i, q};
    }

    static boolean opens(int level, float[][] s) {
        boolean[] open = {false};
        FmReceiver rx = new FmReceiver(0, new FmReceiver.Listener() {
            public void start(double t) { open[0] = true; }
            public void audio(float[] p) {}
            public void end() {}
        });
        rx.setSquelch(level);
        rx.baseband(s[0], s[1], s[0].length);
        return open[0];
    }

    static void run() throws Exception {
        // level 5 is the original squelch
        FmReceiver d = new FmReceiver(0, new FmReceiver.Listener() { public void start(double t) {} public void audio(float[] p) {} public void end() {} });
        check(d.openHz == 2500 && d.closeHz == 3500 && d.openBlocks == 3, "a new receiver keeps the original squelch");
        d.setSquelch(5);
        check(d.openHz == 2500 && d.closeHz == 3500 && d.openBlocks == 3, "level 5 is the original squelch (open < 2500 Hz for 60 ms, close > 3500 Hz)");
        d.setSquelch(10);
        check(d.openHz == 1000 && d.openBlocks == 8, "level 10 needs a quieter signal for longer");
        d.setSquelch(1);
        check(d.openHz == 3700 && d.closeHz == 4700, "level 1 opens easiest and still closes on noise (about 5500 Hz)");
        d.setSquelch(99);
        check(d.openHz == 1000, "out-of-range levels are clamped");

        // behaviour: noise never opens at any level; a clean signal opens at every level; a weak signal opens only at low levels
        float[][] noise = noiseOnly(48000 * 3, 1);
        boolean anyNoise = false;
        for (int l = 1; l <= 10; l++) anyNoise |= opens(l, noise);
        check(!anyNoise, "pure noise does not open the squelch at any level");
        float[][] clean = fm(48000, 0.02, 2);
        boolean allClean = true;
        for (int l = 1; l <= 10; l++) allClean &= opens(l, clean);
        check(allClean, "a clean signal opens the squelch at every level");
        float[][] weak = fm(48000 * 2, 0.15, 3);
        boolean lowOpens = opens(1, weak), highOpens = opens(10, weak);
        check(lowOpens && !highOpens, "a noisy signal opens at level 1 but not at level 10 (" + lowOpens + "/" + highOpens + ")");

        // saved with the system
        RadioSystem s = new RadioSystem();
        s.name = "FM"; s.kind = RadioSystem.ANALOG; s.channels.add(new RadioSystem.Channel(154.8225, "", ""));
        s.squelch = 8;
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("sq");
        s.save(dir);
        check(RadioSystem.load(dir).squelch == 8, "squelch level is saved and loaded");
        java.nio.file.Path f = dir.resolve("system.properties");
        java.nio.file.Files.writeString(f, java.nio.file.Files.readString(f).replaceAll("(?m)^squelch=.*$", ""));
        check(RadioSystem.load(dir).squelch == 5, "systems saved before the setting existed load with level 5");

        // tone formats
        String[] good = {"206.5", "206.50", "PL 206.5", "206.5 PL", "206.5PL", "206.5 Hz", "206.5Hz", "CTCSS 206.5", "206,5", " 206.5 ", "pl206.5"};
        List<String> bad = new ArrayList<>();
        for (String g : good) if (FmReceiver.parseTone(g) != 206.5) bad.add(g + "->" + FmReceiver.parseTone(g));
        check(bad.isEmpty(), "206.5 accepted in every common form " + bad);
        check(FmReceiver.parseTone("206.4") < 0 && FmReceiver.parseTone("123 DPL") < 0 && FmReceiver.parseTone("") == 0, "non-standard tones and DCS are still refused; blank is carrier squelch");
        List<RadioSystem.Channel> ch = RadioSystem.parseChannelText("154.8225:206.5 PL Fire, 154.8300:206.5, 154.8400:206.50 Ops");
        check(ch.size() == 3 && ch.get(0).nac.equals("206.5") && ch.get(0).name.equals("Fire") && ch.get(1).nac.equals("206.5") && ch.get(2).nac.equals("206.5") && ch.get(2).name.equals("Ops"),
                "channel list: 154.8225:206.5 PL Fire keeps the tone and the name " + ch.stream().map(c -> c.mhz + ":" + c.nac + "[" + c.name + "]").toList());
        // the tone typed after the last frequency, with or without the colon and spaces
        String[] lastForms = {"155.2500, 155.3700, 154.8225:206.5", "155.2500, 155.3700, 154.8225 206.5", "155.2500, 155.3700, 154.8225: 206.5",
                "155.2500, 155.3700, 154.8225 : 206.5", "155.2500 155.3700 154.8225 206.5", "155.2500, 155.3700, 154.8225 206.5 PL"};
        List<String> wrong = new ArrayList<>();
        for (String form : lastForms) {
            List<RadioSystem.Channel> c = RadioSystem.parseChannelText(form);
            if (c.size() != 3 || c.get(2).mhz != 154.8225 || !c.get(2).nac.equals("206.5") || !c.get(0).nac.isEmpty() || !c.get(2).name.isEmpty())
                wrong.add(form + " -> " + c.stream().map(x -> x.mhz + ":" + x.nac + "[" + x.name + "]").toList());
        }
        check(wrong.isEmpty(), "a tone after the last frequency is read the same with or without the colon " + wrong);
        List<RadioSystem.Channel> named = RadioSystem.parseChannelText("154.8225 206.5 Fire Ops");
        check(named.size() == 1 && named.get(0).nac.equals("206.5") && named.get(0).name.equals("Fire Ops"), "154.8225 206.5 Fire Ops keeps tone and name");
        List<RadioSystem.Channel> plain = RadioSystem.parseChannelText("155.2500 155.3700, 851.0125: 2D5 Fire");
        check(plain.size() == 3 && plain.get(0).nac.isEmpty() && plain.get(1).nac.isEmpty() && plain.get(2).nac.equals("2D5") && plain.get(2).name.equals("Fire"),
                "plain frequency lists and NACs still work " + plain.stream().map(x -> x.mhz + ":" + x.nac + "[" + x.name + "]").toList());
        s.channels.clear(); s.channels.addAll(ch); s.nac = "";
        check(s.toneFor(154_822_500L) == 206.5, "the 206.5 tone reaches the engine's tone squelch");
    }
}
