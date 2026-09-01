using KeytrinsMultiExchange.Core;
using Xunit;

namespace KeytrinsMultiExchange.Core.Tests;

public sealed class RouteReasonCatalogTests
{
    [Theory]
    [InlineData("BYBIT_110123", "Trading Terms")]
    [InlineData("BYBIT_110123:You must agree to the Trading Terms", "Trading Terms")]
    [InlineData("INVALID_INITIAL_STOP", "Заявка не отправлялась")]
    [InlineData("OKX_1", "общий код OKX 1")]
    [InlineData("FEE_RATE_UNAVAILABLE", "комиссию")]
    public void Known_route_reason_has_actionable_explanation(string reason, string expected)
    {
        Assert.Contains(expected, RouteReasonCatalog.Explain(reason), StringComparison.OrdinalIgnoreCase);
    }

    [Fact]
    public void Unknown_route_reason_is_preserved_verbatim()
    {
        Assert.Equal("EXACT_EXCHANGE_REASON", RouteReasonCatalog.Explain("EXACT_EXCHANGE_REASON"));
    }
}
