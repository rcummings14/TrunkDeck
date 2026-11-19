package p25monitor.motorola;

import java.util.Locale;

/**
 * Motorola Type II channel number to frequency.
 *
 * <p>800 MHz: channels 0-0x2CF are 851.0125 + 25 kHz steps (standard); rebanded systems use 851.0125 + 25 kHz for
 * 0-0x1B7 and 851.0250 + 25 kHz for 0x1B8-0x22F; splinter systems 851.0000 + 25 kHz for 0-0x257 and 866.0125 + 25 kHz
 * for 0x258-0x2CF. All three add 0x2D0-0x2F7 = 866.0000 +, 0x32F-0x33F = 867.0000 +, 0x3C1-0x3FE = 867.4250 + (25 kHz
 * steps) and 0x3BE = 868.9750. 900 MHz: 935.0125 + 12.5 kHz x channel, channels 0-0x1DE. VHF/UHF ("OBT"): set per
 * system, receive channels {@code offset} and up are {@code base + spacing x (channel - offset)}; the 380 channels below
 * {@code offset} are the radios' transmit channels. Numbers from trunk-recorder's band plans (see NOTICE.txt).
 */
public final class Type2BandPlan {
    public enum Kind {
        AUTO("auto"), B800_REBAND("800reband"), B800_STANDARD("800standard"), B800_SPLINTER("800splinter"), B900("900"), OBT("obt");
        public final String key;
        Kind(String key) { this.key = key; }
        public static Kind of(String s) {
            for (Kind k : values()) if (k.key.equalsIgnoreCase(s == null ? "" : s.trim())) return k;
            return AUTO;
        }
    }

    public final Kind kind;
    public final long baseHz, spacingHz;
    public final int offset;

    public Type2BandPlan(Kind kind, long baseHz, long spacingHz, int offset) {
        this.kind = kind; this.baseHz = baseHz; this.spacingHz = spacingHz; this.offset = offset;
    }

    public static Type2BandPlan of(Kind k) { return new Type2BandPlan(k, 0, 0, 0); }

    /** The plan a control channel frequency suggests when the user chose Auto: 900 MHz, else 800 MHz rebanded. */
    public static Kind guess(long ccHz) {
        if (ccHz >= 935_000_000L && ccHz < 941_000_000L) return Kind.B900;
        if (ccHz >= 851_000_000L && ccHz < 870_000_000L) return Kind.B800_REBAND;
        return Kind.OBT;
    }

    /** Downlink Hz for a channel number, or 0 when the plan does not use it. */
    public long frequency(int ch) {
        if (ch < 0 || ch > 0x3FF) return 0;
        switch (kind) {
            case B900: return ch <= 0x1DE ? 935_012_500L + 12_500L * ch : 0;
            case OBT: return isReceive(ch) ? baseHz + spacingHz * (ch - offset) : 0;
            case AUTO: return 0;
            default: break;
        }
        if (ch >= 0x2D0 && ch <= 0x2F7) return 866_000_000L + 25_000L * (ch - 0x2D0);
        if (ch >= 0x32F && ch <= 0x33F) return 867_000_000L + 25_000L * (ch - 0x32F);
        if (ch >= 0x3C1 && ch <= 0x3FE) return 867_425_000L + 25_000L * (ch - 0x3C1);
        if (ch == 0x3BE) return 868_975_000L;
        return switch (kind) {
            case B800_REBAND -> ch <= 0x1B7 ? 851_012_500L + 25_000L * ch : ch <= 0x22F ? 851_025_000L + 25_000L * (ch - 0x1B8) : 0;
            case B800_SPLINTER -> ch <= 0x257 ? 851_000_000L + 25_000L * ch : ch <= 0x2CF ? 866_012_500L + 25_000L * (ch - 0x258) : 0;
            default -> ch <= 0x2CF ? 851_012_500L + 25_000L * ch : 0;
        };
    }

    boolean isReceive(int ch) { return spacingHz > 0 && baseHz > 0 && ch >= offset && ch < 0x2F8; }

    /** An OBT radio transmit channel (the first word of a VHF/UHF grant). */
    public boolean isTransmit(int ch) { return kind == Kind.OBT && offset > 0 && ch >= Math.max(0, offset - 380) && ch < offset; }

    /** A command value that names a voice or control channel rather than an opcode. */
    public boolean isChannel(int ch) { return frequency(ch) > 0; }

    @Override public String toString() {
        return kind == Kind.OBT ? String.format(Locale.ROOT, "VHF/UHF base %.5f MHz, spacing %.2f kHz, offset %d", baseHz / 1e6, spacingHz / 1e3, offset) : kind.key;
    }
}
