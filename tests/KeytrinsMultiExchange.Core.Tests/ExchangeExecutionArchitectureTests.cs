using KeytrinsMultiExchange.Core;
using Xunit;

namespace KeytrinsMultiExchange.Core.Tests;

public sealed class ExchangeExecutionArchitectureTests
{
    [Theory]
    [InlineData("BTC-USDT-SWAP", "BTC-USDT-SWAP", "BTCUSDT", "XBTUSDTM")]
    [InlineData("XRP-USDT-SWAP", "XRP-USDT-SWAP", "XRPUSDT", "XRPUSDTM")]
    public void One_okx_asset_maps_deterministically_to_each_exchange(string source, string okx, string bybit,
        string kucoin)
    {
        Assert.Equal(okx, ExchangeSymbolMapper.Map(ExchangeId.Okx, source));
        Assert.Equal(bybit, ExchangeSymbolMapper.Map(ExchangeId.Bybit, source));
        Assert.Equal(kucoin, ExchangeSymbolMapper.Map(ExchangeId.KuCoinFutures, source));
    }

    [Theory]
    [InlineData("Long", "0.001", "1", "1")]
    [InlineData("Long", "0.01", "0.1", "1")]
    [InlineData("Short", "0.01", "1", "0.1")]
    public void Every_exchange_plan_uses_fixed_notional_and_preserves_net_loss_limit(string directionName,
        string tickText, string quantityStepText, string contractValueText)
    {
        var direction = Enum.Parse<TradeDirection>(directionName);
        var signal = Signal(direction == TradeDirection.Long ? TradeDirection.Short : TradeDirection.Long, 0.02);
        var options = Options();
        var tick = decimal.Parse(tickText, System.Globalization.CultureInfo.InvariantCulture);
        var step = decimal.Parse(quantityStepText, System.Globalization.CultureInfo.InvariantCulture);
        var contractValue = decimal.Parse(contractValueText, System.Globalization.CultureInfo.InvariantCulture);
        var quote = new MarketQuote("TEST", 9.99m, 10.01m, 10m, DateTimeOffset.UtcNow);
        var rules = new InstrumentRules("TEST", tick, step, step, 1_000_000m, 0m, contractValue);

        var plan = EntryPlanner.Plan(ExchangeId.Bybit, signal, "TEST", quote, rules, 0.001m, options);

        var notional = plan.ReferencePrice * plan.Quantity * plan.ContractValue;
        Assert.True(notional <= options.PositionNotionalUsdt);
        Assert.True(notional >= options.PositionNotionalUsdt - plan.ReferencePrice * plan.ContractValue * step);
        var entryFee = notional * plan.TakerFeeRate;
        var netAtHardStop = RiskController.EstimateNetPnl(plan.Direction, plan.ReferencePrice, plan.HardLossStop,
            plan.Quantity * plan.ContractValue, entryFee, plan.TakerFeeRate);
        Assert.True(netAtHardStop >= -options.MaxNetLossUsdt);
        Assert.Equal(direction, plan.Direction);
    }

    [Fact]
    public void Coarse_tick_rejects_entry_when_one_point_five_net_loss_cannot_be_guaranteed()
    {
        var error = Assert.Throws<ExecutionRejectedException>(() => EntryPlanner.Plan(ExchangeId.Bybit,
            Signal(TradeDirection.Long, 0.02), "TEST",
            new("TEST", 9.99m, 10.01m, 10m, DateTimeOffset.UtcNow),
            new("TEST", 0.1m, 1m, 1m, 1_000_000m, 0m, 0.1m), 0.001m, Options()));

        Assert.Equal("INVALID_INITIAL_STOP", error.Reason);
    }

    [Fact]
    public void Position_amount_no_longer_changes_with_okx_risk_distance()
    {
        var options = Options();
        var quote = new MarketQuote("XRPUSDT", 1.329m, 1.330m, 1.3295m, DateTimeOffset.UtcNow);
        var rules = new InstrumentRules("XRPUSDT", 0.0001m, 1m, 1m, 1_000_000m, 5m, 1m);
        var narrow = EntryPlanner.Plan(ExchangeId.Bybit, Signal(TradeDirection.Short, 0.005), quote.Symbol,
            quote, rules, 0.001m, options);
        var wide = EntryPlanner.Plan(ExchangeId.Bybit, Signal(TradeDirection.Short, 0.05), quote.Symbol,
            quote, rules, 0.001m, options);

        Assert.Equal(narrow.Quantity, wide.Quantity);
        Assert.Equal(100m, options.PositionNotionalUsdt);
    }

    private static CanonicalSignal Signal(TradeDirection baseDirection, double riskDistancePct) =>
        new("architecture-signal-" + riskDistancePct, "OKX", "XRP-USDT-SWAP",
            DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), baseDirection, 1.33,
            baseDirection == TradeDirection.Long ? 1.30 : 1.36, 1.33 * riskDistancePct,
            riskDistancePct, 0.01, 30, 100, "TEST", DateTimeOffset.UtcNow);

    private static RuntimeOptions Options() => new()
    {
        PositionNotionalUsdt = 100m,
        MaxNetLossUsdt = 1.50m,
        MaxNotionalUsdt = 1_000m,
        MaxCostR = 1m,
        ExecutionSlippageBufferBps = 2m
    };
}
