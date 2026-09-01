using System.Collections.Concurrent;
using KeytrinsMultiExchange.Core;
using Microsoft.Extensions.Options;

namespace KeytrinsMultiExchange.Service;

public sealed class TradingRuntimeWorker(
    ILogger<TradingRuntimeWorker> logger,
    RuntimeSettingsStore settings,
    RuntimeSnapshot state,
    TradingDatabase database,
    OkxMarketDataClient okx,
    IReadOnlyList<IExchangeAdapter> adapters,
    ExecutionCoordinator execution,
    ExternalWriterGuard writerGuard) : BackgroundService
{
    private IReadOnlyList<OkxUniverseInstrument> _universe = [];
    private DateTimeOffset _universeRefreshAt = DateTimeOffset.MinValue;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await database.InitializeAsync(stoppingToken);
        foreach (var attempt in await database.GetLatestRouteAttemptsAsync(stoppingToken))
            state.LastRouteAttempts[attempt.Exchange] = attempt;
        await database.AppendLogAsync("RUNTIME", "SERVICE_START; master=OKX; mutation gate=" +
            (settings.Current.TradingEnabled ? "CONFIGURED" : "DISARMED"), null, null, stoppingToken);
        await RunPreflightAsync(stoppingToken);
        await RefreshWriterExclusivityAsync(stoppingToken);
        await execution.RecoverAsync(stoppingToken);
        await ScanAsync(stoppingToken);
        while (!stoppingToken.IsCancellationRequested)
        {
            try
            {
                var next = NextScanAt(DateTimeOffset.UtcNow);
                state.MasterDetail = $"next scan {next:O}";
                await Task.Delay(next - DateTimeOffset.UtcNow, stoppingToken);
                await ScanAsync(stoppingToken);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception exception)
            {
                state.MasterHealth = "ERROR";
                state.MasterDetail = exception.GetType().Name;
                logger.LogError(exception, "OKX master scan failed");
                await database.AppendLogAsync("MASTER_ERROR", exception.GetType().Name + ": " + exception.Message, "Okx", null, stoppingToken);
                await Task.Delay(TimeSpan.FromSeconds(30), stoppingToken);
            }
        }
    }

    private async Task RunPreflightAsync(CancellationToken cancellationToken)
    {
        try
        {
            var (_, delta) = await okx.GetServerTimeAsync(cancellationToken);
            if (Math.Abs(delta.TotalSeconds) > 5) throw new InvalidOperationException($"CLOCK_SKEW_{delta.TotalMilliseconds:F0}MS");
            state.MasterHealth = "ONLINE";
            state.MasterDetail = $"OKX public clock delta {delta.TotalMilliseconds:F0} ms";
        }
        catch (Exception exception) { state.MasterHealth = "ERROR"; state.MasterDetail = exception.Message; }

        await Parallel.ForEachAsync(adapters, cancellationToken, async (adapter, ct) =>
        {
            var configured = settings.Current;
            var requested = configured.Exchanges.TryGetValue(adapter.Id.ToString(), out var mode) ? mode : ExchangeMode.NotConfigured;
            if (!configured.TradingEnabled && requested == ExchangeMode.Active) requested = ExchangeMode.Paused;
            var snapshot = await adapter.ReadOnlyPreflightAsync(requested, ct);
            state.Exchanges[adapter.Id] = snapshot;
            await database.AppendLogAsync("PREFLIGHT", snapshot.Detail, adapter.Id.ToString(), null, ct);
        });
    }

    private async Task ScanAsync(CancellationToken cancellationToken)
    {
        await RefreshWriterExclusivityAsync(cancellationToken);
        var now = DateTimeOffset.UtcNow;
        var configured = settings.Current;
        if (now >= _universeRefreshAt || _universe.Count != configured.UniverseSize)
        {
            _universe = await okx.BuildUniverseAsync(configured.UniverseSize, configured.MinTurnoverUsdt, cancellationToken);
            _universeRefreshAt = now.AddHours(1);
            state.UniverseCount = _universe.Count;
            await database.AppendLogAsync("UNIVERSE", $"OKX top={_universe.Count}", "Okx", null, cancellationToken);
        }
        state.LastScanAt = now;
        state.MasterHealth = "ONLINE";
        using var semaphore = new SemaphoreSlim(5);
        var signals = new ConcurrentDictionary<int, CanonicalSignal>();
        var tasks = _universe.Select(async (instrument, index) =>
        {
            await semaphore.WaitAsync(cancellationToken);
            try
            {
                var h1Task = okx.GetClosedCandlesAsync(instrument.InstrumentId, "1H", 300, cancellationToken);
                var m15Task = okx.GetClosedCandlesAsync(instrument.InstrumentId, "15m", 300, cancellationToken);
                await Task.WhenAll(h1Task, m15Task);
                var decision = new OkxStrategyCore().BuildSignal(instrument.InstrumentId, h1Task.Result, m15Task.Result);
                await database.AppendLogAsync("STRATEGY_DECISION", $"{instrument.InstrumentId}:{decision.Reason}", "Okx", decision.Signal?.SignalId, cancellationToken);
                if (decision.Signal is not null) signals[index] = decision.Signal;
            }
            catch (Exception exception)
            {
                await database.AppendLogAsync("SYMBOL_SCAN_ERROR", $"{instrument.InstrumentId}:{exception.GetType().Name}", "Okx", null, cancellationToken);
            }
            finally { semaphore.Release(); }
        });
        await Task.WhenAll(tasks);
        // Preserve every valid StrategyCore signal, but process signal groups serially. OKX must fill with its
        // exchange-side stop confirmed before the active followers are routed concurrently.
        foreach (var signal in signals.OrderBy(x => x.Key).Select(x => x.Value))
            await PublishAsync(signal, cancellationToken);
        await RunPreflightAsync(cancellationToken);
    }

    private async Task RefreshWriterExclusivityAsync(CancellationToken cancellationToken)
    {
        try
        {
            var result = await writerGuard.CheckAsync(cancellationToken);
            state.WriterExclusivity = result.IsExclusive ? "EXCLUSIVE" :
                result.LatestForeignEntryAt is null ? "QUIET_WINDOW_NOT_EXCLUSIVE" : "FOREIGN_WRITER_ACTIVE";
            state.WriterExclusivityDetail = result.Detail;
            if (result.IsExclusive) return;
            foreach (var exchange in new[] { ExchangeId.Okx, ExchangeId.Bybit, ExchangeId.KuCoinFutures })
                if (settings.Current.Exchanges.TryGetValue(exchange.ToString(), out var mode) && mode == ExchangeMode.Active)
                    settings.SetExchangeMode(exchange, ExchangeMode.Paused);
            await database.AppendLogAsync("ADMISSION_AUTO_PAUSE", result.Detail, "Okx", null, cancellationToken);
        }
        catch (Exception exception)
        {
            state.WriterExclusivity = "CHECK_FAILED";
            state.WriterExclusivityDetail = exception.GetType().Name + ":" + exception.Message;
            foreach (var exchange in new[] { ExchangeId.Okx, ExchangeId.Bybit, ExchangeId.KuCoinFutures })
                if (settings.Current.Exchanges.TryGetValue(exchange.ToString(), out var mode) && mode == ExchangeMode.Active)
                    settings.SetExchangeMode(exchange, ExchangeMode.Paused);
            await database.AppendLogAsync("ADMISSION_AUTO_PAUSE", state.WriterExclusivityDetail, "Okx", null, cancellationToken);
        }
    }

    private async Task PublishAsync(CanonicalSignal signal, CancellationToken cancellationToken)
    {
        var age = DateTimeOffset.UtcNow - DateTimeOffset.FromUnixTimeMilliseconds(signal.SignalTimeMs);
        var configured = settings.Current;
        if (age > TimeSpan.FromSeconds(configured.SignalStaleSeconds))
        {
            await database.AppendLogAsync("SIGNAL_SKIP", $"STALE_SIGNAL age={age.TotalSeconds:F1}s", "Okx", signal.SignalId, cancellationToken);
            return;
        }
        if (!await database.InsertSignalOnceAsync(signal, cancellationToken)) return;
        state.LastSignalId = signal.SignalId; state.LastSignalAt = DateTimeOffset.UtcNow;
        var activeCycles = await database.CountActiveSignalCyclesAsync(cancellationToken);
        if (activeCycles >= configured.MaxConcurrentSignals)
        {
            foreach (var adapter in adapters)
            {
                var attempt = new RouteAttempt(adapter.Id, signal.SignalId, DateTimeOffset.UtcNow, null, null,
                    RouteResult.Skipped, $"MAX_CONCURRENT_SIGNALS_{configured.MaxConcurrentSignals}");
                state.LastRouteAttempts[attempt.Exchange] = attempt;
                await database.InsertRouteAttemptAsync(attempt, cancellationToken);
                await database.AppendLogAsync("FAN_OUT", $"Skipped:{attempt.Reason}", adapter.Id.ToString(),
                    signal.SignalId, cancellationToken);
            }
            return;
        }
        var attempts = await execution.RouteOkxLeaderAsync(signal, configured, cancellationToken);
        foreach (var attempt in attempts)
        {
            state.LastRouteAttempts[attempt.Exchange] = attempt;
            await database.InsertRouteAttemptAsync(attempt, cancellationToken);
            await database.AppendLogAsync("FAN_OUT", $"{attempt.Result}:{attempt.Reason}", attempt.Exchange.ToString(), signal.SignalId, cancellationToken);
        }
    }

    private static DateTimeOffset NextScanAt(DateTimeOffset now)
    {
        var minute = (now.Minute / 15 + 1) * 15;
        var next = new DateTimeOffset(now.Year, now.Month, now.Day, now.Hour, 0, 8, TimeSpan.Zero).AddMinutes(minute);
        return next <= now ? next.AddMinutes(15) : next;
    }
}
