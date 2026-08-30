package com.keytrins.liveresearch.model;

public final class Signal {
    public final String symbol, direction, reason;
    public final long signalTimeMs;
    public final double trendScore, h1Adx, h1EmaFast, h1EmaSlow, m15Atr, entryRef, stopRef, riskDistance;

    public Signal(String symbol, String direction, long signalTimeMs, double trendScore, double h1Adx,
                  double h1EmaFast, double h1EmaSlow, double m15Atr, double entryRef, double stopRef,
                  double riskDistance, String reason) {
        this.symbol = symbol;
        this.direction = direction;
        this.signalTimeMs = signalTimeMs;
        this.trendScore = trendScore;
        this.h1Adx = h1Adx;
        this.h1EmaFast = h1EmaFast;
        this.h1EmaSlow = h1EmaSlow;
        this.m15Atr = m15Atr;
        this.entryRef = entryRef;
        this.stopRef = stopRef;
        this.riskDistance = riskDistance;
        this.reason = reason;
    }
}
