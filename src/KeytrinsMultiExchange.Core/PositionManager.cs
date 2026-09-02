namespace KeytrinsMultiExchange.Core;

public sealed class PositionManager
{
    private static readonly TimeSpan MutationVisibilityGrace = TimeSpan.FromSeconds(5);
    private readonly TradingDatabase _database;
    private readonly IReadOnlyDictionary<ExchangeId, ILiveExecutionTransport> _transports;
    private readonly ExchangeOperationLocks _locks;
    private readonly RuntimeSnapshot _snapshot;
    private readonly HashSet<string> _reportedExternal = new(StringComparer.OrdinalIgnoreCase);

    public PositionManager(TradingDatabase database, IEnumerable<ILiveExecutionTransport> transports,
        ExchangeOperationLocks locks, RuntimeSnapshot snapshot)
    {
        _database = database;
        _transports = transports.ToDictionary(x => x.Id);
        _locks = locks;
        _snapshot = snapshot;
    }

    public async Task RunOnceAsync(RuntimeOptions options, CancellationToken cancellationToken)
    {
        var positions = await _database.LoadOpenManagedPositionsAsync(cancellationToken);
        foreach (var transport in _transports.Values.OrderBy(x => x.Id))
        {
            var group = positions.Where(position => position.Exchange == transport.Id).ToArray();

            var gate = _locks.For(transport.Id);
            await gate.WaitAsync(cancellationToken);
            try
            {
                var truths = await transport.GetOpenPositionsAsync(cancellationToken);
                var externalTruths = truths.Where(x => group.All(p => FindTruth(p, [x]) is null)).ToArray();
                foreach (var external in externalTruths)
                {
                    var externalKey = $"{transport.Id}:{external.Symbol}:{external.Direction}";
                    if (_reportedExternal.Add(externalKey))
                        await _database.AppendLogAsync("EXTERNAL_POSITION_NOT_ADOPTED",
                            $"{external.Symbol}:{external.Direction}:qty={external.Quantity}", transport.Id.ToString(), null,
                            cancellationToken);
                }
                SyncExternalSnapshot(transport.Id, externalTruths);
                await ReconcilePendingAsync(transport, group, truths, cancellationToken);

                foreach (var position in group)
                {
                    var truth = FindTruth(position, truths);
                    if (truth is null)
                    {
                        await _database.MarkPositionClosedAsync(position.Exchange, position.SignalId,
                            "EXCHANGE_FLAT_CONFIRMED", cancellationToken);
                        _snapshot.Positions.TryRemove(PositionKey(position.Exchange, position.SignalId), out _);
                        continue;
                    }

                    if (!truth.IsOneWay)
                    {
                        await _database.AppendLogAsync("POSITION_MANAGER_BLOCK", "HEDGE_MODE_NOT_ALLOWED",
                            position.Exchange.ToString(), position.SignalId, cancellationToken);
                        continue;
                    }

                    var updated = Calculate(position, truth, options);
                    await _database.UpsertPositionAsync(updated, cancellationToken);
                    _snapshot.Positions[PositionKey(updated.Exchange, updated.SignalId)] = updated;

                    if (await _database.HasUnresolvedRiskActionAsync(position.Exchange, position.SignalId, cancellationToken))
                        continue;

                    var desiredStop = DesiredStop(updated, options.ExecutionSlippageBufferBps);
                    var crossed = IsCrossed(updated.Direction, truth.MarkPrice, desiredStop);
                    var maximumLossBreached = CurrentNet(updated, truth.MarkPrice) <= -Math.Abs(options.MaxNetLossUsdt);
                    if (crossed || maximumLossBreached)
                    {
                        await SubmitCloseAsync(transport, truth, updated,
                            crossed ? "PROTECTED_STOP_CATCHUP" : "MAX_NET_LOSS", cancellationToken);
                        continue;
                    }

                    if (truth.StopPrice <= 0m || IsLessProtective(updated.Direction, truth.StopPrice, desiredStop))
                        await SubmitStopAsync(transport, truth, updated, desiredStop, cancellationToken);
                }
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { throw; }
            catch (Exception exception)
            {
                await _database.AppendLogAsync("POSITION_MANAGER_ERROR",
                    exception.GetType().Name + ":" + exception.Message, transport.Id.ToString(), null, cancellationToken);
            }
            finally { gate.Release(); }
        }

    }

    private void SyncExternalSnapshot(ExchangeId exchange, IEnumerable<ExchangePositionTruth> externalTruths)
    {
        var prefix = exchange + ":";
        var current = externalTruths.ToDictionary(truth => ExternalKey(exchange, truth), StringComparer.OrdinalIgnoreCase);
        foreach (var stale in _snapshot.ExternalPositions.Keys.Where(key =>
                     key.StartsWith(prefix, StringComparison.OrdinalIgnoreCase) && !current.ContainsKey(key)).ToArray())
            _snapshot.ExternalPositions.TryRemove(stale, out _);
        foreach (var item in current) _snapshot.ExternalPositions[item.Key] = item.Value;
    }

    private static string ExternalKey(ExchangeId exchange, ExchangePositionTruth truth) =>
        $"{exchange}:{truth.Symbol}:{truth.Direction}";

    public async Task<int> RequestCloseExchangeAsync(ExchangeId exchange, string reason,
        CancellationToken cancellationToken)
    {
        if (!_transports.TryGetValue(exchange, out var transport)) return 0;
        var managed = (await _database.LoadOpenManagedPositionsAsync(cancellationToken))
            .Where(x => x.Exchange == exchange).ToArray();
        if (managed.Length == 0) return 0;

        var gate = _locks.For(exchange);
        await gate.WaitAsync(cancellationToken);
        try
        {
            var truths = await transport.GetOpenPositionsAsync(cancellationToken);
            var requested = 0;
            foreach (var position in managed)
            {
                var truth = FindTruth(position, truths);
                if (truth is null)
                {
                    await _database.MarkPositionClosedAsync(position.Exchange, position.SignalId,
                        reason + "_ALREADY_FLAT", cancellationToken);
                    _snapshot.Positions.TryRemove(PositionKey(position.Exchange, position.SignalId), out _);
                    continue;
                }
                if (await _database.HasUnresolvedRiskActionAsync(position.Exchange, position.SignalId, cancellationToken))
                    continue;
                if (await SubmitCloseAsync(transport, truth, position, reason, cancellationToken)) requested++;
            }
            return requested;
        }
        finally { gate.Release(); }
    }

    private async Task ReconcilePendingAsync(ILiveExecutionTransport transport, IReadOnlyList<ManagedPosition> managed,
        IReadOnlyList<ExchangePositionTruth> truths, CancellationToken cancellationToken)
    {
        var pending = (await _database.LoadUnresolvedRiskActionsAsync(cancellationToken))
            .Where(x => x.Exchange == transport.Id).ToArray();
        foreach (var action in pending)
        {
            var position = managed.SingleOrDefault(x => x.SignalId == action.SignalId);
            if (position is null) continue;
            var truth = FindTruth(position, truths);
            if (truth is null)
            {
                await _database.CompleteRiskActionAsync(action.ActionKey, "CONFIRMED", action.ExchangeOrderId,
                    "EXCHANGE_FLAT_CONFIRMED", cancellationToken);
                await _database.MarkPositionClosedAsync(position.Exchange, position.SignalId,
                    "RISK_ACTION_EXCHANGE_FLAT_CONFIRMED", cancellationToken);
                _snapshot.Positions.TryRemove(PositionKey(position.Exchange, position.SignalId), out _);
                continue;
            }

            if (action.Kind == "STOP")
            {
                if (action.RequestedStop is { } requested && StopSatisfies(position.Direction, truth.StopPrice, requested))
                {
                    await _database.CompleteRiskActionAsync(action.ActionKey, "CONFIRMED", truth.StopOrderId,
                        null, cancellationToken);
                    await _database.UpsertPositionAsync(position with
                    {
                        MarkPrice = truth.MarkPrice,
                        RemainingQuantity = truth.Quantity,
                        CurrentStop = truth.StopPrice
                    }, cancellationToken);
                    await _database.FinalizeExecutionAfterRiskRecoveryAsync(position.Exchange, position.SignalId,
                        "PROTECTIVE_STOP_CONFIRMED", cancellationToken);
                }
                else if (DateTimeOffset.UtcNow - action.UpdatedAt >= MutationVisibilityGrace)
                {
                    await _database.CompleteRiskActionAsync(action.ActionKey, "REJECTED", action.ExchangeOrderId,
                        "RECONCILED_STOP_NOT_APPLIED", cancellationToken);
                }
                continue;
            }

            var order = await transport.ReconcileEntryAsync(position.Symbol, action.ActionKey, cancellationToken);
            if (order.Order.State is ExchangeOrderState.Filled)
            {
                await _database.CompleteRiskActionAsync(action.ActionKey, "CONFIRMED", order.Order.OrderId,
                    "CLOSE_FILL_CONFIRMED_POSITION_REMAINS", cancellationToken);
            }
            else if (order.Order.State is ExchangeOrderState.Rejected or ExchangeOrderState.Cancelled ||
                     (order.Order.State is ExchangeOrderState.Missing && DateTimeOffset.UtcNow - action.UpdatedAt >= MutationVisibilityGrace))
            {
                await _database.CompleteRiskActionAsync(action.ActionKey, "REJECTED", order.Order.OrderId,
                    "RECONCILED_CLOSE_NOT_ACTIVE", cancellationToken);
            }
            else
            {
                await _database.CompleteRiskActionAsync(action.ActionKey, "ACCEPTED", order.Order.OrderId,
                    order.Order.Detail, cancellationToken);
            }
        }
    }

    private async Task SubmitStopAsync(ILiveExecutionTransport transport, ExchangePositionTruth truth,
        ManagedPosition position, decimal desiredStop, CancellationToken cancellationToken)
    {
        var revision = await _database.CountRiskActionsAsync(position.Exchange, position.SignalId, cancellationToken) + 1;
        var key = ExecutionIds.Stop(position.Exchange, position.SignalId, revision);
        if (!await _database.TryBeginRiskActionAsync(position.Exchange, position.SignalId, key, "STOP", desiredStop,
                cancellationToken)) return;
        try
        {
            var receipt = await transport.ReplaceStopAsync(truth, desiredStop, key, cancellationToken);
            var state = receipt.Disposition switch
            {
                MutationDisposition.Accepted => "ACCEPTED",
                MutationDisposition.Rejected => "REJECTED",
                _ => "UNKNOWN"
            };
            await _database.CompleteRiskActionAsync(key, state, receipt.StopOrderId ?? receipt.OrderId,
                receipt.Reason, cancellationToken);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { throw; }
        catch (Exception exception) when (exception is AmbiguousMutationException or HttpRequestException or TaskCanceledException)
        {
            await _database.CompleteRiskActionAsync(key, "UNKNOWN", null,
                exception.GetType().Name, cancellationToken);
        }
        catch (Exception exception)
        {
            await _database.CompleteRiskActionAsync(key, "REJECTED", null,
                exception.GetType().Name + ":" + exception.Message, cancellationToken);
        }
    }

    private async Task<bool> SubmitCloseAsync(ILiveExecutionTransport transport, ExchangePositionTruth truth,
        ManagedPosition position, string reason, CancellationToken cancellationToken)
    {
        var revision = await _database.CountRiskActionsAsync(position.Exchange, position.SignalId, cancellationToken) + 1;
        var key = ExecutionIds.Close(position.Exchange, position.SignalId, revision);
        if (!await _database.TryBeginRiskActionAsync(position.Exchange, position.SignalId, key, "CLOSE", null,
                cancellationToken)) return false;
        try
        {
            var receipt = await transport.CloseReduceOnlyAsync(truth, key, cancellationToken);
            var state = receipt.Disposition switch
            {
                MutationDisposition.Accepted => "ACCEPTED",
                MutationDisposition.Rejected => "REJECTED",
                _ => "UNKNOWN"
            };
            await _database.CompleteRiskActionAsync(key, state, receipt.OrderId, reason + ":" + receipt.Reason,
                cancellationToken);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { throw; }
        catch (Exception exception) when (exception is AmbiguousMutationException or HttpRequestException or TaskCanceledException)
        {
            await _database.CompleteRiskActionAsync(key, "UNKNOWN", null,
                reason + ":" + exception.GetType().Name, cancellationToken);
        }
        catch (Exception exception)
        {
            await _database.CompleteRiskActionAsync(key, "REJECTED", null,
                reason + ":" + exception.GetType().Name + ":" + exception.Message, cancellationToken);
        }
        return true;
    }

    public static ManagedPosition Calculate(ManagedPosition position, ExchangePositionTruth truth, RuntimeOptions options)
    {
        var remaining = truth.Quantity;
        var feeFraction = position.Quantity <= 0m ? 1m : Math.Min(1m, remaining / position.Quantity);
        var allocatedEntryFee = position.EntryFee * feeFraction;
        var baseQuantity = remaining * position.ContractValue;
        var net = RiskController.EstimateNetPnl(position.Direction, position.EntryPrice, truth.MarkPrice,
            baseQuantity, allocatedEntryFee, position.TakerFeeRate);
        var peak = Math.Max(position.PeakNetProfitUsdt, net);
        var protectedNet = Math.Max(position.ProtectedNetProfitUsdt, RiskController.ProtectedProfitForPeak(peak));
        var currentForCalculation = position with
        {
            MarkPrice = truth.MarkPrice,
            RemainingQuantity = remaining,
            EntryFee = allocatedEntryFee,
            PeakNetProfitUsdt = peak,
            ProtectedNetProfitUsdt = protectedNet
        };
        var hard = RiskController.HardLossStop(currentForCalculation, options.MaxNetLossUsdt,
            options.ExecutionSlippageBufferBps);
        return currentForCalculation with
        {
            HardLossStop = hard,
            CurrentStop = RiskController.MoreProtective(position.Direction, position.CurrentStop, truth.StopPrice)
        };
    }

    public static decimal DesiredStop(ManagedPosition position, decimal slippageBufferBps)
    {
        var desired = RiskController.MoreProtective(position.Direction, position.MirroredStrategyStop,
            position.HardLossStop);
        // The first protection step is true fee-aware break-even (desired NET = 0), so peak state
        // distinguishes an armed break-even from a position that has not reached +1 USDT yet.
        if (position.PeakNetProfitUsdt >= 1.00m)
        {
            var dollar = RiskController.RequiredStopForNet(position.Direction, position.EntryPrice,
                position.RemainingQuantity * position.ContractValue, position.EntryFee, position.TakerFeeRate,
                position.ProtectedNetProfitUsdt, position.TickSize, position.Spread, slippageBufferBps);
            desired = RiskController.MoreProtective(position.Direction, desired, dollar);
        }
        return RiskController.MoreProtective(position.Direction, desired, position.CurrentStop);
    }

    private static decimal CurrentNet(ManagedPosition position, decimal markPrice) =>
        RiskController.EstimateNetPnl(position.Direction, position.EntryPrice, markPrice,
            position.RemainingQuantity * position.ContractValue, position.EntryFee, position.TakerFeeRate);

    private static ExchangePositionTruth? FindTruth(ManagedPosition position, IReadOnlyList<ExchangePositionTruth> truths) =>
        truths.SingleOrDefault(x => x.Symbol.Equals(position.Symbol, StringComparison.OrdinalIgnoreCase) &&
                                    x.Direction == position.Direction && x.Quantity > 0m);

    private static bool IsCrossed(TradeDirection direction, decimal mark, decimal stop) => stop > 0m &&
        (direction == TradeDirection.Long ? mark <= stop : mark >= stop);

    private static bool IsLessProtective(TradeDirection direction, decimal actual, decimal required) =>
        direction == TradeDirection.Long ? actual < required : actual > required;

    private static bool StopSatisfies(TradeDirection direction, decimal actual, decimal requested) => actual > 0m &&
        (direction == TradeDirection.Long ? actual >= requested : actual <= requested);

    private static string PositionKey(ExchangeId exchange, string signalId) => exchange + ":" + signalId;
}
