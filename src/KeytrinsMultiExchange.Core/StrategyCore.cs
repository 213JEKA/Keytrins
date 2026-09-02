using System.Globalization;
using System.Security.Cryptography;
using System.Text;

namespace KeytrinsMultiExchange.Core;

public sealed class StrategyParameters
{
    public int H1EmaFast { get; init; } = 50;
    public int H1EmaSlow { get; init; } = 200;
    public int H1SlopeBars { get; init; } = 3;
    public int M15EmaFast { get; init; } = 20;
    public int M15EmaSlow { get; init; } = 50;
    public int PullbackLookback { get; init; } = 4;
    public int AtrPeriod { get; init; } = 14;
    public double AdxMin { get; init; } = 22.0;
    public double AtrSlMultiplier { get; init; } = 1.2;
    public int SwingLookback { get; init; } = 5;
}

// Direct mathematical port of StrategyEngine.java blob 67efa3610582267ed6e12fa46be376768f4c76fe.
public sealed class OkxStrategyCore
{
    public const string ReferenceJavaBlob = "67efa3610582267ed6e12fa46be376768f4c76fe";
    private readonly StrategyParameters _s;

    public OkxStrategyCore(StrategyParameters? parameters = null) => _s = parameters ?? new StrategyParameters();

    public StrategyChartSnapshot BuildChart(string symbol, IReadOnlyList<Candle> h1,
        IReadOnlyList<Candle> m15, int maximumPoints = 120)
    {
        var decision = BuildSignal(symbol, h1, m15);
        var hFast = Indicators.Ema(h1, _s.H1EmaFast);
        var hSlow = Indicators.Ema(h1, _s.H1EmaSlow);
        var hAdx = Indicators.Adx(h1, _s.AtrPeriod);
        var mFast = Indicators.Ema(m15, _s.M15EmaFast);
        var mSlow = Indicators.Ema(m15, _s.M15EmaSlow);
        var mAtr = Indicators.Atr(m15, _s.AtrPeriod);
        var hi = h1.Count - 1;
        var mi = m15.Count - 1;
        var slopeIndex = hi - _s.H1SlopeBars;
        var from = Math.Max(0, m15.Count - Math.Max(20, maximumPoints));
        var points = Enumerable.Range(from, m15.Count - from).Select(index => new StrategyChartPoint(
            m15[index].StartMs, m15[index].Open, m15[index].High, m15[index].Low, m15[index].Close,
            Finite(mFast[index]), Finite(mSlow[index]))).ToArray();
        var h1Passed = decision.Reason is "NO_M15_PULLBACK" or "NO_M15_CONFIRMATION" or "INVALID_STOP" or "SIGNAL";
        var pullbackPassed = decision.Reason is "NO_M15_CONFIRMATION" or "INVALID_STOP" or "SIGNAL";
        var confirmationPassed = decision.Reason is "INVALID_STOP" or "SIGNAL";
        return new(symbol, DateTimeOffset.UtcNow, decision.Reason, decision.Signal,
            ValueAt(hFast, hi), ValueAt(hSlow, hi), ValueAt(hFast, slopeIndex),
            hi >= 0 ? Finite(h1[hi].Close) : null, ValueAt(hAdx, hi), _s.AdxMin,
            ValueAt(mAtr, mi), _s.AtrSlMultiplier, h1Passed, pullbackPassed, confirmationPassed, points);
    }

    public StrategyDecision BuildSignal(string symbol, IReadOnlyList<Candle> h1, IReadOnlyList<Candle> m15)
    {
        var needH1 = Math.Max(_s.H1EmaSlow + 10, 220);
        var needM15 = Math.Max(_s.M15EmaSlow + 15, 80);
        if (h1.Count < needH1 || m15.Count < needM15) return new(null, "NOT_ENOUGH_BARS");

        var hFast = Indicators.Ema(h1, _s.H1EmaFast);
        var hSlow = Indicators.Ema(h1, _s.H1EmaSlow);
        var hAdx = Indicators.Adx(h1, _s.AtrPeriod);
        var mFast = Indicators.Ema(m15, _s.M15EmaFast);
        var mSlow = Indicators.Ema(m15, _s.M15EmaSlow);
        var mAtr = Indicators.Atr(m15, _s.AtrPeriod);

        var hi = h1.Count - 1;
        var mi = m15.Count - 1;
        var pi = mi - 1;
        var adx = hAdx[hi];
        var fastNow = hFast[hi];
        var slow = hSlow[hi];
        var hClose = h1[hi].Close;
        var slopeIdx = hi - _s.H1SlopeBars;
        if (slopeIdx < 0 || double.IsNaN(adx) || double.IsNaN(mAtr[mi])) return new(null, "INDICATOR_NAN");
        var fastThen = hFast[slopeIdx];

        var longTrend = fastNow > slow && fastNow > fastThen && adx >= _s.AdxMin && hClose > fastNow;
        var shortTrend = fastNow < slow && fastNow < fastThen && adx >= _s.AdxMin && hClose < fastNow;
        if (!longTrend && !shortTrend) return new(null, "NO_H1_TREND");
        var baseDirection = longTrend ? TradeDirection.Long : TradeDirection.Short;

        var touched = false;
        var from = Math.Max(0, mi - _s.PullbackLookback);
        for (var i = from; i < mi; i++)
        {
            var candle = m15[i];
            var lo = Math.Min(mFast[i], mSlow[i]);
            var hiZone = Math.Max(mFast[i], mSlow[i]);
            if (candle.Low <= hiZone && candle.High >= lo) { touched = true; break; }
        }
        if (!touched) return new(null, "NO_M15_PULLBACK");

        var current = m15[mi];
        var previous = m15[pi];
        var confirmed = longTrend
            ? current.Close > current.Open && current.Close > previous.High && current.Close > mFast[mi]
            : current.Close < current.Open && current.Close < previous.Low && current.Close < mFast[mi];
        if (!confirmed) return new(null, "NO_M15_CONFIRMATION");

        var entry = current.Close;
        var atr = mAtr[mi];
        double stop;
        var swingFrom = Math.Max(0, m15.Count - _s.SwingLookback);
        if (longTrend)
        {
            var atrStop = entry - _s.AtrSlMultiplier * atr;
            var swing = double.PositiveInfinity;
            for (var i = swingFrom; i < m15.Count; i++) swing = Math.Min(swing, m15[i].Low);
            stop = Math.Min(atrStop, swing);
        }
        else
        {
            var atrStop = entry + _s.AtrSlMultiplier * atr;
            var swing = double.NegativeInfinity;
            for (var i = swingFrom; i < m15.Count; i++) swing = Math.Max(swing, m15[i].High);
            stop = Math.Max(atrStop, swing);
        }
        var risk = Math.Abs(entry - stop);
        if (!(risk > 0) || !(atr > 0)) return new(null, "INVALID_STOP");

        var emaSepPct = Math.Abs(fastNow - slow) / hClose * 100.0;
        var slopePct = Math.Abs(fastNow - fastThen) / hClose * 100.0;
        var score = Math.Min(100.0, adx * 1.6 + emaSepPct * 30.0 + slopePct * 60.0);
        var signalTime = current.StartMs + 15 * 60_000L;
        var idMaterial = $"OKX|{symbol}|{signalTime}|{baseDirection.ToString().ToUpperInvariant()}";
        var signalId = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(idMaterial)))[..24].ToLowerInvariant();
        var signal = new CanonicalSignal(signalId, "OKX", symbol, signalTime, baseDirection, entry, stop, risk,
            risk / entry, atr, adx, score, "H1_TREND_M15_PULLBACK_CONFIRM", DateTimeOffset.UtcNow);
        return new(signal, "SIGNAL");
    }

    private static double? ValueAt(IReadOnlyList<double> values, int index) =>
        index >= 0 && index < values.Count ? Finite(values[index]) : null;

    private static double? Finite(double value) => double.IsFinite(value) ? value : null;
}

public static class Indicators
{
    public static double[] Ema(IReadOnlyList<Candle> candles, int period)
    {
        var output = new double[candles.Count];
        if (candles.Count == 0) return output;
        var alpha = 2.0 / (period + 1.0);
        output[0] = candles[0].Close;
        for (var i = 1; i < candles.Count; i++) output[i] = alpha * candles[i].Close + (1 - alpha) * output[i - 1];
        return output;
    }

    public static double[] Atr(IReadOnlyList<Candle> candles, int period)
    {
        var count = candles.Count;
        var tr = new double[count];
        var output = new double[count];
        if (count == 0) return output;
        tr[0] = candles[0].High - candles[0].Low;
        for (var i = 1; i < count; i++)
        {
            var candle = candles[i];
            var previousClose = candles[i - 1].Close;
            tr[i] = Math.Max(candle.High - candle.Low,
                Math.Max(Math.Abs(candle.High - previousClose), Math.Abs(candle.Low - previousClose)));
        }
        var sum = 0.0;
        for (var i = 0; i < count; i++)
        {
            if (i < period)
            {
                sum += tr[i];
                output[i] = i == period - 1 ? sum / period : double.NaN;
            }
            else output[i] = ((output[i - 1] * (period - 1)) + tr[i]) / period;
        }
        return output;
    }

    public static double[] Adx(IReadOnlyList<Candle> candles, int period)
    {
        var count = candles.Count;
        var tr = new double[count]; var pdm = new double[count]; var mdm = new double[count];
        var atrSm = new double[count]; var pSm = new double[count]; var mSm = new double[count];
        var dx = new double[count]; var adx = Enumerable.Repeat(double.NaN, count).ToArray();
        if (count < period * 2 + 2) return adx;
        for (var i = 1; i < count; i++)
        {
            var current = candles[i]; var previous = candles[i - 1];
            var up = current.High - previous.High; var down = previous.Low - current.Low;
            pdm[i] = up > down && up > 0 ? up : 0;
            mdm[i] = down > up && down > 0 ? down : 0;
            tr[i] = Math.Max(current.High - current.Low,
                Math.Max(Math.Abs(current.High - previous.Close), Math.Abs(current.Low - previous.Close)));
        }
        var trSum = 0.0; var pSum = 0.0; var mSum = 0.0;
        for (var i = 1; i <= period; i++) { trSum += tr[i]; pSum += pdm[i]; mSum += mdm[i]; }
        atrSm[period] = trSum; pSm[period] = pSum; mSm[period] = mSum;
        for (var i = period + 1; i < count; i++)
        {
            atrSm[i] = atrSm[i - 1] - atrSm[i - 1] / period + tr[i];
            pSm[i] = pSm[i - 1] - pSm[i - 1] / period + pdm[i];
            mSm[i] = mSm[i - 1] - mSm[i - 1] / period + mdm[i];
        }
        for (var i = period; i < count; i++)
        {
            if (atrSm[i] <= 0) continue;
            var pdi = 100.0 * pSm[i] / atrSm[i]; var mdi = 100.0 * mSm[i] / atrSm[i];
            var denominator = pdi + mdi;
            dx[i] = denominator <= 0 ? 0 : 100.0 * Math.Abs(pdi - mdi) / denominator;
        }
        var dxSum = 0.0; var start = period * 2 - 1;
        for (var i = period; i <= start; i++) dxSum += dx[i];
        adx[start] = dxSum / period;
        for (var i = start + 1; i < count; i++) adx[i] = ((adx[i - 1] * (period - 1)) + dx[i]) / period;
        return adx;
    }
}
