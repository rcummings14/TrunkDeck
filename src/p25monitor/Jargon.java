package p25monitor;

import javax.swing.*;
import javax.swing.table.JTableHeader;
import java.awt.event.MouseEvent;
import java.util.*;

/** Plain-language explanations of radio / engine jargon, shown as tooltips in both views (1.4.5). */
public final class Jargon {
    private Jargon() {}

    static final Map<String, String> TIPS = new HashMap<>();
    static void put(String text, String... keys) { for (String k : keys) TIPS.put(k.toLowerCase(Locale.ROOT), text); }
    static {
        put("Network Access Code: a 3-digit hex number every transmission on this system carries, so receivers ignore other systems on the same frequency. On a trunked system TrunkDeck reads it from the control channel. On a conventional system, type it to ignore other traffic, or add it after one channel (155.2500:2D5).", "NAC");
        put("Trunked follows a control channel and hops to each call. Conventional stays on the frequencies you type. There is no control channel.", "Kind");
        put("The frequencies this conventional system uses. Separate them with commas. Add a NAC after a colon when one channel uses its own: 155.2500:2D5.", "Channels MHz");
        put("Wide Area Communications Network ID: identifies the whole P25 network. Sites with the same WACN and System ID share one talkgroup list. TrunkDeck also reads it from the control channel.", "WACN");
        put("System ID: identifies this trunked system inside its network (WACN). Sites with the same WACN and System ID share one talkgroup list. TrunkDeck also reads it from the control channel.", "Sys ID", "SysID");
        put("Radio Frequency SubSystem: a group of sites run together. For your reference; read from the control channel.", "RFSS");
        put("Site number: which tower / site of the system you are receiving. For your reference.", "Site #");
        put("The control channel is the always-on data channel that tells radios which frequency each call is on. TrunkDeck listens to it and follows the calls.", "Control channel", "Control channel(s) MHz");
        put(SignalMeter.EXPLAIN, "Signal");
        put("How the radio signal is turned back into data. Auto tries both kinds (C4FM, and LSM/CQPSK used by simulcast systems) and keeps the one that works better - leave it on Auto unless told otherwise.", "Demodulator");
        put("How many calls are decoded at the same time. Each one uses a little CPU; calls must also be inside a tuner's range.", "Max simultaneous calls");
        put("Hang time: after someone stops talking, how long the speaker stays on that talkgroup waiting for the reply before switching to another call.", "Hang time", "HANG");
        put("Frequency correction for a cheap tuner's crystal, in parts per million. If calls are hard to decode, use the value suggested on this page.", "PPM");
        put("How much the tuner amplifies the antenna signal. Too low: weak, noisy calls. Too high: overload and garbled calls. Auto (AGC) is a good start; this page suggests a value.", "Gain");
        put("What a tuner is used for: auto (TrunkDeck decides), control (control channel only), voice (calls only) or off.", "Role");
        put("Bias-T: 4.5 V power on the antenna jack for an antenna amplifier (LNA). Leave off unless yours needs it.", "Bias");
        put("Which tuner (dongle) is receiving this call: T1, T2 ...", "Tuner");
        put("Signal level of the call's channel. Higher is stronger; very low levels decode badly.", "Level");
        put("Bit errors per voice frame: how many bits had to be corrected. 0-2 is a clean signal; above about 5 the audio may garble.", "Bit errors/frame");
        put("Frequency the call is on, in MHz. TS1 / TS2: the time slot of a Phase 2 (TDMA) call - two calls share one frequency.", "Freq MHz", "Frequency");
        put("The talkgroup number in hexadecimal (base 16), as some lists and radios show it.", "Hex");
        put("Radio Reference mode: D = digital, E = encrypted (can't be heard), T = TDMA (Phase 2). Encrypted talkgroups show in violet.", "Mode");
        put("Priority 1-100 (1 = most important): when calls overlap, the speaker plays the higher priority one.", "Priority");
        put("Record: save every call on this talkgroup as an audio file. Play it from the Recordings page or the call log.", "Rec");
        put("Mute / lock out: never play this talkgroup on the speaker and don't follow its calls.", "Mute");
        put("Favorite: listen to it in \"Favorites only\" mode, highlighted in lists.", "\u2605", "Fav");
        put("Colour of this talkgroup's name and number while it is on the air. Default keeps the display theme. The talkgroup list itself does not change colour. Sites of the same system share it.", "Color");
        put("The talkgroup number (decimal), as on Radio Reference.", "TG", "Decimal");
        put("The radio (unit ID) talking. Name radios with a right-click.", "Radio", "Unit / radio");
        put("Mount point: the path of your stream on the Icecast server, given by the stream provider (Broadcastify: in your feed's technicals).", "Mount", "Mount point");
        put("MP3 bitrate: how much audio data per second. Broadcastify feeds use 16 kb/s.", "Bitrate", "Bitrate kbps");
        put("The sample rate of the MP3 sent to the server. 16000 Hz is what Broadcastify expects; change it only if the feed plays too fast or too slow.", "MP3 sample rate");
        put("End of transmission: how long a silence (with no end signal from the radio) ends one transmission and starts its upload.", "EOT");
    }

    /** The explanation for a term, or null. */
    public static String tip(String term) { return term == null ? null : TIPS.get(term.trim().toLowerCase(Locale.ROOT)); }

    /** Sets c's tooltip to the explanation of term (if there is one) and returns c. */
    public static <T extends JComponent> T explain(T c, String term) { String t = tip(term); if (t != null) c.setToolTipText(html(t)); return c; }

    static String html(String t) { return "<html><body style='width:300px'>" + t.replace("&", "&amp;").replace("<", "&lt;") + "</body></html>"; }

    /** Column headers of t show the explanation of their title (if any). */
    public static void headerTips(JTable t) {
        t.setTableHeader(new JTableHeader(t.getColumnModel()) {
            @Override public String getToolTipText(MouseEvent e) {
                int c = columnAtPoint(e.getPoint());
                if (c < 0) return null;
                var col = getColumnModel().getColumn(c);
                if (String.valueOf(col.getIdentifier()).startsWith("stream:"))
                    return html("Tick to send this talkgroup's calls to the stream \"" + col.getHeaderValue() + "\" (takes effect from the next transmission; feeds stay connected).");
                String s = tip(String.valueOf(col.getHeaderValue()));
                return s == null ? null : html(s);
            }
        });
    }
}
