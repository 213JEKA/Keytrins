package com.keytrins.zonemonitor;

import java.util.ArrayList;
import java.util.List;

final class MarketModels {
    private MarketModels() {}

    static final class Candle {
        final long timeMs;
        final double open;
        final double high;
        final double low;
        final double close;
        final long volume;

        Candle(long timeMs, double open, double high, double low, double close, long volume) {
            this.timeMs = timeMs;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.volume = volume;
        }
    }

    static final class Zone {
        double center;
        double low;
        double high;
        double halfWidth;
        int supportTouches;
        int resistanceTouches;
        int supportRejections;
        int resistanceRejections;
        int breaksUp;
        int breaksDown;
        int falseBreaksUp;
        int falseBreaksDown;
        int acceptanceBars;
        double profileStrength;
        int sourceMask;
        int lastSupportIndex = Integer.MIN_VALUE;
        int lastResistanceIndex = Integer.MIN_VALUE;
        int lastTouchAge = Integer.MAX_VALUE;
        boolean roleFlip;
        int score;
        String type = "TRADE";

        int touches() {
            return supportTouches + resistanceTouches;
        }

        int rejections() {
            return supportRejections + resistanceRejections;
        }

        String strength() {
            if (score >= 85) return "STRONG";
            if (score >= 70) return "WORK";
            return "WEAK";
        }
    }

    static final class Snapshot {
        String symbol;
        String source;
        String updatedAt;
        String signal = "WAIT";
        double price;
        List<Candle> candles = new ArrayList<>();
        List<Zone> zones = new ArrayList<>();
    }
}
