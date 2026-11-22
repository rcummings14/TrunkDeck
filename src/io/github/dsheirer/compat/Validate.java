/*
 * TrunkDeck compatibility shim for the SDRTrunk code in io.github.dsheirer (GPL-3.0-or-later).
 * Copyright (C) 2026 Ray Cummings.
 */
package io.github.dsheirer.compat;

/** Stands in for the two Apache Commons Lang Validate checks the copied SDRTrunk files use. */
public final class Validate {
    private Validate() {}

    public static void isTrue(boolean ok) { if (!ok) throw new IllegalArgumentException("The validated expression is false"); }

    public static void isTrue(boolean ok, String message) { if (!ok) throw new IllegalArgumentException(message); }

    public static void inclusiveBetween(long start, long end, long value, String message) {
        if (value < start || value > end) throw new IllegalArgumentException(message);
    }
}
