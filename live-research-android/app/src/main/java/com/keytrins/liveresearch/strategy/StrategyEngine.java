package com.keytrins.liveresearch.strategy;

import com.keytrins.liveresearch.BotRuntime;
import com.keytrins.liveresearch.SettingsStore;
import com.keytrins.liveresearch.model.Candle;
import com.keytrins.liveresearch.model.Signal;
import com.keytrins.liveresearch.model.SignalResult;

import java.util.List;

public final class StrategyEngine {
    private static final double MIN_ROOM_R = 1.05;
    private final SettingsStore.Snapshot s;

    public StrategyEngine(SettingsStore.Snapshot s) { this.s = s; }

    private SignalResult reject(String symbol, String reason) {
        BotRuntime.recordDecision(symbol, reason);
        return new SignalResult(null, reason);
    }

    public SignalResult buildSignal(String symbol, List<Candle> h1, List<Candle> m15) {
        int needH1 = Math.max(s.h1EmaSlow + 10, 220);
        int needM15 = Math.max(s.m15EmaSlow + 15, 80);
        if (h1.size() < needH1 || m15.size() < needM15) return reject(symbol, "NOT_ENOUGH_BARS");

        double[] hFast = Indicators.ema(h1, s.h1EmaFast);
        double[] hSlow = Indicators.ema(h1, s.h1EmaSlow);
        double[] hAdx = Indicators.adx(h1, s.atrPeriod);
        double[] hAtr = Indicators.atr(h1, s.atrPeriod);
        double[] mFast = Indicators.ema(m15, s.m15EmaFast);
        double[] mSlow = Indicators.ema(m15, s.m15EmaSlow);
        double[] mAtr = Indicators.atr(m15, s.atrPeriod);

        int hi = h1.size() - 1, mi = m15.size() - 1, pi = mi - 1;
        int slopeIdx = hi - s.h1SlopeBars;
        if (slopeIdx < 0) return reject(symbol, "INDICATOR_NAN");

        double adx = hAdx[hi], fastNow = hFast[hi], slow = hSlow[hi], fastThen = hFast[slopeIdx];
        double atr = mAtr[mi], h1AtrNow = hAtr[hi];
        if (Double.isNaN(adx) || Double.isNaN(atr) || Double.isNaN(h1AtrNow) || !(atr > 0)) return reject(symbol, "INDICATOR_NAN");

        MarketStructure.Analysis structure = MarketStructure.analyze(
                h1, m15, fastNow, slow, fastThen, adx, s.adxMin, h1AtrNow, atr);

        Candle M = m15.get(mi), prev = m15.get(pi);
        String dir;
        String reason;
        boolean breakoutRetest = false;

        if (structure.regime == MarketStructure.Regime.UP_TREND || structure.regime == MarketStructure.Regime.DOWN_TREND) {
            dir = structure.regime == MarketStructure.Regime.UP_TREND ? "LONG" : "SHORT";

            // A trend entry is allowed only from the useful side of the trend channel.
            // This blocks buying the upper edge of an uptrend and selling the lower edge of a downtrend,
            // and, critically, blocks counter-trend entries caused by a short-lived EMA flip.
            if (!MarketStructure.trendLocationAllowed(structure)) {
                return reject(symbol, structure.regime == MarketStructure.Regime.UP_TREND
                        ? "UPTREND_BAD_LOCATION" : "DOWNTREND_BAD_LOCATION");
            }

            boolean touched = false;
            int from = Math.max(0, mi - s.pullbackLookback);
            for (int i = from; i < mi; i++) {
                Candle r = m15.get(i);
                double lo = Math.min(mFast[i], mSlow[i]), hiZone = Math.max(mFast[i], mSlow[i]);
                if (r.low <= hiZone && r.high >= lo) { touched = true; break; }
            }
            if (!touched) return reject(symbol, "NO_M15_PULLBACK");

            boolean confirmed = "LONG".equals(dir)
                    ? M.close > M.open && M.close > prev.high && M.close > mFast[mi]
                    : M.close < M.open && M.close < prev.low && M.close < mFast[mi];
            if (!confirmed) return reject(symbol, "NO_M15_CONFIRMATION");

            reason = structure.regime == MarketStructure.Regime.UP_TREND
                    ? "TREND_UP_LOWER_PULLBACK_CONFIRM"
                    : "TREND_DOWN_UPPER_PULLBACK_CONFIRM";

        } else if (structure.regime == MarketStructure.Regime.RANGE) {
            boolean longBreak = MarketStructure.rangeLongBreakoutRetest(structure, M, prev, atr);
            boolean shortBreak = MarketStructure.rangeShortBreakoutRetest(structure, M, prev, atr);
            boolean longReject = MarketStructure.rangeLongRejection(structure, M, prev, atr);
            boolean shortReject = MarketStructure.rangeShortRejection(structure, M, prev, atr);

            // In a range we never buy merely because price is near the upper edge and never sell merely
            // because price is near the lower edge. We require either rejection back into the range or
            // a real breakout followed by a retest from the other side.
            if (longBreak) {
                dir = "LONG"; reason = "RANGE_UP_BREAKOUT_RETEST"; breakoutRetest = true;
            } else if (shortBreak) {
                dir = "SHORT"; reason = "RANGE_DOWN_BREAKOUT_RETEST"; breakoutRetest = true;
            } else if (longReject) {
                dir = "LONG"; reason = "RANGE_LOWER_REJECTION";
            } else if (shortReject) {
                dir = "SHORT"; reason = "RANGE_UPPER_REJECTION";
            } else if (structure.rangePosition > 0.32 && structure.rangePosition < 0.68) {
                return reject(symbol, "RANGE_MIDDLE_NO_TRADE");
            } else {
                return reject(symbol, "WAIT_RANGE_REACTION");
            }
        } else {
            return reject(symbol, "REGIME_TRANSITION_WAIT");
        }

        double entry = M.close, stop;
        int swingFrom = Math.max(0, m15.size() - s.swingLookback);
        if ("LONG".equals(dir)) {
            double atrStop = entry - s.atrSlMult * atr;
            double swing = Double.POSITIVE_INFINITY;
            for (int i = swingFrom; i < m15.size(); i++) swing = Math.min(swing, m15.get(i).low);
            stop = Math.min(atrStop, swing);
        } else {
            double atrStop = entry + s.atrSlMult * atr;
            double swing = Double.NEGATIVE_INFINITY;
            for (int i = swingFrom; i < m15.size(); i++) swing = Math.max(swing, m15.get(i).high);
            stop = Math.max(atrStop, swing);
        }

        double risk = Math.abs(entry - stop);
        if (!(risk > 0) || !(atr > 0)) return reject(symbol, "INVALID_STOP");

        double roomR = breakoutRetest ? 2.0 : MarketStructure.roomToNearestLevel(structure, dir, entry) / risk;
        if (!breakoutRetest && roomR < MIN_ROOM_R) return reject(symbol, "NO_SPACE_TO_LEVEL");

        double hClose = Math.max(1e-12, h1.get(hi).close);
        double emaSepPct = Math.abs(fastNow - slow) / hClose * 100.0;
        double slopePct = Math.abs(fastNow - fastThen) / hClose * 100.0;
        double locationQuality;
        if (structure.regime == MarketStructure.Regime.UP_TREND) locationQuality = 1.0 - structure.channelPosition;
        else if (structure.regime == MarketStructure.Regime.DOWN_TREND) locationQuality = structure.channelPosition;
        else if ("LONG".equals(dir)) locationQuality = breakoutRetest ? 0.80 : 1.0 - structure.rangePosition;
        else locationQuality = breakoutRetest ? 0.80 : structure.rangePosition;

        double score = Math.min(100.0,
                adx * 1.15
                        + emaSepPct * 24.0
                        + slopePct * 55.0
                        + Math.max(0.0, Math.min(1.0, locationQuality)) * 22.0
                        + Math.min(18.0, roomR * 7.0));

        long signalTime = M.startMs + 15 * 60_000L;
        Signal sig = new Signal(symbol, dir, signalTime, score, adx, fastNow, slow, atr,
                entry, stop, risk, reason);
        BotRuntime.recordDecision(symbol, "SIGNAL_" + dir + "_" + reason);
        return new SignalResult(sig, "SIGNAL");
    }

    public BreakResult structuralBreak(String side, List<Candle> m15) {
        if (m15.size() < 8) return new BreakResult(false, 0, 0);
        double[] atr = Indicators.atr(m15, s.atrPeriod);
        int i = m15.size() - 1;
        Candle last = m15.get(i);
        double minLow = Double.POSITIVE_INFINITY, maxHigh = Double.NEGATIVE_INFINITY;
        for (int x = i - 3; x < i; x++) {
            minLow = Math.min(minLow, m15.get(x).low);
            maxHigh = Math.max(maxHigh, m15.get(x).high);
        }
        boolean broken = "Buy".equals(side)
                ? last.close < minLow && last.close < last.open
                : last.close > maxHigh && last.close > last.open;
        return new BreakResult(broken, last.startMs + 15 * 60_000L, atr[i]);
    }

    public static final class BreakResult {
        public final boolean broken;
        public final long endMs;
        public final double atr;
        public BreakResult(boolean broken, long endMs, double atr) { this.broken = broken; this.endMs = endMs; this.atr = atr; }
    }
}
