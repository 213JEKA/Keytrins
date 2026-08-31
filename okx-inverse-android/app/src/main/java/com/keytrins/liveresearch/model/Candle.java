package com.keytrins.liveresearch.model;

public final class Candle {
    public final long startMs;
    public final double open, high, low, close, volume, turnover;

    public Candle(long startMs, double open, double high, double low, double close, double volume, double turnover) {
        this.startMs = startMs;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.turnover = turnover;
    }
}
