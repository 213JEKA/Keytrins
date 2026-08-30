package com.keytrins.liveresearch.model;

public final class Position {
    public final String symbol, side;
    public final double size, avgPrice, markPrice, stopLoss;
    public final int positionIdx;

    public Position(String symbol, String side, double size, double avgPrice, double markPrice, double stopLoss, int positionIdx) {
        this.symbol = symbol;
        this.side = side;
        this.size = size;
        this.avgPrice = avgPrice;
        this.markPrice = markPrice;
        this.stopLoss = stopLoss;
        this.positionIdx = positionIdx;
    }
}
