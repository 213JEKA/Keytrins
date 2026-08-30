package com.keytrins.liveresearch.strategy;

import com.keytrins.liveresearch.model.Candle;

import java.util.List;

public final class Indicators {
    public static double[] ema(List<Candle> c, int period) {
        double[] out = new double[c.size()];
        if (c.isEmpty()) return out;
        double a = 2.0 / (period + 1.0);
        out[0] = c.get(0).close;
        for (int i = 1; i < c.size(); i++) out[i] = a * c.get(i).close + (1 - a) * out[i - 1];
        return out;
    }

    public static double[] atr(List<Candle> c, int period) {
        int n = c.size();
        double[] tr = new double[n];
        double[] out = new double[n];
        if (n == 0) return out;
        tr[0] = c.get(0).high - c.get(0).low;
        for (int i = 1; i < n; i++) {
            Candle x = c.get(i);
            double prevClose = c.get(i - 1).close;
            tr[i] = Math.max(x.high - x.low, Math.max(Math.abs(x.high - prevClose), Math.abs(x.low - prevClose)));
        }
        double sum = 0;
        for (int i = 0; i < n; i++) {
            if (i < period) {
                sum += tr[i];
                out[i] = i == period - 1 ? sum / period : Double.NaN;
            } else {
                out[i] = ((out[i - 1] * (period - 1)) + tr[i]) / period;
            }
        }
        return out;
    }

    public static double[] adx(List<Candle> c, int period) {
        int n = c.size();
        double[] tr = new double[n], pdm = new double[n], mdm = new double[n];
        double[] atrSm = new double[n], pSm = new double[n], mSm = new double[n];
        double[] dx = new double[n], adx = new double[n];
        for (int i = 0; i < n; i++) adx[i] = Double.NaN;
        if (n < period * 2 + 2) return adx;

        for (int i = 1; i < n; i++) {
            Candle x = c.get(i), p = c.get(i - 1);
            double up = x.high - p.high;
            double down = p.low - x.low;
            pdm[i] = (up > down && up > 0) ? up : 0;
            mdm[i] = (down > up && down > 0) ? down : 0;
            tr[i] = Math.max(x.high - x.low, Math.max(Math.abs(x.high - p.close), Math.abs(x.low - p.close)));
        }
        double trSum = 0, pSum = 0, mSum = 0;
        for (int i = 1; i <= period; i++) { trSum += tr[i]; pSum += pdm[i]; mSum += mdm[i]; }
        atrSm[period] = trSum; pSm[period] = pSum; mSm[period] = mSum;
        for (int i = period + 1; i < n; i++) {
            atrSm[i] = atrSm[i - 1] - atrSm[i - 1] / period + tr[i];
            pSm[i] = pSm[i - 1] - pSm[i - 1] / period + pdm[i];
            mSm[i] = mSm[i - 1] - mSm[i - 1] / period + mdm[i];
        }
        for (int i = period; i < n; i++) {
            if (atrSm[i] <= 0) continue;
            double pdi = 100.0 * pSm[i] / atrSm[i];
            double mdi = 100.0 * mSm[i] / atrSm[i];
            double den = pdi + mdi;
            dx[i] = den <= 0 ? 0 : 100.0 * Math.abs(pdi - mdi) / den;
        }
        double dxSum = 0;
        int start = period * 2 - 1;
        for (int i = period; i <= start; i++) dxSum += dx[i];
        adx[start] = dxSum / period;
        for (int i = start + 1; i < n; i++) adx[i] = ((adx[i - 1] * (period - 1)) + dx[i]) / period;
        return adx;
    }

    private Indicators() {}
}
