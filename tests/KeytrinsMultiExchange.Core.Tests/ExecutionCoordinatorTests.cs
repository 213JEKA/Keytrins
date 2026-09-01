using KeytrinsMultiExchange.Core;
using Microsoft.Data.Sqlite;
using Xunit;

namespace KeytrinsMultiExchange.Core.Tests;

public sealed class ExecutionCoordinatorTests : IDisposable
{
    private readonly string _directory = Path.Combine(Path.GetTempPath(), "keytrins-tests-" + Guid.NewGuid().ToString("N"));

    [Fact]
    public async Task Intent_is_unique_per_exchange_and_signal()
    {
        var database = await DatabaseAsync(); var signal = Signal(); await database.InsertSignalOnceAsync(signal, default);
        Assert.True(await database.TryCreateExecutionIntentAsync(ExchangeId.Okx, signal, "client-1", default));
        Assert.False(await database.TryCreateExecutionIntentAsync(ExchangeId.Okx, signal, "client-2", default));
        Assert.Equal(1, await database.CountUnresolvedExecutionCommandsAsync(default));
    }

    [Fact]
    public async Task Fully_confirmed_fill_and_stop_finishes_entry_command()
    {
        var database = await DatabaseAsync(); var signal = Signal(); await database.InsertSignalOnceAsync(signal, default);
        var transport = new DeterministicTransport();
        var coordinator = new ExecutionCoordinator(database, [transport], new ExchangeOperationLocks());
        var result = await coordinator.RouteAsync(ExchangeId.Okx, signal, Options(), default);
        Assert.Equal(RouteResult.Filled, result.Result);
        Assert.Equal(1, transport.SubmitCount);
        Assert.Equal(0, transport.CloseCount);
        Assert.Equal(0, await database.CountUnresolvedExecutionCommandsAsync(default));
        var positions = await database.QueryAsync("managed_positions", 10, default);
        Assert.Single(positions);
        Assert.Equal("5.1", Convert.ToString(positions[0]["current_stop"], System.Globalization.CultureInfo.InvariantCulture));
    }

    [Fact]
    public async Task Ambiguous_submit_is_not_retried_and_remains_for_reconciliation()
    {
        var database = await DatabaseAsync(); var signal = Signal(); await database.InsertSignalOnceAsync(signal, default);
        var transport = new DeterministicTransport { Ambiguous = true };
        var coordinator = new ExecutionCoordinator(database, [transport], new ExchangeOperationLocks());
        var first = await coordinator.RouteAsync(ExchangeId.Okx, signal, Options(), default);
        var second = await coordinator.RouteAsync(ExchangeId.Okx, signal, Options(), default);
        Assert.Equal(RouteResult.Unknown, first.Result);
        Assert.Equal("SIGNAL_ALREADY_EXECUTED", second.Reason);
        Assert.Equal(1, transport.SubmitCount);
        Assert.Equal(1, await database.CountUnresolvedExecutionCommandsAsync(default));
    }

    [Fact]
    public async Task Deterministic_exchange_submit_error_is_terminal_rejection()
    {
        var database = await DatabaseAsync(); var signal = Signal(); await database.InsertSignalOnceAsync(signal, default);
        var transport = new DeterministicTransport { SubmitApiError = "TEST_400100" };
        var coordinator = new ExecutionCoordinator(database, [transport], new ExchangeOperationLocks());
        var result = await coordinator.RouteAsync(ExchangeId.Okx, signal, Options(), default);
        Assert.Equal(RouteResult.Rejected, result.Result);
        Assert.Equal("TEST_400100", result.Reason);
        Assert.Equal(1, transport.SubmitCount);
        Assert.Equal(0, await database.CountUnresolvedExecutionCommandsAsync(default));
    }

    [Fact]
    public async Task Private_precheck_exchange_error_is_local_rejection_not_coordinator_failure()
    {
        var database = await DatabaseAsync(); var signal = Signal(); await database.InsertSignalOnceAsync(signal, default);
        var transport = new DeterministicTransport { PrepareApiError = "TEST_PRECHECK_ERROR" };
        var coordinator = new ExecutionCoordinator(database, [transport], new ExchangeOperationLocks());
        var result = await coordinator.RouteAsync(ExchangeId.Okx, signal, Options(), default);
        Assert.Equal(RouteResult.Rejected, result.Result);
        Assert.Equal("TEST_PRECHECK_ERROR", result.Reason);
        Assert.Equal(0, transport.SubmitCount);
        Assert.Equal(0, await database.CountUnresolvedExecutionCommandsAsync(default));
    }

    [Fact]
    public async Task Post_submit_lookup_error_is_persisted_for_reconciliation_not_thrown()
    {
        var database = await DatabaseAsync(); var signal = Signal(); await database.InsertSignalOnceAsync(signal, default);
        var transport = new DeterministicTransport { ReconcileApiError = "TEST_LOOKUP_ERROR" };
        var coordinator = new ExecutionCoordinator(database, [transport], new ExchangeOperationLocks());
        var result = await coordinator.RouteAsync(ExchangeId.Okx, signal, Options(), default);
        Assert.Equal(RouteResult.Unknown, result.Result);
        Assert.StartsWith("RECONCILE_REQUIRED_", result.Reason);
        Assert.Equal(1, transport.SubmitCount);
        Assert.Equal(1, await database.CountUnresolvedExecutionCommandsAsync(default));
    }

    [Fact]
    public async Task Missing_initial_stop_triggers_one_reduce_only_close()
    {
        var database = await DatabaseAsync(); var signal = Signal(); await database.InsertSignalOnceAsync(signal, default);
        var transport = new DeterministicTransport { StopPrice = 0m };
        var coordinator = new ExecutionCoordinator(database, [transport], new ExchangeOperationLocks(),
            entryConfirmationAttempts: 1, entryConfirmationPollInterval: TimeSpan.Zero);
        var result = await coordinator.RouteAsync(ExchangeId.Okx, signal, Options(), default);
        Assert.Equal(RouteResult.Unknown, result.Result);
        Assert.Equal("INITIAL_STOP_MISSING_EMERGENCY_CLOSE", result.Reason);
        Assert.Equal(1, transport.CloseCount);
        Assert.Equal(1, await database.CountUnresolvedExecutionCommandsAsync(default));
    }

    [Fact]
    public async Task Delayed_attached_stop_is_observed_before_emergency_close()
    {
        var database = await DatabaseAsync(); var signal = Signal(); await database.InsertSignalOnceAsync(signal, default);
        var transport = new DeterministicTransport { StopVisibleAfterReconciliations = 3 };
        var coordinator = new ExecutionCoordinator(database, [transport], new ExchangeOperationLocks(),
            entryConfirmationAttempts: 3, entryConfirmationPollInterval: TimeSpan.Zero);

        var result = await coordinator.RouteAsync(ExchangeId.Okx, signal, Options(), default);

        Assert.Equal(RouteResult.Filled, result.Result);
        Assert.Equal(3, transport.ReconcileCount);
        Assert.Equal(0, transport.CloseCount);
        Assert.Equal(0, await database.CountUnresolvedExecutionCommandsAsync(default));
    }

    [Fact]
    public async Task Okx_rejection_does_not_suppress_parallel_follower_execution()
    {
        var database = await DatabaseAsync(); var signal = Signal(); await database.InsertSignalOnceAsync(signal, default);
        var okx = new DeterministicTransport(ExchangeId.Okx) { SubmitApiError = "MASTER_REJECTED" };
        var bybit = new DeterministicTransport(ExchangeId.Bybit);
        var kucoin = new DeterministicTransport(ExchangeId.KuCoinFutures);
        var coordinator = new ExecutionCoordinator(database, [okx, bybit, kucoin], new ExchangeOperationLocks());

        var results = await coordinator.RouteParallelAsync(signal, OptionsAllActive(), default);

        Assert.Equal(RouteResult.Rejected, results.Single(x => x.Exchange == ExchangeId.Okx).Result);
        Assert.Equal(RouteResult.Filled, results.Single(x => x.Exchange == ExchangeId.Bybit).Result);
        Assert.Equal(RouteResult.Filled, results.Single(x => x.Exchange == ExchangeId.KuCoinFutures).Result);
        Assert.Equal(1, okx.SubmitCount);
        Assert.Equal(1, bybit.SubmitCount);
        Assert.Equal(1, kucoin.SubmitCount);
    }

    [Fact]
    public async Task Followers_do_not_wait_for_okx_submit_to_complete()
    {
        var database = await DatabaseAsync(); var signal = Signal(); await database.InsertSignalOnceAsync(signal, default);
        var okx = new DeterministicTransport(ExchangeId.Okx) { BlockSubmit = true };
        var bybit = new DeterministicTransport(ExchangeId.Bybit);
        var kucoin = new DeterministicTransport(ExchangeId.KuCoinFutures);
        var coordinator = new ExecutionCoordinator(database, [okx, bybit, kucoin], new ExchangeOperationLocks());

        var routing = coordinator.RouteParallelAsync(signal, OptionsAllActive(), default);
        await okx.SubmitStarted.Task.WaitAsync(TimeSpan.FromSeconds(2));
        await bybit.SubmitStarted.Task.WaitAsync(TimeSpan.FromSeconds(2));
        await kucoin.SubmitStarted.Task.WaitAsync(TimeSpan.FromSeconds(2));

        Assert.Equal(1, bybit.SubmitCount);
        Assert.Equal(1, kucoin.SubmitCount);
        Assert.False(routing.IsCompleted);
        okx.ReleaseSubmit.TrySetResult();
        var results = await routing;
        Assert.All(results, result => Assert.Equal(RouteResult.Filled, result.Result));
    }

    [Fact]
    public async Task Confirmed_okx_entry_is_preserved_when_a_follower_rejects()
    {
        var database = await DatabaseAsync(); var signal = Signal(); await database.InsertSignalOnceAsync(signal, default);
        var okx = new DeterministicTransport(ExchangeId.Okx);
        var bybit = new DeterministicTransport(ExchangeId.Bybit) { SubmitApiError = "FOLLOWER_REJECTED" };
        var kucoin = new DeterministicTransport(ExchangeId.KuCoinFutures);
        var coordinator = new ExecutionCoordinator(database, [okx, bybit, kucoin], new ExchangeOperationLocks());

        var results = await coordinator.RouteParallelAsync(signal, OptionsAllActive(), default);

        Assert.Equal(RouteResult.Filled, results.Single(x => x.Exchange == ExchangeId.Okx).Result);
        Assert.Equal(RouteResult.Rejected, results.Single(x => x.Exchange == ExchangeId.Bybit).Result);
        Assert.Equal(RouteResult.Filled, results.Single(x => x.Exchange == ExchangeId.KuCoinFutures).Result);
        Assert.Equal(0, okx.CloseCount);
        Assert.Equal(1, okx.SubmitCount);
        Assert.Equal(1, bybit.SubmitCount);
        Assert.Equal(1, kucoin.SubmitCount);
    }

    [Fact]
    public async Task Recovery_before_submit_marks_command_terminal_without_mutation()
    {
        var database = await DatabaseAsync(); var signal = Signal(); await database.InsertSignalOnceAsync(signal, default);
        var transport = new DeterministicTransport();
        Assert.True(await database.TryCreateExecutionIntentAsync(ExchangeId.Okx, signal, "recovery-client", default));
        var coordinator = new ExecutionCoordinator(database, [transport], new ExchangeOperationLocks(), TimeSpan.Zero);
        await coordinator.RecoverAsync(default);
        Assert.Equal(0, transport.SubmitCount);
        Assert.Equal(0, await database.CountUnresolvedExecutionCommandsAsync(default));
    }

    [Fact]
    public async Task Recovery_does_not_reclassify_fresh_in_flight_command_from_current_process()
    {
        var database = await DatabaseAsync(); var signal = Signal(); await database.InsertSignalOnceAsync(signal, default);
        var transport = new DeterministicTransport { BlockSubmit = true };
        var coordinator = new ExecutionCoordinator(database, [transport], new ExchangeOperationLocks());
        var route = coordinator.RouteAsync(ExchangeId.Okx, signal, Options(), default);
        await transport.SubmitStarted.Task.WaitAsync(TimeSpan.FromSeconds(2));

        await coordinator.RecoverAsync(default);
        Assert.Equal(1, await database.CountUnresolvedExecutionCommandsAsync(default));

        transport.ReleaseSubmit.TrySetResult();
        var result = await route;
        Assert.Equal(RouteResult.Filled, result.Result);
        Assert.Equal(0, await database.CountUnresolvedExecutionCommandsAsync(default));
    }

    [Fact]
    public async Task Recovery_after_fill_restores_managed_position_from_persisted_plan()
    {
        var database = await DatabaseAsync(); var signal = Signal(); await database.InsertSignalOnceAsync(signal, default);
        var transport = new DeterministicTransport(); var entry = await transport.PrepareEntryAsync(signal, Options(), default);
        Assert.True(await database.TryCreateExecutionIntentAsync(ExchangeId.Okx, signal, entry.ClientOrderId, default));
        await database.SaveExecutionPlanAsync(entry, default);
        await database.TransitionExecutionAsync(ExchangeId.Okx, signal.SignalId, ExecutionCommandState.Intent,
            ExecutionCommandState.Prechecked, "TEST", null, null, null, default);
        await database.TransitionExecutionAsync(ExchangeId.Okx, signal.SignalId, ExecutionCommandState.Prechecked,
            ExecutionCommandState.Submitting, "TEST", null, null, null, default);
        await database.TransitionExecutionAsync(ExchangeId.Okx, signal.SignalId, ExecutionCommandState.Submitting,
            ExecutionCommandState.SubmitUnknown, "TEST_CRASH_WINDOW", null, null, null, default);
        var coordinator = new ExecutionCoordinator(database, [transport], new ExchangeOperationLocks());
        await coordinator.RecoverAsync(default);
        Assert.Equal(0, transport.SubmitCount);
        Assert.Equal(0, await database.CountUnresolvedExecutionCommandsAsync(default));
        Assert.Single(await database.LoadOpenManagedPositionsAsync(default));
    }

    [Fact]
    public async Task Recovery_repairs_false_restart_rejection_and_uses_adapter_symbol_from_plan()
    {
        var database = await DatabaseAsync(); var signal = Signal(); await database.InsertSignalOnceAsync(signal, default);
        var transport = new DeterministicTransport { PreparedSymbol = "UNIUSDT" };
        var entry = await transport.PrepareEntryAsync(signal, Options(), default);
        Assert.True(await database.TryCreateExecutionIntentAsync(ExchangeId.Okx, signal, entry.ClientOrderId, default));
        await database.SaveExecutionPlanAsync(entry, default);
        await database.TransitionExecutionAsync(ExchangeId.Okx, signal.SignalId, ExecutionCommandState.Intent,
            ExecutionCommandState.Prechecked, "TEST", null, null, null, default);
        await database.TransitionExecutionAsync(ExchangeId.Okx, signal.SignalId, ExecutionCommandState.Prechecked,
            ExecutionCommandState.Submitting, "TEST", null, null, null, default);
        var command = Assert.Single(await database.LoadUnresolvedExecutionCommandsAsync(default));
        await database.ForceExecutionStateAfterReconciliationAsync(command, ExecutionCommandState.Rejected,
            "RECOVERY_CONFIRMED_NOT_SUBMITTED", "order-1", null, "SERVICE_RESTART_BEFORE_SUBMIT", default);

        var coordinator = new ExecutionCoordinator(database, [transport], new ExchangeOperationLocks(), TimeSpan.Zero);
        await coordinator.RecoverAsync(default);

        Assert.Equal("UNIUSDT", transport.LastReconcileSymbol);
        Assert.Single(await database.LoadOpenManagedPositionsAsync(default));
        Assert.Empty(await database.LoadExecutionRecoveryCandidatesAsync(default));
    }

    [Fact]
    public async Task Old_submit_with_lookup_api_error_and_exchange_flat_becomes_terminal_without_mutation()
    {
        var database = await DatabaseAsync(); var signal = Signal(); await database.InsertSignalOnceAsync(signal, default);
        var transport = new DeterministicTransport { ReconcileApiError = "TEST_LOOKUP_ERROR", ExchangePositions = [] };
        var entry = await transport.PrepareEntryAsync(signal, Options(), default);
        Assert.True(await database.TryCreateExecutionIntentAsync(ExchangeId.Okx, signal, entry.ClientOrderId, default));
        await database.SaveExecutionPlanAsync(entry, default);
        await database.TransitionExecutionAsync(ExchangeId.Okx, signal.SignalId, ExecutionCommandState.Intent,
            ExecutionCommandState.Prechecked, "TEST", null, null, null, default);
        await database.TransitionExecutionAsync(ExchangeId.Okx, signal.SignalId, ExecutionCommandState.Prechecked,
            ExecutionCommandState.Submitting, "TEST", null, null, null, default);

        var coordinator = new ExecutionCoordinator(database, [transport], new ExchangeOperationLocks(),
            TimeSpan.Zero, TimeSpan.Zero);
        await coordinator.RecoverAsync(default);

        Assert.Equal(0, transport.SubmitCount);
        Assert.Equal(0, await database.CountUnresolvedExecutionCommandsAsync(default));
    }

    [Fact]
    public async Task Recovery_of_position_without_stop_persists_one_emergency_close()
    {
        var database = await DatabaseAsync(); var signal = Signal(); await database.InsertSignalOnceAsync(signal, default);
        var transport = new DeterministicTransport { StopPrice = 0m };
        var entry = await transport.PrepareEntryAsync(signal, Options(), default);
        Assert.True(await database.TryCreateExecutionIntentAsync(ExchangeId.Okx, signal, entry.ClientOrderId, default));
        await database.SaveExecutionPlanAsync(entry, default);
        await database.TransitionExecutionAsync(ExchangeId.Okx, signal.SignalId, ExecutionCommandState.Intent,
            ExecutionCommandState.Prechecked, "TEST", null, null, null, default);
        await database.TransitionExecutionAsync(ExchangeId.Okx, signal.SignalId, ExecutionCommandState.Prechecked,
            ExecutionCommandState.Submitting, "TEST", null, null, null, default);
        await database.TransitionExecutionAsync(ExchangeId.Okx, signal.SignalId, ExecutionCommandState.Submitting,
            ExecutionCommandState.SubmitUnknown, "TEST_CRASH_WINDOW", null, null, null, default);
        var coordinator = new ExecutionCoordinator(database, [transport], new ExchangeOperationLocks());
        await coordinator.RecoverAsync(default);
        await coordinator.RecoverAsync(default);
        Assert.Equal(1, transport.CloseCount);
        Assert.Equal(1, await database.CountUnresolvedRiskActionsAsync(default));
    }

    [Fact]
    public void Client_ids_are_deterministic_and_bounded()
    {
        var first = ExecutionIds.Entry(ExchangeId.KuCoinFutures, new string('x', 200));
        var second = ExecutionIds.Entry(ExchangeId.KuCoinFutures, new string('x', 200));
        Assert.Equal(first, second);
        Assert.True(first.Length <= 36);
        Assert.NotEqual(first, ExecutionIds.Entry(ExchangeId.Bybit, new string('x', 200)));
    }

    private async Task<TradingDatabase> DatabaseAsync()
    {
        var database = new TradingDatabase(_directory); await database.InitializeAsync(default); return database;
    }

    private static CanonicalSignal Signal() => new("signal-001", "OKX", "UNI-USDT-SWAP", 1,
        TradeDirection.Long, 5.2, 5.1, 0.1, 0.019230769, 0.08, 25, 1,
        "H1_TREND_M15_PULLBACK_CONFIRM", DateTimeOffset.UtcNow);

    private static RuntimeOptions Options() => new()
    {
        TradingEnabled = true,
        Exchanges = new(StringComparer.OrdinalIgnoreCase) { [ExchangeId.Okx.ToString()] = ExchangeMode.Active }
    };

    private static RuntimeOptions OptionsAllActive() => new()
    {
        TradingEnabled = true,
        Exchanges = new(StringComparer.OrdinalIgnoreCase)
        {
            [ExchangeId.Okx.ToString()] = ExchangeMode.Active,
            [ExchangeId.Bybit.ToString()] = ExchangeMode.Active,
            [ExchangeId.KuCoinFutures.ToString()] = ExchangeMode.Active
        }
    };

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        if (Directory.Exists(_directory)) Directory.Delete(_directory, true);
    }

    private sealed class DeterministicTransport(ExchangeId id = ExchangeId.Okx) : ILiveExecutionTransport
    {
        public ExchangeId Id => id;
        public bool Ambiguous { get; init; }
        public bool BlockSubmit { get; init; }
        public string? SubmitApiError { get; init; }
        public string? PrepareApiError { get; init; }
        public string? ReconcileApiError { get; init; }
        public IReadOnlyList<ExchangePositionTruth>? ExchangePositions { get; init; }
        public string? PreparedSymbol { get; init; }
        public decimal StopPrice { get; init; } = 5.1m;
        public int StopVisibleAfterReconciliations { get; init; } = 1;
        public int SubmitCount { get; private set; }
        public int CloseCount { get; private set; }
        public int ReconcileCount { get; private set; }
        public TaskCompletionSource SubmitStarted { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public TaskCompletionSource ReleaseSubmit { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public string? LastReconcileSymbol { get; private set; }

        public Task<PreparedEntry> PrepareEntryAsync(CanonicalSignal signal, RuntimeOptions options, CancellationToken cancellationToken) =>
            PrepareApiError is not null
                ? Task.FromException<PreparedEntry>(new ExchangeApiException(PrepareApiError))
                : Task.FromResult(new PreparedEntry(Id, signal.SignalId, ExecutionIds.Entry(Id, signal.SignalId), PreparedSymbol ?? signal.Symbol,
                    signal.ActualDirection, options.Leverage, 10m, 1m, 5.2m, 5.1m, 5.1m, 5.0m, 0.01m, 0.001m, 0.01m, 0.1m));

        public async Task<MutationReceipt> SubmitEntryAsync(PreparedEntry entry, CancellationToken cancellationToken)
        {
            SubmitCount++;
            SubmitStarted.TrySetResult();
            if (BlockSubmit) await ReleaseSubmit.Task.WaitAsync(cancellationToken);
            if (Ambiguous) throw new AmbiguousMutationException("HTTP_5XX");
            if (SubmitApiError is not null) throw new ExchangeApiException(SubmitApiError);
            return new MutationReceipt(MutationDisposition.Accepted, "order-1", "stop-1", "OK", DateTimeOffset.UtcNow);
        }

        public Task<ReconciliationTruth> ReconcileEntryAsync(string symbol, string clientOrderId, CancellationToken cancellationToken)
        {
            LastReconcileSymbol = symbol;
            ReconcileCount++;
            if (ReconcileApiError is not null) throw new ExchangeApiException(ReconcileApiError);
            var visibleStop = ReconcileCount >= StopVisibleAfterReconciliations ? StopPrice : 0m;
            return Task.FromResult(new ReconciliationTruth(
                new(ExchangeOrderState.Filled, "order-1", clientOrderId, 10m, 5.2m, 0.052m, "FILLED"),
                new(symbol, TradeDirection.Short, 10m, 5.2m, 5.19m, visibleStop, visibleStop > 0 ? "stop-1" : null,
                    true, DateTimeOffset.UtcNow)));
        }

        public Task<MutationReceipt> ReplaceStopAsync(ExchangePositionTruth position, decimal stopPrice, string clientActionId,
            CancellationToken cancellationToken) => throw new NotSupportedException();

        public Task<MutationReceipt> CloseReduceOnlyAsync(ExchangePositionTruth position, string clientActionId,
            CancellationToken cancellationToken)
        {
            CloseCount++;
            return Task.FromResult(new MutationReceipt(MutationDisposition.Accepted, "close-1", null, "ACCEPTED", DateTimeOffset.UtcNow));
        }

        public Task<IReadOnlyList<ExchangePositionTruth>> GetOpenPositionsAsync(CancellationToken cancellationToken) =>
            Task.FromResult(ExchangePositions ?? (IReadOnlyList<ExchangePositionTruth>)[]);
    }
}
