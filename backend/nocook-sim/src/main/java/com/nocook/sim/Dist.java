package com.nocook.sim;

import java.util.Map;

/** Inverse-CDF helpers: turn a uniform draw into a value. StrictMath keeps results identical across platforms. */
final class Dist {
    private Dist() {}

    static double uniform(double u, Assumptions.Range range) {
        return range.min() + u * (range.max() - range.min());
    }

    static double lognormal(double u, double median, double sigma) {
        return median * StrictMath.exp(sigma * normalQuantile(clamp(u)));
    }

    /** Lognormal truncated to (0, cap) by squeezing u into [0, F(cap)]. */
    static double truncatedLognormal(double u, double median, double sigma, double cap) {
        double capProbability = normalCdf(StrictMath.log(cap / median) / sigma);
        return lognormal(u * capProbability, median, sigma);
    }

    static <K> K pick(Map<K, Double> weights, double u) {
        double cumulative = 0;
        K last = null;
        for (Map.Entry<K, Double> entry : weights.entrySet()) {
            cumulative += entry.getValue();
            last = entry.getKey();
            if (u < cumulative) return entry.getKey();
        }
        return last;
    }

    private static double clamp(double u) {
        return Math.min(Math.max(u, 1e-12), 1 - 1e-12);
    }

    /** Acklam's rational approximation of the standard normal quantile. */
    static double normalQuantile(double p) {
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
                1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
                6.680131188771972e+01, -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
                -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        double low = 0.02425;
        if (p < low) {
            double q = StrictMath.sqrt(-2 * StrictMath.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        if (p > 1 - low) {
            double q = StrictMath.sqrt(-2 * StrictMath.log(1 - p));
            return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        double q = p - 0.5;
        double r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    }

    /** Standard normal CDF via the Abramowitz-Stegun 26.2.17 approximation (abs error below 7.5e-8). */
    static double normalCdf(double x) {
        double t = 1.0 / (1.0 + 0.2316419 * StrictMath.abs(x));
        double density = StrictMath.exp(-x * x / 2) / StrictMath.sqrt(2 * StrictMath.PI);
        double poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        double upperTail = density * poly;
        return x >= 0 ? 1 - upperTail : upperTail;
    }
}
