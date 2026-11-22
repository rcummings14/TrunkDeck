/*
 * TrunkDeck compatibility shim for the SDRTrunk code in io.github.dsheirer (GPL-3.0-or-later).
 * Copyright (C) 2026 Ray Cummings.
 */
package io.github.dsheirer.compat;

/**
 * Running sample variance (divides by n - 1, the Apache Commons Math default) using Welford's method. Stands in for
 * org.apache.commons.math3.stat.descriptive.moment.Variance. NaN with no values, 0 with one, as Commons Math returns.
 */
public class Variance {
    private long n;
    private double mean, m2;

    public void increment(double x) {
        n++;
        double d = x - mean;
        mean += d / n;
        m2 += d * (x - mean);
    }

    public void clear() { n = 0; mean = 0; m2 = 0; }

    public long getN() { return n; }

    double mean() { return n == 0 ? Double.NaN : mean; }

    public double getResult() { return n == 0 ? Double.NaN : n == 1 ? 0.0 : m2 / (n - 1); }
}
