using KeytrinsMultiExchange.Core;
using Xunit;

namespace KeytrinsMultiExchange.Core.Tests;

public sealed class EntryPlannerTests
{
    [Fact]
    public void Plans_inverse_short_with_exchange_contract_value_and_hard_loss()
    {
        var signal = Signal(TradeDirection.Long);
        var quote = new MarketQuote("UNI-USDT-SWAP", 5.200m, 5.201m, 5.2005m, DateTimeOffset.UtcNow);
        var rules = new InstrumentRules(quote.Symbol, 0.001m, 1m, 1m, 100000m, 5m, 0.1m);
        var plan = EntryPlanner.Plan(ExchangeId.Okx, signal, quote.Symbol, quote, rules, 0.001m, Options());
        Assert.Equal(TradeDirection.Short, plan.Direction);
        Assert.Equal(decimal.Floor((100m / 5.20m) / 0.1m), plan.Quantity);
        Assert.True(plan.InitialStop > quote.Ask);
        var baseQuantity = plan.Quantity * rules.ContractValue;
        var entryFee = plan.ReferencePrice * baseQuantity * plan.TakerFeeRate;
        var netAtHard = RiskController.EstimateNetPnl(plan.Direction, plan.ReferencePrice, plan.HardLossStop,
            baseQuantity, entryFee, plan.TakerFeeRate);
        Assert.True(netAtHard >= -Options().MaxNetLossUsdt);
    }

    [Fact]
    public void Rejects_max_notional_instead_of_silently_capping_risk()
    {
        var options = Options(); options.MaxNotionalUsdt = 10m;
        var error = Assert.Throws<ExecutionRejectedException>(() => EntryPlanner.Plan(ExchangeId.Bybit,
            Signal(TradeDirection.Short), "UNIUSDT", new("UNIUSDT", 5.20m, 5.21m, 5.205m, DateTimeOffset.UtcNow),
            new("UNIUSDT", 0.001m, 0.1m, 0.1m, 100000m, 5m), 0.001m, options));
        Assert.Equal("MAX_NOTIONAL", error.Reason);
    }

    [Fact]
    public void Rejects_cost_over_limit()
    {
        var options = Options(); options.MaxCostR = 0.01m;
        var error = Assert.Throws<ExecutionRejectedException>(() => EntryPlanner.Plan(ExchangeId.Bybit,
            Signal(TradeDirection.Short), "UNIUSDT", new("UNIUSDT", 5.00m, 5.20m, 5.10m, DateTimeOffset.UtcNow),
            new("UNIUSDT", 0.001m, 0.1m, 0.1m, 100000m, 5m), 0.001m, options));
        Assert.Equal("COST_R", error.Reason);
    }

    [Theory]
    [InlineData("1.239", "0.01", "1.23")]
    [InlineData("5", "1", "5")]
    [InlineData("0.009", "0.01", "0")]
    public void Floors_quantity_to_exchange_step(string value, string step, string expected) =>
        Assert.Equal(decimal.Parse(expected, System.Globalization.CultureInfo.InvariantCulture), EntryPlanner.FloorToStep(
            decimal.Parse(value, System.Globalization.CultureInfo.InvariantCulture),
            decimal.Parse(step, System.Globalization.CultureInfo.InvariantCulture)));

    private static CanonicalSignal Signal(TradeDirection baseDirection) => new("planner-signal", "OKX", "UNI-USDT-SWAP",
        1, baseDirection, 5.2, baseDirection == TradeDirection.Long ? 5.096 : 5.304, 0.104, 0.02,
        0.08, 25, 1, "H1_TREND_M15_PULLBACK_CONFIRM", DateTimeOffset.UtcNow);

    private static RuntimeOptions Options() => new()
    {
        RiskUsdt = 3m, PositionNotionalUsdt = 100m, MaxNetLossUsdt = 1.50m,
        MaxNotionalUsdt = 1000m, MaxCostR = 0.25m,
        ExecutionSlippageBufferBps = 2m
    };
}
