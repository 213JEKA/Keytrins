package com.keytrins.liveresearch.strategy;

import com.keytrins.liveresearch.model.Candle;

import java.util.ArrayList;
import java.util.List;

public final class StrategyRegimeSelfTest {
    public static void main(String[] args) {
        testRegimeClassification();
        testTrendLocationGate();
        testRangeRejection();
        testBreakoutRetest();
        System.out.println("StrategyRegimeSelfTest PASS");
    }

    private static void testRegimeClassification() {
        List<Candle> down = series(240, 120.0, -0.12, 0.35);
        List<Candle> m15 = series(100, 90.0, -0.01, 0.16);
        MarketStructure.Analysis d = MarketStructure.analyze(down, m15,
                92.0, 98.0, 93.0, 30.0, 22.0, 0.90, 0.22);
        check(d.regime == MarketStructure.Regime.DOWN_TREND, "clear downtrend must be DOWN_TREND");

        List<Candle> flat = series(240, 100.0, 0.0, 0.25);
        List<Candle> flat15 = series(100, 100.0, 0.0, 0.12);
        MarketStructure.Analysis r = MarketStructure.analyze(flat, flat15,
                100.02, 100.00, 100.01, 15.0, 22.0, 0.50, 0.18);
        check(r.regime == MarketStructure.Regime.RANGE, "flat low-ADX market must be RANGE");
    }

    private static void testTrendLocationGate() {
        MarketStructure.Analysis downAtBottom = new MarketStructure.Analysis(
                MarketStructure.Regime.DOWN_TREND, -2.0,
                100, 90, 110, 0.18,
                92, 108, 0.20);
        check(!MarketStructure.trendLocationAllowed(downAtBottom),
                "must not sell the lower edge of a downtrend channel");

        MarketStructure.Analysis downAtTop = new MarketStructure.Analysis(
                MarketStructure.Regime.DOWN_TREND, -2.0,
                100, 90, 110, 0.78,
                92, 108, 0.76);
        check(MarketStructure.trendLocationAllowed(downAtTop),
                "downtrend pullback near upper zone must be eligible");

        MarketStructure.Analysis upAtTop = new MarketStructure.Analysis(
                MarketStructure.Regime.UP_TREND, 2.0,
                100, 90, 110, 0.82,
                92, 108, 0.80);
        check(!MarketStructure.trendLocationAllowed(upAtTop),
                "must not buy the upper edge of an uptrend channel");
    }

    private static void testRangeRejection() {
        MarketStructure.Analysis lower = new MarketStructure.Analysis(
                MarketStructure.Regime.RANGE, 0,
                100, 95, 105, 0.50,
                99, 101, 0.12);
        Candle prev = new Candle(0, 99.4, 99.6, 99.2, 99.30, 1, 1);
        Candle bullishReject = new Candle(1, 99.20, 99.45, 98.92, 99.38, 1, 1);
        check(MarketStructure.rangeLongRejection(lower, bullishReject, prev, 0.30),
                "lower-bound bullish rejection must be recognized");

        MarketStructure.Analysis upper = new MarketStructure.Analysis(
                MarketStructure.Regime.RANGE, 0,
                100, 95, 105, 0.50,
                99, 101, 0.88);
        Candle prev2 = new Candle(0, 100.6, 100.8, 100.4, 100.70, 1, 1);
        Candle bearishReject = new Candle(1, 100.82, 101.08, 100.55, 100.60, 1, 1);
        check(MarketStructure.rangeShortRejection(upper, bearishReject, prev2, 0.30),
                "upper-bound bearish rejection must be recognized");
    }

    private static void testBreakoutRetest() {
        MarketStructure.Analysis range = new MarketStructure.Analysis(
                MarketStructure.Regime.RANGE, 0,
                100, 95, 105, 0.50,
                99, 101, 0.50);
        Candle prevLong = new Candle(0, 100.8, 101.4, 100.7, 101.20, 1, 1);
        Candle retestLong = new Candle(1, 101.08, 101.32, 100.98, 101.18, 1, 1);
        check(MarketStructure.rangeLongBreakoutRetest(range, retestLong, prevLong, 0.50),
                "up breakout must require and accept a retest");

        Candle prevShort = new Candle(0, 99.2, 99.3, 98.6, 98.80, 1, 1);
        Candle retestShort = new Candle(1, 98.95, 99.04, 98.70, 98.82, 1, 1);
        check(MarketStructure.rangeShortBreakoutRetest(range, retestShort, prevShort, 0.50),
                "down breakout must require and accept a retest");
    }

    private static List<Candle> series(int n, double start, double step, double halfRange) {
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double close = start + step * i;
            double open = close - step * 0.2;
            out.add(new Candle(i * 60_000L, open, Math.max(open, close) + halfRange,
                    Math.min(open, close) - halfRange, close, 1, 1));
        }
        return out;
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
