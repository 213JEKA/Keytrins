using KeytrinsMultiExchange.Core;
using Xunit;

namespace KeytrinsMultiExchange.Core.Tests;

public sealed class StrategyChartTests
{
    [Fact]
    public void Chart_uses_the_same_strategy_decision_and_indicator_implementation()
    {
        var h1 = Candles(240, 100, 0.10);
        var m15 = Candles(100, 100, 0.03);
        var strategy = new OkxStrategyCore();

        var decision = strategy.BuildSignal("TEST-USDT-SWAP", h1, m15);
        var chart = strategy.BuildChart("TEST-USDT-SWAP", h1, m15);

        Assert.Equal(decision.Reason, chart.Decision);
        Assert.Equal(decision.Signal?.SignalId, chart.Signal?.SignalId);
        Assert.Equal(100, chart.Points.Count);
        Assert.Equal(22, chart.AdxMinimum);
        Assert.Equal(1.2, chart.AtrStopMultiplier);
    }

    private static IReadOnlyList<Candle> Candles(int count, double start, double step) => Enumerable.Range(0, count)
        .Select(index =>
        {
            var open = start + index * step;
            var close = open + step / 2;
            return new Candle(index * 60_000L, open, close + step, open - step, close, 1, 1);
        }).ToArray();
}
