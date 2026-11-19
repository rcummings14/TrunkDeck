package p25monitor.motorola;

import java.util.Locale;

/**
 * Turns the stream of Type II OSWs into call grants and site facts.
 *
 * <p>A group call grant is two words: first an opcode word whose address is the talking radio (0x308 = analog voice,
 * 0x321 = digital voice), then a word whose command is the voice channel number, group flag set, address = talkgroup.
 * A channel word on its own is a grant update for a call already running (mode not stated). The low 4 bits of a
 * talkgroup address are status: 0x8 = encrypted, and the lower three bits 2, 4 or 5 = emergency, 3 or 4 = patch, 5 or
 * 7 = multiselect. A non-group channel word whose address is 0x1Fxx announces the control channel (preceded by 0x308
 * with the system ID). 0x2F8 is idle. On VHF/UHF ("OBT") systems the first word of a grant may instead name the radio's
 * transmit channel. Facts from trunk-recorder and gr-smartnet, see NOTICE.txt.
 */
public final class Type2Site {
    public interface Listener {
        /** {@code digital}: TRUE = P25 voice, FALSE = analog FM, null = not stated (a grant update). */
        void grant(int tg, int status, int src, int channel, long hz, Boolean digital, boolean update);
        /** Every decoded word, described for the message log. */
        void message(String text);
    }

    public static final int OP_ANALOG = 0x308, OP_DIGITAL = 0x321, OP_IDLE = 0x2F8, OP_SYSID = 0x30B, OP_PATCH = 0x340, OP_DATE = 0x322;

    private final Listener out;
    private final long ccHz;
    private final boolean auto;
    private Type2BandPlan plan;
    private Type2Frame.Osw prev;
    public int sysId = -1, ccChannel = -1;

    public Type2Site(Type2BandPlan plan, long ccHz, Listener out) {
        this.out = out; this.ccHz = ccHz;
        auto = plan.kind == Type2BandPlan.Kind.AUTO;
        this.plan = auto ? Type2BandPlan.of(Type2BandPlan.guess(ccHz)) : plan;
    }

    public Type2BandPlan plan() { return plan; }

    public static int talkgroup(int address) { return address & 0xFFF0; }
    public static boolean encrypted(int status) { return (status & 0x8) != 0; }
    public static boolean emergency(int status) { int o = status & 7; return o == 2 || o == 4 || o == 5; }
    public static boolean patch(int status) { int o = status & 7; return o == 3 || o == 4; }

    public void osw(Type2Frame.Osw w) {
        Type2Frame.Osw p = prev;
        prev = w;
        int cmd = w.command();
        if (plan.isChannel(cmd)) {
            long hz = plan.frequency(cmd);
            if (w.group()) {
                int tg = talkgroup(w.address()), st = w.address() & 0xF;
                if (p != null && (p.command() == OP_ANALOG || p.command() == OP_DIGITAL)) {
                    boolean dig = p.command() == OP_DIGITAL;
                    prev = null;
                    out.grant(tg, st, p.address(), cmd, hz, dig, false);
                    out.message(String.format(Locale.ROOT, "%s GROUP GRANT TG %d SRC %d CH %03X %s MHz%s", dig ? "DIGITAL" : "ANALOG", tg, p.address(), cmd, mhz(hz), flags(st)));
                } else if (p != null && plan.isTransmit(p.command())) {
                    prev = null;
                    out.grant(tg, st, p.address(), cmd, hz, null, false);
                    out.message(String.format(Locale.ROOT, "GROUP GRANT TG %d SRC %d CH %03X %s MHz%s", tg, p.address(), cmd, mhz(hz), flags(st)));
                } else {
                    out.grant(tg, st, -1, cmd, hz, null, true);
                    out.message(String.format(Locale.ROOT, "GROUP UPDATE TG %d CH %03X %s MHz%s", tg, cmd, mhz(hz), flags(st)));
                }
            } else if ((w.address() & 0xFF00) == 0x1F00) {
                ccChannel = cmd;
                if (p != null && p.command() == OP_ANALOG) sysId = p.address();
                learnPlan(cmd);
                out.message(String.format(Locale.ROOT, "CONTROL CHANNEL CH %03X %s MHz%s", cmd, mhz(plan.frequency(cmd)), sysId >= 0 ? String.format(" SYSID %04X", sysId) : ""));
            } else {
                out.message(String.format(Locale.ROOT, "PRIVATE CALL RADIO %d CH %03X %s MHz", w.address(), cmd, mhz(hz)));
            }
            return;
        }
        if (cmd == OP_SYSID && p != null && p.command() == OP_ANALOG) { sysId = p.address(); out.message(String.format(Locale.ROOT, "SYSTEM ID %04X", sysId)); return; }
        if (cmd == OP_IDLE) { out.message("IDLE"); return; }
        if (cmd == OP_PATCH) { out.message(String.format(Locale.ROOT, "PATCH %04X", w.address())); return; }
        if (cmd == OP_ANALOG || cmd == OP_DIGITAL) return;   // first half of a pair: described with the second
        out.message(String.format(Locale.ROOT, "OSW %s CMD %03X ADDR %d", w.group() ? "G" : "I", cmd, w.address()));
    }

    /** Auto band plan: the 800 MHz variant that puts the announced control channel number on the frequency we hear it on. */
    private void learnPlan(int ch) {
        if (!auto || ccHz <= 0 || plan.frequency(ch) == ccHz) return;
        for (Type2BandPlan.Kind k : new Type2BandPlan.Kind[]{Type2BandPlan.Kind.B800_REBAND, Type2BandPlan.Kind.B800_STANDARD, Type2BandPlan.Kind.B800_SPLINTER, Type2BandPlan.Kind.B900}) {
            Type2BandPlan c = Type2BandPlan.of(k);
            if (Math.abs(c.frequency(ch) - ccHz) < 1000) { plan = c; return; }
        }
    }

    static String flags(int st) {
        return (encrypted(st) ? " ENCRYPTED" : "") + (emergency(st) ? " EMERGENCY" : "") + (patch(st) ? " PATCH" : "");
    }

    static String mhz(long hz) { return hz <= 0 ? "?" : String.format(Locale.ROOT, "%.4f", hz / 1e6); }
}
