package com.keytrins.liveresearch.model;

import java.math.BigDecimal;

public final class Instrument {
    public final String symbol;
    public final String baseCoin;
    public final BigDecimal tickSize, qtyStep, minQty, maxMarketQty, minNotional, contractValue;
    public double turnover24h;

    public Instrument(String symbol, String baseCoin, BigDecimal tickSize, BigDecimal qtyStep,
                      BigDecimal minQty, BigDecimal maxMarketQty, BigDecimal minNotional) {
        this(symbol, baseCoin, tickSize, qtyStep, minQty, maxMarketQty, minNotional, BigDecimal.ONE);
    }

    public Instrument(String symbol, String baseCoin, BigDecimal tickSize, BigDecimal qtyStep,
                      BigDecimal minQty, BigDecimal maxMarketQty, BigDecimal minNotional,
                      BigDecimal contractValue) {
        this.symbol = symbol;
        this.baseCoin = baseCoin;
        this.tickSize = tickSize;
        this.qtyStep = qtyStep;
        this.minQty = minQty;
        this.maxMarketQty = maxMarketQty;
        this.minNotional = minNotional;
        this.contractValue = contractValue == null || contractValue.signum() <= 0 ? BigDecimal.ONE : contractValue;
    }
}
