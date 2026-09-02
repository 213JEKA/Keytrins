package com.keytrins.zonemonitor;

import static com.keytrins.zonemonitor.MarketModels.Candle;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class DemoData {
    private DemoData() {}

    static List<Candle> candles(String symbol) {
        int seed = symbol == null ? 7 : symbol.hashCode();
        Random random = new Random(seed);
        double base = base(symbol);
        double pip = symbol != null && symbol.contains("JPY") ? 0.01 : 0.0001;
        long start = System.currentTimeMillis() - 320L * 15L * 60L * 1000L;
        List<Candle> result = new ArrayList<>();
        double close = base;
        for (int i = 0; i < 320; i++) {
            double wave = Math.sin(i * 0.145) * 4.2 * pip + Math.sin(i * 0.037) * 8.0 * pip;
            double magnet = (i % 62 < 34 ? 1 : -1) * 1.4 * pip;
            double change = wave * 0.18 + magnet + random.nextGaussian() * 2.0 * pip;
            double open = close;
            close = open + change;
            double wick = (1.2 + random.nextDouble() * 3.0) * pip;
            double high = Math.max(open, close) + wick;
            double low = Math.min(open, close) - wick * (0.8 + random.nextDouble() * 0.5);
            long volume = 60 + random.nextInt(170) + (long) (80 * Math.abs(Math.sin(i * 0.12)));
            result.add(new Candle(start + i * 15L * 60L * 1000L, open, high, low, close, volume));
        }
        return result;
    }

    private static double base(String symbol) {
        if ("GBPUSD".equals(symbol)) return 1.3140;
        if ("AUDUSD".equals(symbol)) return 0.6570;
        if ("USDCAD".equals(symbol)) return 1.3760;
        return 1.0870;
    }
}
