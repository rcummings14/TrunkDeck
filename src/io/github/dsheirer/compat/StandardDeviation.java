/*
 * TrunkDeck compatibility shim for the SDRTrunk code in io.github.dsheirer (GPL-3.0-or-later).
 * Copyright (C) 2026 Ray Cummings.
 */
package io.github.dsheirer.compat;

/** Sample standard deviation (n - 1), standing in for org.apache.commons.math3.stat.descriptive.moment.StandardDeviation. */
public final class StandardDeviation extends Variance {
    @Override public double getResult() { return Math.sqrt(super.getResult()); }
}
