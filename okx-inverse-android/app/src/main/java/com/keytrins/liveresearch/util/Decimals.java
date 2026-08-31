package com.keytrins.liveresearch.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Decimals {
    public static BigDecimal bd(double v) { return new BigDecimal(Double.toString(v)); }
    public static BigDecimal bd(String v) { return new BigDecimal(v); }

    public static BigDecimal floorStep(BigDecimal value, BigDecimal step) {
        if (step.signum() <= 0) return value;
        BigDecimal n = value.divide(step, 0, RoundingMode.FLOOR);
        return n.multiply(step).stripTrailingZeros();
    }

    public static BigDecimal ceilStep(BigDecimal value, BigDecimal step) {
        if (step.signum() <= 0) return value;
        BigDecimal n = value.divide(step, 0, RoundingMode.CEILING);
        return n.multiply(step).stripTrailingZeros();
    }

    public static BigDecimal floorTick(double value, BigDecimal tick) {
        return floorStep(bd(value), tick);
    }

    public static BigDecimal ceilTick(double value, BigDecimal tick) {
        return ceilStep(bd(value), tick);
    }

    public static String fmt(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private Decimals() {}
}
