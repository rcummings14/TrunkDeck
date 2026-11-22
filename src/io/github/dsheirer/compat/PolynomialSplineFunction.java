/*
 * TrunkDeck compatibility shim for the SDRTrunk code in io.github.dsheirer (GPL-3.0-or-later).
 * Copyright (C) 2026 Ray Cummings.
 */
package io.github.dsheirer.compat;

/** Piecewise cubic polynomial made by {@link SplineInterpolator}. Each piece is a + b t + c t^2 + d t^3 from its knot. */
public final class PolynomialSplineFunction {
    private final double[] knots;
    private final double[][] coefficients;

    PolynomialSplineFunction(double[] knots, double[][] coefficients) {
        this.knots = knots;
        this.coefficients = coefficients;
    }

    /** Like Commons Math, a point outside the knots is an error. */
    public double value(double v) {
        if (v < knots[0] || v > knots[knots.length - 1]) throw new IllegalArgumentException(v + " is outside the spline");
        int i = java.util.Arrays.binarySearch(knots, v);
        if (i < 0) i = -i - 2;
        if (i >= coefficients.length) i = coefficients.length - 1;
        double t = v - knots[i];
        double[] p = coefficients[i];
        return p[0] + t * (p[1] + t * (p[2] + t * p[3]));
    }
}
