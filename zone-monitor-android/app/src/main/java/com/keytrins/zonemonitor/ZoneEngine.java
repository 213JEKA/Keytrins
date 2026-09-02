package com.keytrins.zonemonitor;

import static com.keytrins.zonemonitor.MarketModels.Candle;
import static com.keytrins.zonemonitor.MarketModels.Zone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class ZoneEngine {
    private static final int MAX_ZONES = 64;
    private static final int PIVOT_SPAN = 2;
    private static final int MIN_TOUCH_GAP = 4;
    private static final int BOUNCE_BARS = 4;
    private static final int PROFILE_BINS = 64;
    private static final int MIN_TOUCHES = 3;
    private static final int MIN_PROFILE_BARS = 12;
    private static final int MIN_SCORE = 55;

    private ZoneEngine() {}

    static List<Zone> calculate(List<Candle> candles) {
        if (candles == null || candles.size() < 80) return new ArrayList<>();
        double atr = atr(candles, 14);
        if (!(atr > 0)) return new ArrayList<>();
        double halfWidth = 0.12 * atr;
        double mergeDistance = 0.30 * atr;
        List<Zone> zones = new ArrayList<>();

        for (int i = PIVOT_SPAN; i < candles.size() - PIVOT_SPAN; i++) {
            if (isPivotLow(candles, i, PIVOT_SPAN)) {
                registerPivot(zones, candles.get(i).low, true, i,
                        rejected(candles, i, true, atr), halfWidth, mergeDistance, candles.size());
            }
            if (isPivotHigh(candles, i, PIVOT_SPAN)) {
                registerPivot(zones, candles.get(i).high, false, i,
                        rejected(candles, i, false, atr), halfWidth, mergeDistance, candles.size());
            }
        }

        addProfileZones(zones, candles, halfWidth, mergeDistance);
        score(zones, candles, atr);
        zones.removeIf(z -> !qualified(z));
        zones.sort(Comparator.comparingInt((Zone z) -> z.score).reversed());
        if (zones.size() > 20) return new ArrayList<>(zones.subList(0, 20));
        return zones;
    }

    static void classify(List<Zone> zones, double price) {
        for (Zone z : zones) {
            if (price >= z.low && price <= z.high) z.type = "ACTIVE";
            else if (z.roleFlip) z.type = "FLIP";
            else if ((z.sourceMask & 4) != 0 && z.touches() < MIN_TOUCHES) z.type = "TRADE";
            else if (price > z.high) z.type = "SUP";
            else z.type = "RES";
        }
    }

    private static double atr(List<Candle> c, int period) {
        int start = Math.max(1, c.size() - period);
        double sum = 0;
        int count = 0;
        for (int i = start; i < c.size(); i++) {
            Candle cur = c.get(i);
            double prev = c.get(i - 1).close;
            sum += Math.max(cur.high - cur.low,
                    Math.max(Math.abs(cur.high - prev), Math.abs(cur.low - prev)));
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    private static boolean isPivotHigh(List<Candle> c, int i, int span) {
        for (int k = 1; k <= span; k++) {
            if (c.get(i).high < c.get(i - k).high || c.get(i).high < c.get(i + k).high) return false;
        }
        return true;
    }

    private static boolean isPivotLow(List<Candle> c, int i, int span) {
        for (int k = 1; k <= span; k++) {
            if (c.get(i).low > c.get(i - k).low || c.get(i).low > c.get(i + k).low) return false;
        }
        return true;
    }

    private static boolean rejected(List<Candle> c, int i, boolean support, double atr) {
        int last = Math.min(c.size() - 1, i + BOUNCE_BARS);
        if (support) {
            double best = c.get(i).high;
            for (int j = i + 1; j <= last; j++) best = Math.max(best, c.get(j).high);
            return best - c.get(i).low >= 0.30 * atr;
        }
        double best = c.get(i).low;
        for (int j = i + 1; j <= last; j++) best = Math.min(best, c.get(j).low);
        return c.get(i).high - best >= 0.30 * atr;
    }

    private static int nearest(List<Zone> zones, double price, double distance) {
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < zones.size(); i++) {
            double d = Math.abs(price - zones.get(i).center);
            if (d <= distance && d < bestDistance) {
                best = i;
                bestDistance = d;
            }
        }
        return best;
    }

    private static void registerPivot(List<Zone> zones, double price, boolean support,
                                      int index, boolean rejection, double halfWidth,
                                      double mergeDistance, int totalBars) {
        int found = nearest(zones, price, mergeDistance);
        if (found < 0) {
            if (zones.size() >= MAX_ZONES) return;
            Zone z = new Zone();
            z.center = price;
            z.halfWidth = halfWidth;
            zones.add(z);
            found = zones.size() - 1;
        }
        Zone z = zones.get(found);
        int previous = support ? z.lastSupportIndex : z.lastResistanceIndex;
        if (previous != Integer.MIN_VALUE && Math.abs(previous - index) < MIN_TOUCH_GAP) return;
        int oldWeight = z.touches();
        z.center = oldWeight > 0 ? (z.center * oldWeight + price) / (oldWeight + 1) : price;
        z.halfWidth = Math.max(z.halfWidth, halfWidth);
        z.lastTouchAge = Math.min(z.lastTouchAge, totalBars - 1 - index);
        if (support) {
            z.supportTouches++;
            z.lastSupportIndex = index;
            z.sourceMask |= 1;
            if (rejection) z.supportRejections++;
        } else {
            z.resistanceTouches++;
            z.lastResistanceIndex = index;
            z.sourceMask |= 2;
            if (rejection) z.resistanceRejections++;
        }
    }

    private static void addProfileZones(List<Zone> zones, List<Candle> candles,
                                        double halfWidth, double mergeDistance) {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (Candle c : candles) {
            min = Math.min(min, c.low);
            max = Math.max(max, c.high);
        }
        if (!(max > min)) return;
        double binSize = (max - min) / PROFILE_BINS;
        double[] profile = new double[PROFILE_BINS];
        int[] occupancy = new int[PROFILE_BINS];
        int[] lastIndex = new int[PROFILE_BINS];
        for (int i = 0; i < lastIndex.length; i++) lastIndex[i] = -1;

        for (int i = 0; i < candles.size(); i++) {
            Candle c = candles.get(i);
            int b0 = clamp((int) Math.floor((c.low - min) / binSize), 0, PROFILE_BINS - 1);
            int b1 = clamp((int) Math.floor((c.high - min) / binSize), 0, PROFILE_BINS - 1);
            int crossed = Math.max(1, b1 - b0 + 1);
            double share = Math.max(1, c.volume) / (double) crossed;
            for (int b = b0; b <= b1; b++) {
                profile[b] += share;
                occupancy[b]++;
                lastIndex[b] = i;
            }
        }
        double peak = 0;
        for (double v : profile) peak = Math.max(peak, v);
        if (!(peak > 0)) return;

        for (int b = 1; b < PROFILE_BINS - 1; b++) {
            double strength = profile[b] / peak;
            boolean localPeak = profile[b] >= profile[b - 1] && profile[b] >= profile[b + 1];
            if (!localPeak || strength < 0.58 || occupancy[b] < MIN_PROFILE_BARS) continue;
            double price = min + (b + 0.5) * binSize;
            registerProfile(zones, price, Math.max(halfWidth, 1.35 * binSize),
                    Math.max(mergeDistance, 1.5 * binSize), occupancy[b], strength,
                    candles.size() - 1 - lastIndex[b]);
        }
    }

    private static void registerProfile(List<Zone> zones, double price, double halfWidth,
                                        double mergeDistance, int tradeBars,
                                        double strength, int lastTouchAge) {
        int found = nearest(zones, price, mergeDistance);
        if (found < 0) {
            if (zones.size() >= MAX_ZONES) return;
            Zone z = new Zone();
            z.center = price;
            zones.add(z);
            found = zones.size() - 1;
        }
        Zone z = zones.get(found);
        int oldWeight = Math.min(20, z.touches() + z.acceptanceBars);
        int newWeight = Math.min(20, tradeBars);
        if (oldWeight + newWeight > 0) {
            z.center = (z.center * oldWeight + price * newWeight) / (oldWeight + newWeight);
        }
        z.halfWidth = Math.max(z.halfWidth, halfWidth);
        z.acceptanceBars = Math.max(z.acceptanceBars, tradeBars);
        z.profileStrength = Math.max(z.profileStrength, strength);
        z.sourceMask |= 4;
        z.lastTouchAge = Math.min(z.lastTouchAge, lastTouchAge);
    }

    private static void score(List<Zone> zones, List<Candle> candles, double atr) {
        double buffer = 0.12 * atr;
        for (Zone z : zones) {
            z.low = z.center - z.halfWidth;
            z.high = z.center + z.halfWidth;
            for (int i = 1; i < candles.size(); i++) {
                Candle older = candles.get(i - 1);
                Candle current = candles.get(i);
                if (older.close <= z.high + buffer && current.close > z.high + buffer) z.breaksUp++;
                if (older.close >= z.low - buffer && current.close < z.low - buffer) z.breaksDown++;
                if (current.high > z.high + buffer && current.close <= z.high &&
                        older.close <= z.high + buffer) z.falseBreaksUp++;
                if (current.low < z.low - buffer && current.close >= z.low &&
                        older.close >= z.low - buffer) z.falseBreaksDown++;
            }
            z.roleFlip = z.supportTouches > 0 && z.resistanceTouches > 0 &&
                    (z.breaksUp > 0 || z.breaksDown > 0);
            int reaction = Math.min(44, 11 * z.touches());
            int rejection = Math.min(21, 7 * z.rejections());
            int falsePoints = Math.min(6, 3 * (z.falseBreaksUp + z.falseBreaksDown));
            int profile = 0;
            if ((z.sourceMask & 4) != 0) {
                profile = 10 + Math.min(32, 2 * z.acceptanceBars) +
                        (int) Math.round(25 * z.profileStrength);
            }
            int fresh = z.lastTouchAge <= 12 ? 10 : (z.lastTouchAge <= 48 ? 6 : 0);
            z.score = Math.min(100, reaction + rejection + falsePoints + profile + fresh +
                    (z.roleFlip ? 8 : 0));
        }
    }

    private static boolean qualified(Zone z) {
        return (z.touches() >= MIN_TOUCHES ||
                ((z.sourceMask & 4) != 0 && z.acceptanceBars >= MIN_PROFILE_BARS)) &&
                z.score >= MIN_SCORE;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
