package p25monitor;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Optional live colour: presets, readable text, saved on the shared talkgroup list, ignored when Default. */
public final class TalkgroupColorTests {
    static int pass, fail;
    static void check(boolean ok, String what) {
        if (ok) pass++;
        else { fail++; System.out.println("  FAIL " + what); }
    }

    public static void main(String[] a) throws Exception {
        presets();
        readable();
        csvAndShare();
        System.out.println("TalkgroupColorTests: " + pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    static void presets() {
        System.out.println("1. Default keeps the theme; a preset is a colour");
        check(TalkgroupColors.parse(null) == null && TalkgroupColors.parse("") == null && TalkgroupColors.parse("Default") == null, "blank and Default are no colour");
        check(TalkgroupColors.parse("not-a-colour") == null, "an unknown value is no colour");
        Color amber = TalkgroupColors.parse("Amber");
        check(amber != null && amber.equals(TalkgroupColors.parse("#C89010")), "Amber is the preset");
        check("".equals(TalkgroupColors.store("Default")) && "#C89010".equals(TalkgroupColors.store("Amber")), "saving Default is blank, Amber is its hex");
        check("Amber".equals(TalkgroupColors.label("#C89010")) && "Default".equals(TalkgroupColors.label("")), "labels");
        check(TalkgroupColors.parse("#0E8") != null, "a short hex is accepted");
    }

    static void readable() {
        System.out.println("2. writing stays readable on the live background");
        for (String[] p : TalkgroupColors.PRESETS) {
            if (p[1].isEmpty()) continue;
            Color bg = TalkgroupColors.background(TalkgroupColors.parse(p[0]));
            Color ink = TalkgroupColors.ink(bg);
            double contrast = contrast(ink, bg);
            check(contrast >= 4.5, p[0] + " contrast " + String.format("%.1f", contrast));
            check(TalkgroupColors.luminance(bg) < TalkgroupColors.luminance(TalkgroupColors.parse(p[0])), p[0] + " background is darker than the swatch");
        }
        Color paper = new Color(0xF4E7A8);
        check(TalkgroupColors.ink(paper).getRed() < 40, "a light background gets dark text");
        Color night = new Color(0x101820);
        check(TalkgroupColors.ink(night).getRed() > 200, "a dark background gets light text");
    }

    static double contrast(Color a, Color b) {
        double l1 = TalkgroupColors.luminance(a), l2 = TalkgroupColors.luminance(b);
        double hi = Math.max(l1, l2), lo = Math.min(l1, l2);
        return (hi + 0.05) / (lo + 0.05);
    }

    static void csvAndShare() throws Exception {
        System.out.println("3. the colour is stored with the talkgroup list and shared by sites of one system");
        Talkgroup one = new Talkgroup();
        one.decimal = 100;
        one.alphaTag = "Sample One";
        one.color = TalkgroupColors.store("Amber");
        Talkgroup plain = new Talkgroup();
        plain.decimal = 240;
        plain.alphaTag = "Sample Two";
        Path dir = Files.createTempDirectory("tg-color");
        Path file = dir.resolve("talkgroups.csv");
        TalkgroupCsv.write(file, List.of(one, plain));
        String text = Files.readString(file);
        check(text.startsWith("Decimal,Hex,Alpha Tag") && text.contains(",Color"), "the file has a Color column");
        TalkgroupCsv.Result back = TalkgroupCsv.read(file);
        check(back.talkgroups.size() == 2, "both rows read back");
        check("#C89010".equals(back.talkgroups.get(0).color), "Amber survived");
        check(back.talkgroups.get(1).color == null || back.talkgroups.get(1).color.isEmpty(), "Default stayed blank");
        Path old = dir.resolve("old.csv");
        Files.writeString(old, "Decimal,Alpha Tag\r\n100,Sample One\r\n");
        TalkgroupCsv.Result legacy = TalkgroupCsv.read(old);
        check(legacy.talkgroups.size() == 1 && (legacy.talkgroups.get(0).color == null || legacy.talkgroups.get(0).color.isEmpty()), "a file with no Color column still loads");

        RadioSystem a = site("north", "BEE00", "001");
        RadioSystem b = site("east", "BEE00", "001");
        Talkgroup shared = new Talkgroup();
        shared.decimal = 100;
        shared.alphaTag = "Sample One";
        a.talkgroups.add(shared);
        Talkgroup copy = new Talkgroup();
        copy.decimal = 100;
        copy.alphaTag = "Sample One";
        b.talkgroups.add(copy);
        List<RadioSystem> systems = new ArrayList<>();
        systems.add(a);
        systems.add(b);
        TalkgroupLists.bind(systems);
        check(a.talkgroups == b.talkgroups, "the two sites use one list");
        a.talkgroups.get(0).color = TalkgroupColors.store("Green");
        check("#1E9E55".equals(b.find(100).color), "the other site sees the same colour");
        Talkgroup incoming = new Talkgroup();
        incoming.decimal = 100;
        incoming.color = "";
        TalkgroupLists.mergeInto(a.talkgroups, List.of(incoming));
        check("#1E9E55".equals(a.find(100).color), "a blank colour does not erase one already set");
    }

    static RadioSystem site(String id, String wacn, String sys) {
        RadioSystem s = new RadioSystem();
        s.id = id;
        s.name = "Sample " + id;
        s.wacn = wacn;
        s.sysId = sys;
        s.talkgroups = new ArrayList<>();
        return s;
    }
}
