package p25monitor;

/** Which calls the engine follows. */
public enum ListenMode {
    ALL("Everything"), LISTED("Only talkgroups in my list"), FAVORITES("Favorites only");
    public final String label;
    ListenMode(String l) { label = l; }
    @Override public String toString() { return label; }
}
