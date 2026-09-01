namespace KeytrinsMultiExchange.Core;

using System.Text.Json;

public sealed class ExecutionCoordinator
{
    public static readonly TimeSpan DefaultInFlightRecoveryGrace = TimeSpan.FromSeconds(45);
    public static readonly TimeSpan DefaultMissingVisibilityGrace = TimeSpan.FromSeconds(30);
    private readonly TradingDatabase _database;
    private readonly IReadOnlyDictionary<ExchangeId, ILiveExecutionTransport> _transports;
    private readonly ExchangeOperationLocks _locks;
    private readonly SemaphoreSlim _recoveryGate = new(1, 1);
    private readonly TimeSpan _inFlightRecoveryGrace;
    private readonly TimeSpan _missingVisibilityGrace;
    private readonly int _entryConfirmationAttempts;
    private readonly TimeSpan _entryConfirmationPollInterval;

    public ExecutionCoordinator(TradingDatabase database, IEnumerable<ILiveExecutionTransport> transports,
        ExchangeOperationLocks locks, TimeSpan? inFlightRecoveryGrace = null, TimeSpan? missingVisibilityGrace = null,
        int entryConfirmationAttempts = 67, TimeSpan? entryConfirmationPollInterval = null)
    {
        if (entryConfirmationAttempts < 1) throw new ArgumentOutOfRangeException(nameof(entryConfirmationAttempts));
        _database = database;
        _transports = transports.ToDictionary(x => x.Id);
        _locks = locks;
        _inFlightRecoveryGrace = inFlightRecoveryGrace ?? DefaultInFlightRecoveryGrace;
        _missingVisibilityGrace = missingVisibilityGrace ?? DefaultMissingVisibilityGrace;
        _entryConfirmationAttempts = entryConfirmationAttempts;
        _entryConfirmationPollInterval = entryConfirmationPollInterval ?? TimeSpan.FromMilliseconds(300);
    }

    public async Task<IReadOnlyList<RouteAttempt>> RouteOkxLeaderAsync(CanonicalSignal signal,
        RuntimeOptions options, CancellationToken cancellationToken)
    {
        var okx = await RouteSafelyAsync(ExchangeId.Okx, signal, options, cancellationToken);
        if (okx.Result != RouteResult.Filled)
        {
            var reason = "MASTER_OKX_NOT_PROTECTED_" + okx.Reason;
            return
            [
                okx,
                Skip(ExchangeId.Bybit, signal, DateTimeOffset.UtcNow, reason),
                Skip(ExchangeId.KuCoinFutures, signal, DateTimeOffset.UtcNow, reason)
            ];
        }

        var followers = await Task.WhenAll(
            RouteSafelyAsync(ExchangeId.Bybit, signal, options, cancellationToken),
            RouteSafelyAsync(ExchangeId.KuCoinFutures, signal, options, cancellationToken));
        return [okx, .. followers];
    }

    public async Task<RouteAttempt> RouteAsync(ExchangeId exchange, CanonicalSignal signal, RuntimeOptions options,
        CancellationToken cancellationToken)
    {
        var received = DateTimeOffset.UtcNow;
        if (!options.Exchanges.TryGetValue(exchange.ToString(), out var mode) || mode != ExchangeMode.Active)
            return Skip(exchange, signal, received, "EXCHANGE_PAUSED");
        if (!options.TradingEnabled) return Skip(exchange, signal, received, "GLOBAL_ADMISSION_DISABLED");
        if (!_transports.TryGetValue(exchange, out var transport))
            return Skip(exchange, signal, received, "LIVE_TRANSPORT_NOT_AVAILABLE");

        var gate = _locks.For(exchange);
        await gate.WaitAsync(cancellationToken);
        try
        {
            var clientOrderId = ExecutionIds.Entry(exchange, signal.SignalId);
            if (!await _database.TryCreateExecutionIntentAsync(exchange, signal, clientOrderId, cancellationToken))
                return Skip(exchange, signal, received, "SIGNAL_ALREADY_EXECUTED");

            PreparedEntry entry;
            try
            {
                entry = await transport.PrepareEntryAsync(signal, options, cancellationToken);
                await _database.SaveExecutionPlanAsync(entry, cancellationToken);
                await _database.TransitionExecutionAsync(exchange, signal.SignalId, ExecutionCommandState.Intent,
                    ExecutionCommandState.Prechecked, "TECHNICAL_PRECHECK_PASS", null, null, null, cancellationToken);
            }
            catch (ExecutionRejectedException exception)
            {
                await _database.TransitionExecutionAsync(exchange, signal.SignalId, ExecutionCommandState.Intent,
                    ExecutionCommandState.Rejected, exception.Reason, null, null, exception.Reason, cancellationToken);
                return new(exchange, signal.SignalId, received, null, null, RouteResult.Rejected, exception.Reason);
            }
            catch (ExchangeApiException exception)
            {
                await _database.TransitionExecutionAsync(exchange, signal.SignalId, ExecutionCommandState.Intent,
                    ExecutionCommandState.Rejected, "PRECHECK_EXCHANGE_ERROR_" + exception.Message,
                    null, null, exception.Message, cancellationToken);
                return new(exchange, signal.SignalId, received, null, null, RouteResult.Rejected, exception.Message);
            }

            await _database.TransitionExecutionAsync(exchange, signal.SignalId, ExecutionCommandState.Prechecked,
                ExecutionCommandState.Submitting, "ENTRY_HTTP_BEGIN", null, null, null, cancellationToken);
            MutationReceipt receipt;
            try
            {
                receipt = await transport.SubmitEntryAsync(entry, cancellationToken);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                await MarkSubmitUnknown(exchange, signal.SignalId, "ENTRY_TIMEOUT", cancellationToken);
                return new(exchange, signal.SignalId, received, DateTimeOffset.UtcNow, null, RouteResult.Unknown, "SUBMIT_UNKNOWN_TIMEOUT");
            }
            catch (AmbiguousMutationException exception)
            {
                await MarkSubmitUnknown(exchange, signal.SignalId, "ENTRY_AMBIGUOUS_" + exception.Operation, cancellationToken);
                return new(exchange, signal.SignalId, received, DateTimeOffset.UtcNow, null, RouteResult.Unknown, "SUBMIT_UNKNOWN");
            }
            catch (HttpRequestException exception)
            {
                await MarkSubmitUnknown(exchange, signal.SignalId, "ENTRY_NETWORK_" + exception.GetType().Name, cancellationToken);
                return new(exchange, signal.SignalId, received, DateTimeOffset.UtcNow, null, RouteResult.Unknown, "SUBMIT_UNKNOWN_NETWORK");
            }
            catch (ExchangeApiException exception)
            {
                await _database.TransitionExecutionAsync(exchange, signal.SignalId, ExecutionCommandState.Submitting,
                    ExecutionCommandState.Rejected, "ENTRY_EXCHANGE_REJECTED_" + exception.Message,
                    null, null, exception.Message, cancellationToken);
                return new(exchange, signal.SignalId, received, DateTimeOffset.UtcNow, null,
                    RouteResult.Rejected, exception.Message);
            }

            if (receipt.Disposition == MutationDisposition.Ambiguous)
            {
                await MarkSubmitUnknown(exchange, signal.SignalId, receipt.Reason, cancellationToken);
                return new(exchange, signal.SignalId, received, receipt.SubmittedAt, null, RouteResult.Unknown, "SUBMIT_UNKNOWN");
            }
            if (receipt.Disposition == MutationDisposition.Rejected)
            {
                await _database.TransitionExecutionAsync(exchange, signal.SignalId, ExecutionCommandState.Submitting,
                    ExecutionCommandState.Rejected, receipt.Reason, receipt.OrderId, receipt.StopOrderId, receipt.Reason, cancellationToken);
                return new(exchange, signal.SignalId, received, receipt.SubmittedAt, null, RouteResult.Rejected, receipt.Reason,
                    null, entry.Quantity, receipt.OrderId);
            }

            await _database.TransitionExecutionAsync(exchange, signal.SignalId, ExecutionCommandState.Submitting,
                ExecutionCommandState.Submitted, "ENTRY_ACCEPTED", receipt.OrderId, receipt.StopOrderId, null, cancellationToken);
            try
            {
                return await ConfirmEntryAsync(transport, signal, entry, receipt, received, cancellationToken);
            }
            catch (ExchangeApiException exception)
            {
                await _database.TransitionExecutionAsync(exchange, signal.SignalId, ExecutionCommandState.Submitted,
                    ExecutionCommandState.ReconcileRequired, "POST_SUBMIT_RECONCILE_ERROR_" + exception.Message,
                    receipt.OrderId, receipt.StopOrderId, exception.Message, cancellationToken);
                return new(exchange, signal.SignalId, received, receipt.SubmittedAt, null,
                    RouteResult.Unknown, "RECONCILE_REQUIRED_" + exception.Message, null, entry.Quantity, receipt.OrderId);
            }
        }
        finally { gate.Release(); }
    }

    public async Task RecoverAsync(CancellationToken cancellationToken)
    {
        await _recoveryGate.WaitAsync(cancellationToken);
        try
        {
            foreach (var command in await _database.LoadExecutionRecoveryCandidatesAsync(cancellationToken))
            {
                if (!_transports.TryGetValue(command.Exchange, out var transport)) continue;
                var age = DateTimeOffset.UtcNow - command.UpdatedAt;
                if (command.State is ExecutionCommandState.Intent or ExecutionCommandState.Prechecked or
                    ExecutionCommandState.Submitting or ExecutionCommandState.Submitted && age < _inFlightRecoveryGrace)
                    continue;
                var gate = _locks.For(command.Exchange);
                await gate.WaitAsync(cancellationToken);
                try
                {
                    if (command.State is ExecutionCommandState.Intent or ExecutionCommandState.Prechecked)
                    {
                        await _database.ForceExecutionStateAfterReconciliationAsync(command, ExecutionCommandState.Rejected,
                            "RECOVERY_CONFIRMED_NOT_SUBMITTED", null, null, "SERVICE_RESTART_BEFORE_SUBMIT", cancellationToken);
                        continue;
                    }
                    var plan = command.PlanJson is null ? null : JsonSerializer.Deserialize<PreparedEntry>(command.PlanJson);
                    var truth = await transport.ReconcileEntryAsync(plan?.Symbol ?? command.Symbol,
                        command.ClientOrderId, cancellationToken);
                    if (truth.Position is not null && plan is not null)
                    {
                        await PersistManagedPositionAsync(plan, truth, command.CreatedAt, cancellationToken);
                        if (truth.Position.StopPrice > 0m)
                        {
                            await _database.ForceExecutionStateAfterReconciliationAsync(command, ExecutionCommandState.Final,
                                "RECOVERY_POSITION_AND_STOP_CONFIRMED", truth.Order.OrderId, truth.Position.StopOrderId, null, cancellationToken);
                        }
                        else
                        {
                            await _database.ForceExecutionStateAfterReconciliationAsync(command, ExecutionCommandState.ReconcileRequired,
                                "RECOVERY_POSITION_WITHOUT_STOP", truth.Order.OrderId, null, "EMERGENCY_CLOSE_REQUIRED", cancellationToken);
                            await SubmitEmergencyCloseAsync(transport, command.Exchange, command.SignalId, truth.Position, cancellationToken);
                        }
                        continue;
                    }
                    if (truth.Position is null && truth.Order.State is ExchangeOrderState.Filled)
                    {
                        await _database.ForceExecutionStateAfterReconciliationAsync(command, ExecutionCommandState.Final,
                            "RECOVERY_FILLED_AND_EXCHANGE_FLAT", truth.Order.OrderId, null, null, cancellationToken);
                        continue;
                    }
                    if (truth.Position is null && truth.Order.State == ExchangeOrderState.Missing &&
                        DateTimeOffset.UtcNow - command.UpdatedAt >= _missingVisibilityGrace)
                    {
                        await _database.ForceExecutionStateAfterReconciliationAsync(command, ExecutionCommandState.Rejected,
                            "RECOVERY_CLIENT_ID_ABSENT_AND_EXCHANGE_FLAT", null, null, "CONFIRMED_NOT_ACCEPTED", cancellationToken);
                        continue;
                    }
                    if (truth.Position is null && truth.Order.State is ExchangeOrderState.Rejected or ExchangeOrderState.Cancelled)
                    {
                        await _database.ForceExecutionStateAfterReconciliationAsync(command, ExecutionCommandState.Rejected,
                            "RECOVERY_ORDER_TERMINAL_NO_POSITION", truth.Order.OrderId, null, truth.Order.Detail, cancellationToken);
                        continue;
                    }
                    if (command.State != ExecutionCommandState.ReconcileRequired)
                        await _database.ForceExecutionStateAfterReconciliationAsync(command, ExecutionCommandState.ReconcileRequired,
                            "RECOVERY_TRUTH_NOT_TERMINAL", truth.Order.OrderId, truth.Position?.StopOrderId,
                            "MANUAL_SAFETY_RECONCILIATION_REQUIRED", cancellationToken);
                }
                catch (ExchangeApiException exception)
                {
                    try
                    {
                        var plan = command.PlanJson is null ? null : JsonSerializer.Deserialize<PreparedEntry>(command.PlanJson);
                        var symbol = plan?.Symbol ?? command.Symbol;
                        var truths = await transport.GetOpenPositionsAsync(cancellationToken);
                        var positionStillOpen = truths.Any(x => x.Symbol.Equals(symbol, StringComparison.OrdinalIgnoreCase));
                        if (!positionStillOpen && DateTimeOffset.UtcNow - command.UpdatedAt >= _missingVisibilityGrace)
                        {
                            await _database.ForceExecutionStateAfterReconciliationAsync(command,
                                ExecutionCommandState.Final, "RECOVERY_LOOKUP_ERROR_EXCHANGE_FLAT",
                                command.OrderId, command.StopOrderId, exception.Message, cancellationToken);
                        }
                        else
                        {
                            await _database.AppendLogAsync("RECOVERY_ERROR", exception.Message,
                                command.Exchange.ToString(), command.SignalId, cancellationToken);
                        }
                    }
                    catch (Exception truthException)
                    {
                        await _database.AppendLogAsync("RECOVERY_ERROR",
                            exception.Message + ":TRUTH_" + truthException.GetType().Name,
                            command.Exchange.ToString(), command.SignalId, cancellationToken);
                    }
                }
                catch (Exception exception)
                {
                    await _database.AppendLogAsync("RECOVERY_ERROR",
                        exception.GetType().Name + ":" + exception.Message, command.Exchange.ToString(),
                        command.SignalId, cancellationToken);
                }
                finally { gate.Release(); }
            }
        }
        finally { _recoveryGate.Release(); }
    }

    private async Task<RouteAttempt> ConfirmEntryAsync(ILiveExecutionTransport transport, CanonicalSignal signal,
        PreparedEntry entry, MutationReceipt receipt, DateTimeOffset received, CancellationToken cancellationToken)
    {
        ReconciliationTruth truth = new(new(ExchangeOrderState.Unknown, receipt.OrderId, entry.ClientOrderId, 0, 0, 0, "NOT_OBSERVED"), null);
        for (var attempt = 0; attempt < _entryConfirmationAttempts; attempt++)
        {
            truth = await transport.ReconcileEntryAsync(entry.Symbol, entry.ClientOrderId, cancellationToken);
            // An attached OKX stop may become visible shortly after the position itself.  The entry is
            // protected only when both pieces of exchange truth are present.
            if (truth.Position is { StopPrice: > 0m }) break;
            if (truth.Order.State is ExchangeOrderState.Rejected or ExchangeOrderState.Cancelled) break;
            if (attempt + 1 < _entryConfirmationAttempts && _entryConfirmationPollInterval > TimeSpan.Zero)
                await Task.Delay(_entryConfirmationPollInterval, cancellationToken);
        }
        if (truth.Position is null)
        {
            var terminal = truth.Order.State is ExchangeOrderState.Rejected or ExchangeOrderState.Cancelled;
            await _database.TransitionExecutionAsync(entry.Exchange, signal.SignalId, ExecutionCommandState.Submitted,
                terminal ? ExecutionCommandState.Rejected : ExecutionCommandState.ReconcileRequired,
                terminal ? "ORDER_TERMINAL_NO_POSITION" : "POSITION_NOT_CONFIRMED",
                truth.Order.OrderId, null, truth.Order.Detail, cancellationToken);
            return new(entry.Exchange, signal.SignalId, received, receipt.SubmittedAt, null,
                terminal ? RouteResult.Rejected : RouteResult.Unknown, terminal ? truth.Order.Detail : "RECONCILE_REQUIRED",
                null, entry.Quantity, truth.Order.OrderId);
        }

        if (truth.Position.StopPrice <= 0m)
        {
            await PersistManagedPositionAsync(entry, truth, DateTimeOffset.UtcNow, cancellationToken);
            await SubmitEmergencyCloseAsync(transport, entry.Exchange, signal.SignalId, truth.Position, cancellationToken);
            await _database.TransitionExecutionAsync(entry.Exchange, signal.SignalId, ExecutionCommandState.Submitted,
                ExecutionCommandState.ReconcileRequired, "INITIAL_STOP_MISSING_EMERGENCY_CLOSE", truth.Order.OrderId,
                null, "PERSISTENT_REDUCE_ONLY_CLOSE_SUBMITTED", cancellationToken);
            return new(entry.Exchange, signal.SignalId, received, receipt.SubmittedAt, null, RouteResult.Unknown,
                "INITIAL_STOP_MISSING_EMERGENCY_CLOSE", truth.Position.EntryPrice, truth.Position.Quantity, truth.Order.OrderId);
        }

        await PersistManagedPositionAsync(entry, truth, DateTimeOffset.UtcNow, cancellationToken);
        await _database.TransitionExecutionAsync(entry.Exchange, signal.SignalId, ExecutionCommandState.Submitted,
            ExecutionCommandState.Final, "ENTRY_FILL_AND_INITIAL_STOP_CONFIRMED", truth.Order.OrderId,
            truth.Position.StopOrderId ?? receipt.StopOrderId, null, cancellationToken);
        return new(entry.Exchange, signal.SignalId, received, receipt.SubmittedAt, DateTimeOffset.UtcNow,
            RouteResult.Filled, "FILLED_STOP_CONFIRMED", truth.Position.EntryPrice, truth.Position.Quantity, truth.Order.OrderId);
    }

    private async Task MarkSubmitUnknown(ExchangeId exchange, string signalId, string reason, CancellationToken cancellationToken) =>
        await _database.TransitionExecutionAsync(exchange, signalId, ExecutionCommandState.Submitting,
            ExecutionCommandState.SubmitUnknown, reason, null, null, reason, cancellationToken);

    private async Task PersistManagedPositionAsync(PreparedEntry entry, ReconciliationTruth truth, DateTimeOffset openedAt,
        CancellationToken cancellationToken)
    {
        if (truth.Position is null) return;
        var baseQuantity = truth.Position.Quantity * entry.ContractValue;
        var entryFee = truth.Order.FeePaid > 0m
            ? truth.Order.FeePaid
            : truth.Position.EntryPrice * baseQuantity * entry.TakerFeeRate;
        var position = new ManagedPosition(entry.Exchange, entry.SignalId, entry.Symbol, entry.Direction,
            truth.Position.EntryPrice, truth.Position.MarkPrice, truth.Position.Quantity, truth.Position.Quantity,
            entry.ContractValue, entryFee, entry.TakerFeeRate, entry.Spread, 0m, 0m, entry.MirroredStrategyStop,
            entry.HardLossStop, truth.Position.StopPrice, entry.TickSize, openedAt);
        await _database.UpsertPositionAsync(position, cancellationToken);
        await _database.AppendLogAsync("ENTRY_FEE_MODEL",
            $"verifiedRate={entry.TakerFeeRate};actualFee={truth.Order.FeePaid};persistedFee={entryFee}",
            entry.Exchange.ToString(), entry.SignalId, cancellationToken);
    }

    private async Task SubmitEmergencyCloseAsync(ILiveExecutionTransport transport, ExchangeId exchange, string signalId,
        ExchangePositionTruth position, CancellationToken cancellationToken)
    {
        if (await _database.HasUnresolvedRiskActionAsync(exchange, signalId, cancellationToken)) return;
        var revision = await _database.CountRiskActionsAsync(exchange, signalId, cancellationToken) + 1;
        var actionKey = ExecutionIds.Close(exchange, signalId, revision);
        if (!await _database.TryBeginRiskActionAsync(exchange, signalId, actionKey, "CLOSE", null, cancellationToken)) return;
        try
        {
            var receipt = await transport.CloseReduceOnlyAsync(position, actionKey, cancellationToken);
            var state = receipt.Disposition switch
            {
                MutationDisposition.Accepted => "ACCEPTED",
                MutationDisposition.Rejected => "REJECTED",
                _ => "UNKNOWN"
            };
            await _database.CompleteRiskActionAsync(actionKey, state, receipt.OrderId, receipt.Reason, cancellationToken);
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { throw; }
        catch (Exception exception) when (exception is AmbiguousMutationException or HttpRequestException or TaskCanceledException)
        {
            await _database.CompleteRiskActionAsync(actionKey, "UNKNOWN", null, exception.GetType().Name, cancellationToken);
        }
        catch (Exception exception)
        {
            await _database.CompleteRiskActionAsync(actionKey, "REJECTED", null,
                exception.GetType().Name + ":" + exception.Message, cancellationToken);
        }
    }

    private static RouteAttempt Skip(ExchangeId exchange, CanonicalSignal signal, DateTimeOffset received, string reason) =>
        new(exchange, signal.SignalId, received, null, null, RouteResult.Skipped, reason);

    private async Task<RouteAttempt> RouteSafelyAsync(ExchangeId exchange, CanonicalSignal signal,
        RuntimeOptions options, CancellationToken cancellationToken)
    {
        try { return await RouteAsync(exchange, signal, options, cancellationToken); }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested) { throw; }
        catch (Exception exception)
        {
            await _database.AppendLogAsync("ROUTE_ERROR", exception.GetType().Name + ":" + exception.Message,
                exchange.ToString(), signal.SignalId, cancellationToken);
            return new RouteAttempt(exchange, signal.SignalId, DateTimeOffset.UtcNow, null, null,
                RouteResult.Unknown, "UNHANDLED_ROUTE_ERROR_" + exception.GetType().Name);
        }
    }
}
