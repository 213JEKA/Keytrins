package com.keytrins.liveresearch.model;

import java.math.BigDecimal;

public final class Instrument {
    public final String symbol;
    public final String baseCoin;
    public final String contractType;
    public final long launchTimeMs;
    public final BigDecimal tickSize, qtyStep, minQty, maxMarketQty, minNotional;
    public double turnover24h;

    public Instrument(String symbol, String baseCoin, String contractType, long launchTimeMs,
                      BigDecimal tickSize, BigDecimal qtyStep,
                      BigDecimal minQty, BigDecimal maxMarketQty, BigDecimal minNotional) {
        this.symbol = symbol;
        this.baseCoin = baseCoin;
        this.contractType = contractType;
        this.launchTimeMs = launchTimeMs;
        this.tickSize = tickSize;
        this.qtyStep = qtyStep;
        this.minQty = minQty;
        this.maxMarketQty = maxMarketQty;
        this.minNotional = minNotional;
    }
}
