package com.keytrins.liveresearch.strategy;

import com.keytrins.liveresearch.model.Candle;

import java.util.Arrays;
import java.util.List;

public final class MarketStructure {
    public enum Regime { UP_TREND, DOWN_TREND, RANGE, TRANSITION }

    public static final class Analysis {
        public final Regime regime;
        public final double macroMoveAtr;
        public final double channelCenter, channelLower, channelUpper, channelPosition;
        public final double rangeLower, rangeUpper, rangePosition;

        Analysis(Regime regime, double macroMoveAtr,
                 double channelCenter, double channelLower, double channelUpper, double channelPosition,
                 double rangeLower, double rangeUpper, double rangePosition) {
            this.regime = regime;
            this.macroMoveAtr = macroMoveAtr;
            this.channelCenter = channelCenter;
            this.channelLower = channelLower;
            this.channelUpper = channelUpper;
            this.channelPosition = channelPosition;
            this.rangeLower = rangeLower;
            this.rangeUpper = rangeUpper;
            this.rangePosition = rangePosition;
        }
    }

    public static Analysis analyze(List<Candle> h1, List<Candle> m15,
                                   double emaFast, double emaSlow, double emaFastThen,
                                   double adx, double adxMin, double h1Atr, double m15Atr) {
        double hClose = h1.get(h1.size() - 1).close;
        double safeH1Atr = positive(h1Atr) ? h1Atr : Math.max(hClose * 0.005, 1e-9);
        double safeM15Atr = positive(m15Atr) ? m15Atr : Math.max(m15.get(m15.size() - 1).close * 0.002, 1e-9);

        Regression reg = regression(h1, 48);
        double channelWidth = Math.max(1.25 * safeH1Atr, 1.60 * reg.residualStd);
        double channelLower = reg.last - channelWidth;
        double channelUpper = reg.last + channelWidth;
        double channelPosition = clamp01((hClose - channelLower) / Math.max(1e-12, channelUpper - channelLower));
        double macroMoveAtr = reg.slope * Math.max(1, reg.count - 1) / safeH1Atr;

        double[] bounds = robustRange(m15, 48, safeM15Atr);
        double rangeLower = bounds[0], rangeUpper = bounds[1];
        double mClose = m15.get(m15.size() - 1).close;
        double rangePosition = clamp01((mClose - rangeLower) / Math.max(1e-12, rangeUpper - rangeLower));

        boolean emaUp = emaFast > emaSlow && emaFast > emaFastThen && hClose > emaFast;
        boolean emaDown = emaFast < emaSlow && emaFast < emaFastThen && hClose < emaFast;
        boolean macroUp = macroMoveAtr >= 0.60;
        boolean macroDown = macroMoveAtr <= -0.60;
        boolean trendAdx = adx >= Math.max(18.0, adxMin - 4.0);

        Regime regime;
        if (macroUp && trendAdx && !emaDown) regime = Regime.UP_TREND;
        else if (macroDown && trendAdx && !emaUp) regime = Regime.DOWN_TREND;
        else if ((emaUp && macroMoveAtr <= -0.15) || (emaDown && macroMoveAtr >= 0.15)) regime = Regime.TRANSITION;
        else if (emaUp && adx >= adxMin && macroMoveAtr >= 0.15) regime = Regime.UP_TREND;
        else if (emaDown && adx >= adxMin && macroMoveAtr <= -0.15) regime = Regime.DOWN_TREND;
        else if (Math.abs(macroMoveAtr) <= 0.80 || adx < adxMin + 4.0) regime = Regime.RANGE;
        else regime = Regime.TRANSITION;

        return new Analysis(regime, macroMoveAtr,
                reg.last, channelLower, channelUpper, channelPosition,
                rangeLower, rangeUpper, rangePosition);
    }

    public static boolean trendLocationAllowed(Analysis a) {
        if (a.regime == Regime.UP_TREND) {
            return a.channelPosition <= 0.45 && a.rangePosition <= 0.58;
        }
        if (a.regime == Regime.DOWN_TREND) {
            return a.channelPosition >= 0.55 && a.rangePosition >= 0.42;
        }
        return false;
    }

    public static boolean rangeLongRejection(Analysis a, Candle current, Candle prev, double atr) {
        if (a.rangePosition > 0.32) return false;
        double tol = 0.15 * atr;
        double span = Math.max(1e-12, current.high - current.low);
        double lowerWick = Math.min(current.open, current.close) - current.low;
        return current.low <= a.rangeLower + tol
                && current.close > current.open
                && current.close > a.rangeLower
                && current.close >= prev.close
                && lowerWick >= 0.22 * span;
    }

    public static boolean rangeShortRejection(Analysis a, Candle current, Candle prev, double atr) {
        if (a.rangePosition < 0.68) return false;
        double tol = 0.15 * atr;
        double span = Math.max(1e-12, current.high - current.low);
        double upperWick = current.high - Math.max(current.open, current.close);
        return current.high >= a.rangeUpper - tol
                && current.close < current.open
                && current.close < a.rangeUpper
                && current.close <= prev.close
                && upperWick >= 0.22 * span;
    }

    public static boolean rangeLongBreakoutRetest(Analysis a, Candle current, Candle prev, double atr) {
        double breakBuffer = 0.08 * atr;
        return prev.close >= a.rangeUpper + breakBuffer
                && current.low <= a.rangeUpper + 0.18 * atr
                && current.low >= a.rangeUpper - 0.35 * atr
                && current.close >= a.rangeUpper + 0.03 * atr
                && current.close > current.open
                && current.close <= a.rangeUpper + 0.80 * atr;
    }

    public static boolean rangeShortBreakoutRetest(Analysis a, Candle current, Candle prev, double atr) {
        double breakBuffer = 0.08 * atr;
        return prev.close <= a.rangeLower - breakBuffer
                && current.high >= a.rangeLower - 0.18 * atr
                && current.high <= a.rangeLower + 0.35 * atr
                && current.close <= a.rangeLower - 0.03 * atr
                && current.close < current.open
                && current.close >= a.rangeLower - 0.80 * atr;
    }

    public static double roomToNearestLevel(Analysis a, String direction, double entry) {
        if ("LONG".equals(direction)) {
            if (a.regime == Regime.UP_TREND) {
                double target = Math.min(a.channelUpper, a.rangeUpper);
                return Math.max(0.0, target - entry);
            }
            return Math.max(0.0, a.rangeUpper - entry);
        }
        if (a.regime == Regime.DOWN_TREND) {
            double target = Math.max(a.channelLower, a.rangeLower);
            return Math.max(0.0, entry - target);
        }
        return Math.max(0.0, entry - a.rangeLower);
    }

    private static double[] robustRange(List<Candle> candles, int lookback, double atr) {
        int endExclusive = Math.max(0, candles.size() - 1); // never use the decision candle to build its own level
        int n = Math.min(lookback, endExclusive);
        if (n < 12) {
            double c = candles.get(candles.size() - 1).close;
            return new double[]{c - 2.0 * atr, c + 2.0 * atr};
        }
        double[] lows = new double[n], highs = new double[n];
        int start = endExclusive - n;
        for (int i = 0; i < n; i++) {
            Candle x = candles.get(start + i);
            lows[i] = x.low;
            highs[i] = x.high;
        }
        Arrays.sort(lows);
        Arrays.sort(highs);
        double lower = quantileSorted(lows, 0.12);
        double upper = quantileSorted(highs, 0.88);
        double minSpan = Math.max(1.5 * atr, candles.get(candles.size() - 1).close * 0.002);
        if (!(upper > lower) || upper - lower < minSpan) {
            double mid = (upper + lower) * 0.5;
            if (!(mid > 0)) mid = candles.get(candles.size() - 1).close;
            lower = mid - minSpan * 0.5;
            upper = mid + minSpan * 0.5;
        }
        return new double[]{lower, upper};
    }

    private static Regression regression(List<Candle> candles, int lookback) {
        int n = Math.min(lookback, candles.size());
        int start = candles.size() - n;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (int i = 0; i < n; i++) {
            double y = candles.get(start + i).close;
            sx += i; sy += y; sxx += (double) i * i; sxy += i * y;
        }
        double den = n * sxx - sx * sx;
        double slope = Math.abs(den) < 1e-12 ? 0.0 : (n * sxy - sx * sy) / den;
        double intercept = (sy - slope * sx) / Math.max(1, n);
        double rss = 0;
        for (int i = 0; i < n; i++) {
            double err = candles.get(start + i).close - (intercept + slope * i);
            rss += err * err;
        }
        double std = Math.sqrt(rss / Math.max(1, n));
        return new Regression(n, slope, intercept + slope * Math.max(0, n - 1), std);
    }

    private static double quantileSorted(double[] x, double q) {
        if (x.length == 0) return Double.NaN;
        double p = clamp01(q) * (x.length - 1);
        int lo = (int) Math.floor(p), hi = (int) Math.ceil(p);
        if (lo == hi) return x[lo];
        double w = p - lo;
        return x[lo] * (1.0 - w) + x[hi] * w;
    }

    private static boolean positive(double x) { return x > 0 && !Double.isNaN(x) && !Double.isInfinite(x); }
    private static double clamp01(double x) { return Math.max(0.0, Math.min(1.0, x)); }

    private static final class Regression {
        final int count;
        final double slope, last, residualStd;
        Regression(int count, double slope, double last, double residualStd) {
            this.count = count; this.slope = slope; this.last = last; this.residualStd = residualStd;
        }
    }

    private MarketStructure() {}
}
