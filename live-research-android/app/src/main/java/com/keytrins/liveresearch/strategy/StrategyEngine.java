package com.keytrins.liveresearch.strategy;

import com.keytrins.liveresearch.BotRuntime;
import com.keytrins.liveresearch.SettingsStore;
import com.keytrins.liveresearch.model.Candle;
import com.keytrins.liveresearch.model.Signal;
import com.keytrins.liveresearch.model.SignalResult;

import java.util.List;

public final class StrategyEngine {
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
        double[] mFast = Indicators.ema(m15, s.m15EmaFast);
        double[] mSlow = Indicators.ema(m15, s.m15EmaSlow);
        double[] mAtr = Indicators.atr(m15, s.atrPeriod);

        int hi = h1.size() - 1, mi = m15.size() - 1, pi = mi - 1;
        double adx = hAdx[hi], fastNow = hFast[hi], slow = hSlow[hi], hClose = h1.get(hi).close;
        int slopeIdx = hi - s.h1SlopeBars;
        if (slopeIdx < 0 || Double.isNaN(adx) || Double.isNaN(mAtr[mi])) return reject(symbol, "INDICATOR_NAN");
        double fastThen = hFast[slopeIdx];

        boolean longTrend = fastNow > slow && fastNow > fastThen && adx >= s.adxMin && hClose > fastNow;
        boolean shortTrend = fastNow < slow && fastNow < fastThen && adx >= s.adxMin && hClose < fastNow;
        if (!longTrend && !shortTrend) return reject(symbol, "NO_H1_TREND");
        String dir = longTrend ? "LONG" : "SHORT";

        boolean touched = false;
        int from = Math.max(0, mi - s.pullbackLookback);
        for (int i = from; i < mi; i++) {
            Candle r = m15.get(i);
            double lo = Math.min(mFast[i], mSlow[i]), hiZone = Math.max(mFast[i], mSlow[i]);
            if (r.low <= hiZone && r.high >= lo) { touched = true; break; }
        }
        if (!touched) return reject(symbol, "NO_M15_PULLBACK");

        Candle M = m15.get(mi), prev = m15.get(pi);
        boolean confirmed = longTrend
                ? M.close > M.open && M.close > prev.high && M.close > mFast[mi]
                : M.close < M.open && M.close < prev.low && M.close < mFast[mi];
        if (!confirmed) return reject(symbol, "NO_M15_CONFIRMATION");

        double entry = M.close, atr = mAtr[mi], stop;
        int swingFrom = Math.max(0, m15.size() - s.swingLookback);
        if (longTrend) {
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

        double emaSepPct = Math.abs(fastNow - slow) / hClose * 100.0;
        double slopePct = Math.abs(fastNow - fastThen) / hClose * 100.0;
        double score = Math.min(100.0, adx * 1.6 + emaSepPct * 30.0 + slopePct * 60.0);
        long signalTime = M.startMs + 15 * 60_000L;
        Signal sig = new Signal(symbol, dir, signalTime, score, adx, fastNow, slow, atr,
                entry, stop, risk, "H1_TREND_M15_PULLBACK_CONFIRM");
        BotRuntime.recordDecision(symbol, "SIGNAL_" + dir);
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
