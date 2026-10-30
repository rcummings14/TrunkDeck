package p25monitor;

import javax.swing.*;
import java.util.*;

/** Feeds made-up calls into the dashboard so you can see how it works without a radio. Nothing is sent to the engine. */
public class Demo {
    final MainFrame f;
    final Random rnd = new Random();
    int left = 60;
    Demo(MainFrame f) { this.f = f; }

    void start() {
        f.show("Monitor");
        f.demoUntil = System.currentTimeMillis() + 62_000;
        List<Talkgroup> pool = new ArrayList<>(f.app.current == null ? List.of() : f.app.current.talkgroups);
        if (pool.isEmpty()) for (int i = 0; i < 6; i++) { Talkgroup t = new Talkgroup(); t.decimal = 1000 + i * 37; pool.add(t); }
        javax.swing.Timer msg = new javax.swing.Timer(250, null);
        msg.addActionListener(e -> { f.display.onMessages(6 + rnd.nextInt(5), "DEMO TSBK NETWORK STATUS BROADCAST"); if (System.currentTimeMillis() > f.demoUntil) msg.stop(); });
        msg.start();
        javax.swing.Timer t = new javax.swing.Timer(1000, null);
        final int[] hold = {0};
        final CallEvent[] cur = {null};
        t.addActionListener(e -> {
            if (System.currentTimeMillis() > f.demoUntil) { t.stop(); f.toast("Demo finished."); return; }
            if (cur[0] != null && hold[0]-- > 0) { cur[0].receivedAt = System.currentTimeMillis(); f.display.onCall(cur[0], f.app.current == null ? null : f.app.current.find(cur[0].talkgroup)); return; }
            Talkgroup tg = pool.get(rnd.nextInt(pool.size()));
            CallEvent c = new CallEvent();
            c.protocol = "APCO-25";
            c.talkgroup = tg.decimal;
            c.to = String.valueOf(tg.decimal);
            c.radioId = 1_000_000 + rnd.nextInt(900_000);
            c.from = String.valueOf(c.radioId);
            c.frequencyMHz = f.app.current != null && !f.app.current.controlChannelsMHz.isEmpty() ? f.app.current.controlChannelsMHz.get(0) + 0.0125 * (1 + rnd.nextInt(40)) : 413.9;
            c.event = rnd.nextInt(12) == 0 ? "Encrypted Group Call" : "Group Call";
            c.timeslot = rnd.nextBoolean() ? "" : "TS:" + (1 + rnd.nextInt(2));
            c.details = "DEMO";
            cur[0] = c;
            hold[0] = 2 + rnd.nextInt(5);
            f.onEvent(c);
        });
        t.start();
        f.toast("Demo running for 60 seconds - these calls are made up.");
    }
}
