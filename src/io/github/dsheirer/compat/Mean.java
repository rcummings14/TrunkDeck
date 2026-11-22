/*
 * TrunkDeck compatibility shim for the SDRTrunk code in io.github.dsheirer (GPL-3.0-or-later).
 * Copyright (C) 2026 Ray Cummings.
 */
package io.github.dsheirer.compat;

/** Running mean, standing in for org.apache.commons.math3.stat.descriptive.moment.Mean (NaN with no values). */
public final class Mean {
    private final Variance v = new Variance();

    public void increment(double x) { v.increment(x); }

    public void clear() { v.clear(); }

    public long getN() { return v.getN(); }

    public double getResult() { return v.mean(); }
}
