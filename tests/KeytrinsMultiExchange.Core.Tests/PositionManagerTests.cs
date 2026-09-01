using KeytrinsMultiExchange.Core;
using Microsoft.Data.Sqlite;
using Xunit;

namespace KeytrinsMultiExchange.Core.Tests;

public sealed class PositionManagerTests : IDisposable
{
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "keytrins-position-tests-" + Guid.NewGuid().ToString("N"));

    [Theory]
    [InlineData("Long", "101.502", "1.00")]
    [InlineData("Short", "98.499", "1.00")]
    [InlineData("Long", "102.203", "1.50")]
    [InlineData("Long", "110.212", "9.50")]
    public void Profit_staircase_uses_net_peak_and_never_loosens(string directionText, string markText, string protectedText)
    {
        var direction = Enum.Parse<TradeDirection>(directionText);
        var position = Position(direction) with { CurrentStop = direction == TradeDirection.Long ? 99m : 101m };
        var truth = Truth(direction, decimal.Parse(markText, System.Globalization.CultureInfo.InvariantCulture), position.CurrentStop);
        var updated = PositionManager.Calculate(position, truth, Options());
        Assert.Equal(decimal.Parse(protectedText, System.Globalization.CultureInfo.InvariantCulture), updated.ProtectedNetProfitUsdt);
        var desired = PositionManager.DesiredStop(updated, Options().ExecutionSlippageBufferBps);
        Assert.False(RiskController.WouldLoosen(direction, position.CurrentStop, desired));
        var estimated = RiskController.EstimateNetPnl(direction, updated.EntryPrice, desired,
            updated.RemainingQuantity * updated.ContractValue, updated.EntryFee, updated.TakerFeeRate);
        Assert.True(estimated >= updated.ProtectedNetProfitUsdt);
    }

    [Fact]
    public async Task Maximum_net_loss_submits_one_close_and_confirms_exchange_flat()
    {
        var database = await DatabaseAsync(); var position = Position(TradeDirection.Long);
        await database.UpsertPositionAsync(position, default);
        var transport = new FakeTransport(ExchangeId.Okx, [Truth(TradeDirection.Long, 98m, 99m)]) { FlattenOnClose = true };
        var manager = Manager(database, transport);
        await manager.RunOnceAsync(Options(), default);
        await manager.RunOnceAsync(Options(), default);
        Assert.Equal(1, transport.CloseCount);
        Assert.Equal(0, await database.CountUnresolvedRiskActionsAsync(default));
        Assert.Empty(await database.LoadOpenManagedPositionsAsync(default));
    }

    [Fact]
    public async Task Missing_exchange_stop_is_restored_once_and_confirmed()
    {
        var database = await DatabaseAsync(); var position = Position(TradeDirection.Long);
        await database.UpsertPositionAsync(position, default);
        var transport = new FakeTransport(ExchangeId.Okx, [Truth(TradeDirection.Long, 100m, 0m)]) { ApplyStop = true };
        var manager = Manager(database, transport);
        await manager.RunOnceAsync(Options(), default);
        await manager.RunOnceAsync(Options(), default);
        Assert.Equal(1, transport.StopCount);
        Assert.Equal(0, await database.CountUnresolvedRiskActionsAsync(default));
        Assert.True(transport.Positions.Single().StopPrice >= position.CurrentStop);
    }

    [Fact]
    public async Task Ambiguous_stop_is_not_blindly_retried()
    {
        var database = await DatabaseAsync(); var position = Position(TradeDirection.Long);
        await database.UpsertPositionAsync(position, default);
        var transport = new FakeTransport(ExchangeId.Okx, [Truth(TradeDirection.Long, 100m, 0m)]) { AmbiguousStop = true };
        var manager = Manager(database, transport);
        await manager.RunOnceAsync(Options(), default);
        await manager.RunOnceAsync(Options(), default);
        Assert.Equal(1, transport.StopCount);
        Assert.Equal(1, await database.CountUnresolvedRiskActionsAsync(default));
    }

    [Fact]
    public void Partial_reduction_uses_remaining_contract_quantity()
    {
        var position = Position(TradeDirection.Long) with { Quantity = 10m, RemainingQuantity = 10m, ContractValue = 0.1m, EntryFee = 0.1m };
        var truth = Truth(TradeDirection.Long, 101m, 99m) with { Quantity = 4m };
        var updated = PositionManager.Calculate(position, truth, Options());
        Assert.Equal(4m, updated.RemainingQuantity);
        Assert.Equal(0.04m, updated.EntryFee);
        Assert.Equal(0.3196m, updated.PeakNetProfitUsdt);
    }

    [Fact]
    public async Task Failure_on_one_exchange_does_not_stop_other_exchange_management()
    {
        var database = await DatabaseAsync();
        await database.UpsertPositionAsync(Position(TradeDirection.Long) with { Exchange = ExchangeId.Okx }, default);
        await database.UpsertPositionAsync(Position(TradeDirection.Long) with
            { Exchange = ExchangeId.Bybit, SignalId = "signal-bybit", Symbol = "BTCUSDT" }, default);
        var failing = new FakeTransport(ExchangeId.Okx, []) { FailTruth = true };
        var healthy = new FakeTransport(ExchangeId.Bybit, [Truth(TradeDirection.Long, 98m, 99m) with { Symbol = "BTCUSDT" }])
        { FlattenOnClose = true };
        var manager = new PositionManager(database, [failing, healthy], new ExchangeOperationLocks(), new RuntimeSnapshot());
        await manager.RunOnceAsync(Options(), default);
        Assert.Equal(1, healthy.CloseCount);
    }

    [Fact]
    public async Task External_position_is_visible_but_never_adopted_or_mutated()
    {
        var database = await DatabaseAsync();
        var transport = new FakeTransport(ExchangeId.Okx,
        [
            Truth(TradeDirection.Short, 0.3748m, 0m) with
            {
                Symbol = "CRV-USDT-SWAP", Quantity = 233m, EntryPrice = 0.3727m
            }
        ]);
        var snapshot = new RuntimeSnapshot();
        var manager = new PositionManager(database, [transport], new ExchangeOperationLocks(), snapshot);

        await manager.RunOnceAsync(Options(), default);

        var external = Assert.Single(snapshot.ExternalPositions).Value;
        Assert.Equal("CRV-USDT-SWAP", external.Symbol);
        Assert.Equal(TradeDirection.Short, external.Direction);
        Assert.Equal(233m, external.Quantity);
        Assert.Empty(await database.LoadOpenManagedPositionsAsync(default));
        Assert.Equal(0, transport.StopCount);
        Assert.Equal(0, transport.CloseCount);
    }

    [Fact]
    public async Task Manual_close_all_is_persistent_reduce_only_and_deduplicated()
    {
        var database = await DatabaseAsync(); var position = Position(TradeDirection.Short);
        await database.UpsertPositionAsync(position, default);
        var transport = new FakeTransport(ExchangeId.Okx, [Truth(TradeDirection.Short, 100m, 101m)])
            { FlattenOnClose = true };
        var manager = Manager(database, transport);

        Assert.Equal(1, await manager.RequestCloseExchangeAsync(ExchangeId.Okx, "MANUAL_TEST", default));
        Assert.Equal(0, await manager.RequestCloseExchangeAsync(ExchangeId.Okx, "MANUAL_TEST", default));
        await manager.RunOnceAsync(Options(), default);

        Assert.Equal(1, transport.CloseCount);
        Assert.Equal(0, await database.CountUnresolvedRiskActionsAsync(default));
        Assert.Empty(await database.LoadOpenManagedPositionsAsync(default));
    }

    private PositionManager Manager(TradingDatabase database, params ILiveExecutionTransport[] transports) =>
        new(database, transports, new ExchangeOperationLocks(), new RuntimeSnapshot());

    private async Task<TradingDatabase> DatabaseAsync()
    {
        var database = new TradingDatabase(_directory); await database.InitializeAsync(default);
        await database.InsertSignalOnceAsync(Signal("signal-1"), default);
        await database.InsertSignalOnceAsync(Signal("signal-bybit"), default);
        return database;
    }

    private static CanonicalSignal Signal(string id) => new(id, "OKX", "UNI-USDT-SWAP", 1,
        TradeDirection.Short, 100, 99, 1, 0.01, 1, 25, 1, "TEST", DateTimeOffset.UtcNow);

    private static RuntimeOptions Options() => new()
    {
        TradingEnabled = true, MaxNetLossUsdt = 0.50m, ExecutionSlippageBufferBps = 2m,
        Exchanges = new(StringComparer.OrdinalIgnoreCase)
        {
            [ExchangeId.Okx.ToString()] = ExchangeMode.Active,
            [ExchangeId.Bybit.ToString()] = ExchangeMode.Active
        }
    };

    private static ManagedPosition Position(TradeDirection direction) => new(
        ExchangeId.Okx, "signal-1", direction == TradeDirection.Long ? "UNI-USDT-SWAP" : "UNI-USDT-SWAP",
        direction, 100m, 100m, 1m, 1m, 1m, 0.1m, 0.001m, 0m, 0m, 0m,
        direction == TradeDirection.Long ? 99m : 101m, direction == TradeDirection.Long ? 99.4m : 100.6m,
        direction == TradeDirection.Long ? 99m : 101m, 0.001m, DateTimeOffset.UtcNow);

    private static ExchangePositionTruth Truth(TradeDirection direction, decimal mark, decimal stop) => new(
        "UNI-USDT-SWAP", direction, 1m, 100m, mark, stop, stop > 0 ? "stop-1" : null, true, DateTimeOffset.UtcNow, 5);

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        if (Directory.Exists(_directory)) Directory.Delete(_directory, true);
    }

    private sealed class FakeTransport(ExchangeId id, IReadOnlyList<ExchangePositionTruth> positions) : ILiveExecutionTransport
    {
        public ExchangeId Id { get; } = id;
        public List<ExchangePositionTruth> Positions { get; } = positions.ToList();
        public bool FlattenOnClose { get; init; }
        public bool ApplyStop { get; init; }
        public bool AmbiguousStop { get; init; }
        public bool FailTruth { get; init; }
        public int StopCount { get; private set; }
        public int CloseCount { get; private set; }

        public Task<PreparedEntry> PrepareEntryAsync(CanonicalSignal signal, RuntimeOptions options, CancellationToken cancellationToken) =>
            throw new NotSupportedException();
        public Task<MutationReceipt> SubmitEntryAsync(PreparedEntry entry, CancellationToken cancellationToken) =>
            throw new NotSupportedException();

        public Task<ReconciliationTruth> ReconcileEntryAsync(string symbol, string clientOrderId, CancellationToken cancellationToken) =>
            Task.FromResult(new ReconciliationTruth(new(ExchangeOrderState.Filled, "order", clientOrderId, 1m, 100m, 0.1m, "FILLED"),
                Positions.SingleOrDefault(x => x.Symbol.Equals(symbol, StringComparison.OrdinalIgnoreCase))));

        public Task<MutationReceipt> ReplaceStopAsync(ExchangePositionTruth position, decimal stopPrice, string clientActionId,
            CancellationToken cancellationToken)
        {
            StopCount++;
            if (AmbiguousStop) throw new AmbiguousMutationException("STOP_HTTP_5XX");
            if (ApplyStop)
            {
                var index = Positions.FindIndex(x => x.Symbol == position.Symbol && x.Direction == position.Direction);
                Positions[index] = position with { StopPrice = stopPrice, StopOrderId = "stop-new" };
            }
            return Task.FromResult(new MutationReceipt(MutationDisposition.Accepted, null, "stop-new", "OK", DateTimeOffset.UtcNow));
        }

        public Task<MutationReceipt> CloseReduceOnlyAsync(ExchangePositionTruth position, string clientActionId,
            CancellationToken cancellationToken)
        {
            CloseCount++;
            if (FlattenOnClose) Positions.Clear();
            return Task.FromResult(new MutationReceipt(MutationDisposition.Accepted, "close-1", null, "OK", DateTimeOffset.UtcNow));
        }

        public Task<IReadOnlyList<ExchangePositionTruth>> GetOpenPositionsAsync(CancellationToken cancellationToken) =>
            FailTruth ? throw new HttpRequestException("truth failed") : Task.FromResult<IReadOnlyList<ExchangePositionTruth>>(Positions.ToArray());
    }
}
