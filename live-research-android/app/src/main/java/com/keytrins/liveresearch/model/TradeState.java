package com.keytrins.liveresearch.model;

public final class TradeState {
    public String tradeId, symbol, side, state;
    public long openedAtMs, structureBreakTimeMs;
    public double entryPrice, initialQty, currentQty, initialStop, currentStop, riskDistance;
    public double targetRiskUsdt, atr, takerFee, spreadAtEntry, costREst, highWater, lowWater, balanceAtOpen;
    public double peakProfitUsdt, protectedProfitUsdt;
    public boolean reduced, beArmed, trailing, structureBreak;

    public TradeState() {}
}
