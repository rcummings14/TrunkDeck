/*
 * TrunkDeck compatibility shim for the SDRTrunk code in io.github.dsheirer (GPL-3.0-or-later).
 * Copyright (C) 2026 Ray Cummings.
 */
package io.github.dsheirer.compat;

/**
 * Natural cubic spline through the given points (second derivative 0 at both ends), the same curve as
 * org.apache.commons.math3.analysis.interpolation.SplineInterpolator. Used once, to build the reference sync waveform.
 */
public final class SplineInterpolator {
    public PolynomialSplineFunction interpolate(double[] x, double[] y) {
        int n = x.length - 1;
        if (n < 2 || y.length != x.length) throw new IllegalArgumentException("need at least 3 points of equal count");
        double[] h = new double[n];
        for (int i = 0; i < n; i++) {
            h[i] = x[i + 1] - x[i];
            if (h[i] <= 0) throw new IllegalArgumentException("x must be strictly increasing");
        }
        double[] mu = new double[n], z = new double[n + 1];
        double g;
        for (int i = 1; i < n; i++) {
            g = 2.0 * (x[i + 1] - x[i - 1]) - h[i - 1] * mu[i - 1];
            mu[i] = h[i] / g;
            z[i] = (3.0 * (y[i + 1] * h[i - 1] - y[i] * (x[i + 1] - x[i - 1]) + y[i - 1] * h[i]) / (h[i - 1] * h[i]) - h[i - 1] * z[i - 1]) / g;
        }
        double[] b = new double[n], c = new double[n + 1], d = new double[n];
        for (int j = n - 1; j >= 0; j--) {
            c[j] = z[j] - mu[j] * c[j + 1];
            b[j] = (y[j + 1] - y[j]) / h[j] - h[j] * (c[j + 1] + 2.0 * c[j]) / 3.0;
            d[j] = (c[j + 1] - c[j]) / (3.0 * h[j]);
        }
        double[][] coefficients = new double[n][];
        for (int i = 0; i < n; i++) coefficients[i] = new double[]{ y[i], b[i], c[i], d[i] };
        return new PolynomialSplineFunction(x.clone(), coefficients);
    }
}
