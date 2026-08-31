package com.keytrins.liveresearch.model;

public final class HedgeState {
    public String primaryTradeId, symbol, side, state;
    public int positionIdx;
    public long openedAtMs, lastAttemptMs;
    public double entryPrice, initialQty, currentQty, initialStop, currentStop;
    public double atr, takerFee, spreadAtEntry, highWater, lowWater;
    public double peakProfitUsdt, protectedProfitUsdt;

    public HedgeState() {}
}
