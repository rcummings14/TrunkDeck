package p25monitor;

/** One call or event reported by the engine (shown in the live call log). */
public class CallEvent {
    public String timestamp = "", protocol = "", event = "", from = "", to = "", channel = "", details = "", eventId = "";
    public double frequencyMHz;
    public long durationMs;
    public int talkgroup = -1;
    public int radioId = -1;
    public String timeslot = "";
    /** A call that is listed but not the one on the speaker (it may still be decoded and recorded). */
    public boolean background;
    public long receivedAt = System.currentTimeMillis();

    public boolean isEncrypted() { return event.toLowerCase().contains("encrypt") || details.toUpperCase().contains("ENCRYPT"); }
    public boolean isEmergency() { return event.toUpperCase().contains("EMERGENCY") || details.toUpperCase().contains("EMERGENCY"); }
    public boolean isVoiceCall() {
        String l = event.toLowerCase();
        return l.contains("call") && !l.contains("data") && !l.contains("alert") && !l.contains("no tuner") && !l.contains("timeout");
    }
    public boolean isNoTuner() { return event.toLowerCase().contains("no tuner"); }
}
