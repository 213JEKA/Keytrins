using KeytrinsMultiExchange.Core;
using Microsoft.Data.Sqlite;
using Xunit;

namespace KeytrinsMultiExchange.Core.Tests;

public sealed class TradingDatabaseRouteAttemptTests : IDisposable
{
    private readonly string _directory = Path.Combine(Path.GetTempPath(), $"keytrins-db-{Guid.NewGuid():N}");

    [Fact]
    public async Task Restores_only_latest_route_attempt_for_each_exchange()
    {
        var database = new TradingDatabase(_directory);
        await database.InitializeAsync(default);
        var first = Signal("first");
        var second = Signal("second");
        Assert.True(await database.InsertSignalOnceAsync(first, default));
        Assert.True(await database.InsertSignalOnceAsync(second, default));
        await database.InsertRouteAttemptAsync(new RouteAttempt(ExchangeId.Okx, first.SignalId,
            DateTimeOffset.UtcNow.AddMinutes(-1), null, null, RouteResult.Rejected, "OLD_REASON"), default);
        await database.InsertRouteAttemptAsync(new RouteAttempt(ExchangeId.Okx, second.SignalId,
            DateTimeOffset.UtcNow, null, null, RouteResult.Rejected, "EXACT_OKX_REASON"), default);
        await database.InsertRouteAttemptAsync(new RouteAttempt(ExchangeId.Bybit, second.SignalId,
            DateTimeOffset.UtcNow, null, null, RouteResult.Skipped, "FOLLOWER_REASON"), default);

        var restored = await database.GetLatestRouteAttemptsAsync(default);

        Assert.Equal(2, restored.Count);
        Assert.Equal("EXACT_OKX_REASON", restored.Single(x => x.Exchange == ExchangeId.Okx).Reason);
        Assert.Equal("FOLLOWER_REASON", restored.Single(x => x.Exchange == ExchangeId.Bybit).Reason);
    }

    private static CanonicalSignal Signal(string id) => new(id, "OKX", "UNI-USDT-SWAP", 1,
        TradeDirection.Long, 5.2, 5.1, 0.1, 0.02, 0.08, 25, 1, "TEST", DateTimeOffset.UtcNow);

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        if (Directory.Exists(_directory)) Directory.Delete(_directory, true);
    }
}
