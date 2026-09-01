package com.keytrins.liveresearch.model;

import java.math.BigDecimal;

public final class Instrument {
    public final String symbol, instId, baseCoin, contractType, contractValueCcy;
    public final long launchTimeMs;
    public final BigDecimal tickSize, qtyStep, minQty, maxMarketQty, minNotional, contractValue;
    public double turnover24h;

    public Instrument(String symbol, String instId, String baseCoin, String contractType, long launchTimeMs,
                      BigDecimal tickSize, BigDecimal qtyStep, BigDecimal minQty,
                      BigDecimal maxMarketQty, BigDecimal minNotional,
                      BigDecimal contractValue, String contractValueCcy) {
        this.symbol=symbol; this.instId=instId; this.baseCoin=baseCoin; this.contractType=contractType;
        this.launchTimeMs=launchTimeMs; this.tickSize=tickSize; this.qtyStep=qtyStep;
        this.minQty=minQty; this.maxMarketQty=maxMarketQty; this.minNotional=minNotional;
        this.contractValue=contractValue; this.contractValueCcy=contractValueCcy==null?"":contractValueCcy;
    }

    public double basePerContract(double price) {
        double ct=contractValue.doubleValue();
        if (!(ct>0)) return 0;
        if (baseCoin.equalsIgnoreCase(contractValueCcy)) return ct;
        if (("USDT".equalsIgnoreCase(contractValueCcy)||"USD".equalsIgnoreCase(contractValueCcy)) && price>0) return ct/price;
        return ct;
    }
}
