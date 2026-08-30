package com.keytrins.liveresearch.model;

public final class Ticker {
    public final String symbol;
    public final double last, mark, bid, ask, turnover24h, fundingRate;

    public Ticker(String symbol, double last, double mark, double bid, double ask, double turnover24h, double fundingRate) {
        this.symbol = symbol;
        this.last = last;
        this.mark = mark;
        this.bid = bid;
        this.ask = ask;
        this.turnover24h = turnover24h;
        this.fundingRate = fundingRate;
    }
}
