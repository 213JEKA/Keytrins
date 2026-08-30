package com.keytrins.liveresearch.model;

public final class Position {
    public final String symbol, side;
    public final double size, avgPrice, markPrice, stopLoss, unrealisedPnl;
    public final int positionIdx;

    public Position(String symbol, String side, double size, double avgPrice, double markPrice,
                    double stopLoss, double unrealisedPnl, int positionIdx) {
        this.symbol = symbol;
        this.side = side;
        this.size = size;
        this.avgPrice = avgPrice;
        this.markPrice = markPrice;
        this.stopLoss = stopLoss;
        this.unrealisedPnl = unrealisedPnl;
        this.positionIdx = positionIdx;
    }
}
