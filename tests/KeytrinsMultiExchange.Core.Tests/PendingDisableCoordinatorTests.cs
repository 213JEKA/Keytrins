using KeytrinsMultiExchange.Core;
using Microsoft.Data.Sqlite;
using Xunit;

namespace KeytrinsMultiExchange.Core.Tests;

public sealed class PendingDisableCoordinatorTests : IDisposable
{
    private readonly string _directory = Path.Combine(Path.GetTempPath(), $"keytrins-disable-{Guid.NewGuid():N}");

    [Fact]
    public async Task Remains_disabling_until_managed_position_is_confirmed_closed()
    {
        var database = new TradingDatabase(_directory);
        await database.InitializeAsync(default);
        var settings = new RuntimeSettingsStore(new RuntimeOptions(), _directory);
        settings.SetExchangeMode(ExchangeId.Okx, ExchangeMode.Disabling);
        var snapshot = new RuntimeSnapshot();
        snapshot.Exchanges[ExchangeId.Okx] = new(ExchangeId.Okx, ExchangeMode.Disabling, true, true,
            true, false, "ONLINE", null, null, 0, 0, 1, null, DateTimeOffset.UtcNow, "CLOSE_PENDING");
        var position = new ManagedPosition(ExchangeId.Okx, "signal", "BTC-USDT-SWAP", TradeDirection.Long,
            100m, 100m, 1m, 1m, 1m, 0.1m, 0.001m, 0.01m, 0m, 0m, 99m, 99m, 99m, 0.1m,
            DateTimeOffset.UtcNow);
        await database.UpsertPositionAsync(position, default);
        var coordinator = new PendingDisableCoordinator(database, settings, snapshot);

        await coordinator.CompleteConfirmedFlatAsync(default);
        Assert.Equal(ExchangeMode.Disabling, settings.Current.Exchanges[ExchangeId.Okx.ToString()]);

        await database.MarkPositionClosedAsync(ExchangeId.Okx, position.SignalId, "TEST_FLAT", default);
        await coordinator.CompleteConfirmedFlatAsync(default);

        Assert.Equal(ExchangeMode.Off, settings.Current.Exchanges[ExchangeId.Okx.ToString()]);
        Assert.Equal(ExchangeMode.Off, snapshot.Exchanges[ExchangeId.Okx].Mode);
        Assert.Equal("MANAGED_FLAT_CONFIRMED_OFF", snapshot.Exchanges[ExchangeId.Okx].Detail);
    }

    [Fact]
    public async Task Restarted_disabling_state_completes_when_database_is_already_flat()
    {
        var database = new TradingDatabase(_directory);
        await database.InitializeAsync(default);
        var initial = new RuntimeSettingsStore(new RuntimeOptions(), _directory);
        initial.SetExchangeMode(ExchangeId.Bybit, ExchangeMode.Disabling);
        var restored = new RuntimeSettingsStore(new RuntimeOptions(), _directory);
        var coordinator = new PendingDisableCoordinator(database, restored, new RuntimeSnapshot());

        await coordinator.CompleteConfirmedFlatAsync(default);

        Assert.Equal(ExchangeMode.Off, restored.Current.Exchanges[ExchangeId.Bybit.ToString()]);
    }

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        if (Directory.Exists(_directory)) Directory.Delete(_directory, true);
    }
}
