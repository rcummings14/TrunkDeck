package p25monitor.rr;

/** A RadioReference request that did not work, with a message meant for the user (never contains the password or key). */
public final class RrException extends Exception {
    public enum Kind {
        /** No app key in this build / on this computer. */
        NO_KEY,
        /** RadioReference refused the app key. */
        BAD_KEY,
        /** Wrong username or password. */
        LOGIN,
        /** The account has no (or an expired) Premium subscription. */
        NOT_PREMIUM,
        /** No connection, timeout, DNS ... */
        NETWORK,
        /** Any other SOAP fault from RadioReference (its own text is shown). */
        FAULT,
        /** An answer TrunkDeck could not read. */
        BAD_RESPONSE
    }
    public final Kind kind;
    /** RadioReference's own fault text, if any. */
    public final String detail;

    public RrException(Kind kind, String detail) { super(message(kind, detail)); this.kind = kind; this.detail = detail == null ? "" : detail; }

    static String message(Kind k, String d) {
        String rr = d == null || d.isBlank() ? "" : " (RadioReference said: \"" + d.strip() + "\")";
        return switch (k) {
            case NO_KEY -> "RadioReference setup isn't available in this build: no RadioReference app key was found.";
            case BAD_KEY -> "RadioReference did not accept this build's app key" + rr + ". The key may be wrong, not approved yet, or disabled. "
                    + "Check it on the Engine page (Advanced view) - RadioReference app key.";
            case LOGIN -> "RadioReference did not accept that username and password" + rr + ". Check them (they are the ones you use on radioreference.com).";
            case NOT_PREMIUM -> "This RadioReference account has no active Premium subscription" + rr + ". RadioReference only lets Premium members "
                    + "download system data into apps. You can still enter your system by hand on the Systems page.";
            case NETWORK -> "Could not reach RadioReference" + (d == null || d.isBlank() ? "" : " (" + d.strip() + ")") + ". Check the internet connection and try again.";
            case FAULT -> "RadioReference returned an error" + rr + ".";
            case BAD_RESPONSE -> "RadioReference sent an answer TrunkDeck could not read" + (d == null || d.isBlank() ? "" : " (" + d.strip() + ")") + ".";
        };
    }

    /**
     * Sorts a SOAP fault into a kind from RadioReference's fault text. RR's exact fault strings are not published, so
     * this goes by keywords; anything unrecognised stays a FAULT and shows RR's own text.
     */
    public static Kind classify(String code, String text) {
        String s = ((code == null ? "" : code) + " " + (text == null ? "" : text)).toLowerCase(java.util.Locale.ROOT);
        if (s.contains("app key") || s.contains("appkey") || s.contains("application key") || s.contains("api key") || s.contains("invalid key")
                || s.contains("key is") || s.contains("key not")) return Kind.BAD_KEY;
        if (s.contains("premium") || s.contains("subscri") || s.contains("expired") || s.contains("not paid")) return Kind.NOT_PREMIUM;
        if (s.contains("password") || s.contains("username") || s.contains("user name") || s.contains("login") || s.contains("log in")
                || s.contains("authenticat") || s.contains("invalid user") || s.contains("credentials")) return Kind.LOGIN;
        return Kind.FAULT;
    }
}
