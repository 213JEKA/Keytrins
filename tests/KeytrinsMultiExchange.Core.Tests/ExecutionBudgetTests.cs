using KeytrinsMultiExchange.Core;
using Xunit;

namespace KeytrinsMultiExchange.Core.Tests;

public sealed class ExecutionBudgetTests
{
    [Fact]
    public void Allows_entry_when_exchange_reports_sufficient_available_margin()
    {
        var entry = Entry();
        var options = new RuntimeOptions { MaxNetLossUsdt = 1.50m };

        ExecutionBudget.RequireAvailableMargin(entry, options, 21.71m);
    }

    [Fact]
    public void Rejects_before_submit_when_exchange_margin_is_insufficient()
    {
        var error = Assert.Throws<ExecutionRejectedException>(() =>
            ExecutionBudget.RequireAvailableMargin(
                Entry(),
                new RuntimeOptions { MaxNetLossUsdt = 1.50m },
                21.69m));

        Assert.Equal("INSUFFICIENT_AVAILABLE_MARGIN_PRECHECK", error.Reason);
    }

    [Fact]
    public void Rejects_when_current_exchange_margin_cannot_be_verified()
    {
        var error = Assert.Throws<ExecutionRejectedException>(() =>
            ExecutionBudget.RequireAvailableMargin(
                Entry(),
                new RuntimeOptions { MaxNetLossUsdt = 1.50m },
                0m));

        Assert.Equal("AVAILABLE_MARGIN_UNVERIFIED", error.Reason);
    }

    private static PreparedEntry Entry() => new(
        ExchangeId.Bybit,
        "margin-test",
        "entry-client-id",
        "ETHUSDT",
        TradeDirection.Long,
        5,
        1m,
        1m,
        100m,
        99m,
        99m,
        99m,
        0.01m,
        0.001m,
        0.1m,
        0m);
}
