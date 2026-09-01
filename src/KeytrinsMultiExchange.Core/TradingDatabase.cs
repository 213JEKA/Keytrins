using System.Globalization;
using System.Text.Json;
using Microsoft.Data.Sqlite;

namespace KeytrinsMultiExchange.Core;

public sealed class TradingDatabase
{
    private readonly string _connectionString;
    public TradingDatabase(string dataDirectory)
    {
        Directory.CreateDirectory(dataDirectory);
        var builder = new SqliteConnectionStringBuilder
        {
            DataSource = Path.Combine(dataDirectory, "keytrins-multi-exchange.db"),
            Mode = SqliteOpenMode.ReadWriteCreate,
            Cache = SqliteCacheMode.Shared
        };
        _connectionString = builder.ToString();
    }

    public async Task InitializeAsync(CancellationToken cancellationToken)
    {
        await using var connection = new SqliteConnection(_connectionString);
        await connection.OpenAsync(cancellationToken);
        await ExecuteAsync(connection, "PRAGMA journal_mode=WAL;", cancellationToken);
        await ExecuteAsync(connection, "PRAGMA synchronous=FULL;", cancellationToken);
        await ExecuteAsync(connection, "PRAGMA foreign_keys=ON;", cancellationToken);
        await ExecuteAsync(connection, Schema, cancellationToken);
        await EnsureColumnAsync(connection, "managed_positions", "contract_value", "REAL NOT NULL DEFAULT 1", cancellationToken);
        await EnsureColumnAsync(connection, "execution_commands", "plan_json", "TEXT", cancellationToken);
    }

    public async Task<bool> InsertSignalOnceAsync(CanonicalSignal signal, CancellationToken cancellationToken)
    {
        const string sql = """
            INSERT OR IGNORE INTO canonical_signals
            (signal_id, source_exchange, symbol, signal_time_ms, base_direction, okx_entry_ref, okx_stop_ref,
             okx_risk_distance, okx_risk_distance_pct, m15_atr, adx, score, entry_reason, created_at)
            VALUES ($id, $source, $symbol, $time, $direction, $entry, $stop, $risk, $riskPct, $atr, $adx, $score, $reason, $created);
            """;
        await using var connection = new SqliteConnection(_connectionString);
        await connection.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand(); command.CommandText = sql;
        Add(command, "$id", signal.SignalId); Add(command, "$source", signal.SourceExchange); Add(command, "$symbol", signal.Symbol);
        Add(command, "$time", signal.SignalTimeMs); Add(command, "$direction", signal.BaseSignalDirection.ToString().ToUpperInvariant());
        Add(command, "$entry", signal.OkxEntryRef); Add(command, "$stop", signal.OkxStopRef); Add(command, "$risk", signal.OkxRiskDistance);
        Add(command, "$riskPct", signal.OkxRiskDistancePct); Add(command, "$atr", signal.M15Atr); Add(command, "$adx", signal.Adx);
        Add(command, "$score", signal.Score); Add(command, "$reason", signal.EntryReason); Add(command, "$created", signal.CreatedAt.ToString("O"));
        return await command.ExecuteNonQueryAsync(cancellationToken) == 1;
    }

    public async Task InsertRouteAttemptAsync(RouteAttempt attempt, CancellationToken cancellationToken)
    {
        const string sql = """
            INSERT OR IGNORE INTO route_attempts
            (exchange, signal_id, received_at, submitted_at, filled_at, result, reason, entry_price, quantity, order_id)
            VALUES ($exchange, $signal, $received, $submitted, $filled, $result, $reason, $entry, $qty, $order);
            """;
        await using var connection = new SqliteConnection(_connectionString); await connection.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand(); command.CommandText = sql;
        Add(command, "$exchange", attempt.Exchange.ToString()); Add(command, "$signal", attempt.SignalId);
        Add(command, "$received", attempt.ReceivedAt.ToString("O")); Add(command, "$submitted", attempt.SubmittedAt?.ToString("O"));
        Add(command, "$filled", attempt.FilledAt?.ToString("O")); Add(command, "$result", attempt.Result.ToString().ToUpperInvariant());
        Add(command, "$reason", attempt.Reason); Add(command, "$entry", attempt.EntryPrice); Add(command, "$qty", attempt.Quantity); Add(command, "$order", attempt.OrderId);
        await command.ExecuteNonQueryAsync(cancellationToken);
    }

    public async Task<bool> TryCreateExecutionIntentAsync(ExchangeId exchange, CanonicalSignal signal,
        string clientOrderId, CancellationToken cancellationToken)
    {
        await using var connection = new SqliteConnection(_connectionString);
        await connection.OpenAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        var now = DateTimeOffset.UtcNow.ToString("O");
        await using var command = connection.CreateCommand();
        command.Transaction = (SqliteTransaction)transaction;
        command.CommandText = """
            INSERT OR IGNORE INTO execution_commands
            (exchange,signal_id,client_order_id,symbol,direction,state,created_at,updated_at)
            VALUES($exchange,$signal,$client,$symbol,$direction,'Intent',$now,$now);
            """;
        Add(command, "$exchange", exchange.ToString()); Add(command, "$signal", signal.SignalId);
        Add(command, "$client", clientOrderId); Add(command, "$symbol", signal.Symbol);
        Add(command, "$direction", signal.ActualDirection.ToString()); Add(command, "$now", now);
        var inserted = await command.ExecuteNonQueryAsync(cancellationToken) == 1;
        if (inserted)
        {
            await using var transition = connection.CreateCommand();
            transition.Transaction = (SqliteTransaction)transaction;
            transition.CommandText = """
                INSERT INTO execution_transitions(exchange,signal_id,from_state,to_state,reason,at)
                VALUES($exchange,$signal,NULL,'Intent','CANONICAL_SIGNAL_RECEIVED',$at);
                """;
            Add(transition, "$exchange", exchange.ToString()); Add(transition, "$signal", signal.SignalId); Add(transition, "$at", now);
            await transition.ExecuteNonQueryAsync(cancellationToken);
        }
        await transaction.CommitAsync(cancellationToken);
        return inserted;
    }

    public async Task TransitionExecutionAsync(ExchangeId exchange, string signalId, ExecutionCommandState expected,
        ExecutionCommandState next, string reason, string? orderId, string? stopOrderId, string? lastError,
        CancellationToken cancellationToken)
    {
        await using var connection = new SqliteConnection(_connectionString);
        await connection.OpenAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        var now = DateTimeOffset.UtcNow.ToString("O");
        await using var update = connection.CreateCommand();
        update.Transaction = (SqliteTransaction)transaction;
        update.CommandText = """
            UPDATE execution_commands SET state=$next,
              order_id=COALESCE($order,order_id), stop_order_id=COALESCE($stop,stop_order_id),
              last_error=$error, updated_at=$at
            WHERE exchange=$exchange AND signal_id=$signal AND state=$expected;
            """;
        Add(update, "$next", next.ToString()); Add(update, "$order", orderId); Add(update, "$stop", stopOrderId);
        Add(update, "$error", lastError); Add(update, "$at", now); Add(update, "$exchange", exchange.ToString());
        Add(update, "$signal", signalId); Add(update, "$expected", expected.ToString());
        if (await update.ExecuteNonQueryAsync(cancellationToken) != 1)
            throw new InvalidOperationException($"EXECUTION_STATE_CONFLICT:{exchange}:{signalId}:{expected}->{next}");
        await using var transition = connection.CreateCommand();
        transition.Transaction = (SqliteTransaction)transaction;
        transition.CommandText = """
            INSERT INTO execution_transitions(exchange,signal_id,from_state,to_state,reason,at)
            VALUES($exchange,$signal,$from,$to,$reason,$at);
            """;
        Add(transition, "$exchange", exchange.ToString()); Add(transition, "$signal", signalId);
        Add(transition, "$from", expected.ToString()); Add(transition, "$to", next.ToString());
        Add(transition, "$reason", reason); Add(transition, "$at", now);
        await transition.ExecuteNonQueryAsync(cancellationToken);
        await transaction.CommitAsync(cancellationToken);
    }

    public async Task SaveExecutionPlanAsync(PreparedEntry entry, CancellationToken cancellationToken)
    {
        await using var connection = new SqliteConnection(_connectionString); await connection.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand();
        command.CommandText = """
            UPDATE execution_commands SET plan_json=$plan,updated_at=$at
            WHERE exchange=$exchange AND signal_id=$signal AND state='Intent';
            """;
        Add(command, "$plan", JsonSerializer.Serialize(entry)); Add(command, "$at", DateTimeOffset.UtcNow.ToString("O"));
        Add(command, "$exchange", entry.Exchange.ToString()); Add(command, "$signal", entry.SignalId);
        if (await command.ExecuteNonQueryAsync(cancellationToken) != 1)
            throw new InvalidOperationException("EXECUTION_PLAN_STATE_CONFLICT");
    }

    public async Task<int> CountUnresolvedExecutionCommandsAsync(CancellationToken cancellationToken)
    {
        await using var connection = new SqliteConnection(_connectionString); await connection.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT COUNT(*) FROM execution_commands WHERE state NOT IN ('Final','Rejected')";
        return Convert.ToInt32(await command.ExecuteScalarAsync(cancellationToken), CultureInfo.InvariantCulture);
    }

    public async Task<IReadOnlyList<ExecutionCommand>> LoadUnresolvedExecutionCommandsAsync(CancellationToken cancellationToken)
        => await LoadExecutionCommandsAsync("state NOT IN ('Final','Rejected')", cancellationToken);

    public async Task<IReadOnlyList<ExecutionCommand>> LoadExecutionRecoveryCandidatesAsync(CancellationToken cancellationToken)
        => await LoadExecutionCommandsAsync(
            "state NOT IN ('Final','Rejected') OR (state='Rejected' AND last_error='SERVICE_RESTART_BEFORE_SUBMIT' AND plan_json IS NOT NULL)",
            cancellationToken);

    private async Task<IReadOnlyList<ExecutionCommand>> LoadExecutionCommandsAsync(string predicate,
        CancellationToken cancellationToken)
    {
        var sql = $"""
            SELECT id,exchange,signal_id,client_order_id,symbol,direction,plan_json,state,order_id,stop_order_id,last_error,
                   created_at,updated_at
            FROM execution_commands WHERE {predicate} ORDER BY id;
            """;
        await using var connection = new SqliteConnection(_connectionString); await connection.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand(); command.CommandText = sql;
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        var output = new List<ExecutionCommand>();
        while (await reader.ReadAsync(cancellationToken))
        {
            output.Add(new(
                reader.GetInt64(0), Enum.Parse<ExchangeId>(reader.GetString(1), true), reader.GetString(2),
                reader.GetString(3), reader.GetString(4), Enum.Parse<TradeDirection>(reader.GetString(5), true),
                reader.IsDBNull(6) ? null : reader.GetString(6), Enum.Parse<ExecutionCommandState>(reader.GetString(7), true),
                reader.IsDBNull(8) ? null : reader.GetString(8), reader.IsDBNull(9) ? null : reader.GetString(9),
                reader.IsDBNull(10) ? null : reader.GetString(10), DateTimeOffset.Parse(reader.GetString(11), CultureInfo.InvariantCulture),
                DateTimeOffset.Parse(reader.GetString(12), CultureInfo.InvariantCulture)));
        }
        return output;
    }

    public async Task ForceExecutionStateAfterReconciliationAsync(ExecutionCommand command, ExecutionCommandState next,
        string reason, string? orderId, string? stopOrderId, string? lastError, CancellationToken cancellationToken)
    {
        await using var connection = new SqliteConnection(_connectionString); await connection.OpenAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        var now = DateTimeOffset.UtcNow.ToString("O");
        await using var update = connection.CreateCommand(); update.Transaction = (SqliteTransaction)transaction;
        update.CommandText = """
            UPDATE execution_commands SET state=$next,order_id=COALESCE($order,order_id),
              stop_order_id=COALESCE($stop,stop_order_id),last_error=$error,updated_at=$at WHERE id=$id;
            """;
        Add(update, "$next", next.ToString()); Add(update, "$order", orderId); Add(update, "$stop", stopOrderId);
        Add(update, "$error", lastError); Add(update, "$at", now); Add(update, "$id", command.Id);
        if (await update.ExecuteNonQueryAsync(cancellationToken) != 1) throw new InvalidOperationException("EXECUTION_COMMAND_MISSING");
        await using var transition = connection.CreateCommand(); transition.Transaction = (SqliteTransaction)transaction;
        transition.CommandText = """
            INSERT INTO execution_transitions(exchange,signal_id,from_state,to_state,reason,at)
            VALUES($exchange,$signal,$from,$to,$reason,$at);
            """;
        Add(transition, "$exchange", command.Exchange.ToString()); Add(transition, "$signal", command.SignalId);
        Add(transition, "$from", command.State.ToString()); Add(transition, "$to", next.ToString());
        Add(transition, "$reason", reason); Add(transition, "$at", now);
        await transition.ExecuteNonQueryAsync(cancellationToken); await transaction.CommitAsync(cancellationToken);
    }

    public async Task UpsertPositionAsync(ManagedPosition position, CancellationToken cancellationToken)
    {
        const string sql = """
            INSERT INTO managed_positions
            (exchange, signal_id, symbol, direction, entry_price, mark_price, quantity, remaining_quantity, contract_value, entry_fee,
             taker_fee_rate, spread, peak_net, protected_net, mirrored_stop, hard_loss_stop, current_stop, tick_size,
             opened_at, state, updated_at)
            VALUES ($exchange,$signal,$symbol,$direction,$entry,$mark,$qty,$remaining,$contractValue,$entryFee,$feeRate,$spread,$peak,
                    $protected,$mirrored,$hard,$current,$tick,$opened,$state,$updated)
            ON CONFLICT(exchange, signal_id) DO UPDATE SET
              mark_price=excluded.mark_price, remaining_quantity=excluded.remaining_quantity,
              peak_net=MAX(managed_positions.peak_net, excluded.peak_net),
              protected_net=MAX(managed_positions.protected_net, excluded.protected_net),
              hard_loss_stop=excluded.hard_loss_stop,
              current_stop=CASE WHEN excluded.current_stop<=0 THEN managed_positions.current_stop
                                WHEN managed_positions.current_stop<=0 THEN excluded.current_stop
                                WHEN managed_positions.direction='Long' THEN MAX(managed_positions.current_stop, excluded.current_stop)
                                ELSE MIN(managed_positions.current_stop, excluded.current_stop) END,
              state=excluded.state, updated_at=excluded.updated_at;
            """;
        await using var connection = new SqliteConnection(_connectionString); await connection.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand(); command.CommandText = sql;
        Add(command,"$exchange",position.Exchange.ToString()); Add(command,"$signal",position.SignalId); Add(command,"$symbol",position.Symbol);
        Add(command,"$direction",position.Direction.ToString()); Add(command,"$entry",position.EntryPrice); Add(command,"$mark",position.MarkPrice);
        Add(command,"$qty",position.Quantity); Add(command,"$remaining",position.RemainingQuantity); Add(command,"$entryFee",position.EntryFee);
        Add(command,"$contractValue",position.ContractValue);
        Add(command,"$feeRate",position.TakerFeeRate); Add(command,"$spread",position.Spread); Add(command,"$peak",position.PeakNetProfitUsdt);
        Add(command,"$protected",position.ProtectedNetProfitUsdt); Add(command,"$mirrored",position.MirroredStrategyStop);
        Add(command,"$hard",position.HardLossStop); Add(command,"$current",position.CurrentStop); Add(command,"$tick",position.TickSize);
        Add(command,"$opened",position.OpenedAt.ToString("O")); Add(command,"$state",position.State); Add(command,"$updated",DateTimeOffset.UtcNow.ToString("O"));
        await command.ExecuteNonQueryAsync(cancellationToken);
    }

    public async Task<IReadOnlyList<ManagedPosition>> LoadOpenManagedPositionsAsync(CancellationToken cancellationToken)
    {
        const string sql = """
            SELECT exchange,signal_id,symbol,direction,entry_price,mark_price,quantity,remaining_quantity,contract_value,
                   entry_fee,taker_fee_rate,spread,peak_net,protected_net,mirrored_stop,hard_loss_stop,current_stop,
                   tick_size,opened_at,state FROM managed_positions WHERE state='OPEN' ORDER BY id;
            """;
        await using var connection = new SqliteConnection(_connectionString); await connection.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand(); command.CommandText = sql;
        await using var reader = await command.ExecuteReaderAsync(cancellationToken); var output = new List<ManagedPosition>();
        while (await reader.ReadAsync(cancellationToken))
            output.Add(new(Enum.Parse<ExchangeId>(reader.GetString(0), true), reader.GetString(1), reader.GetString(2),
                Enum.Parse<TradeDirection>(reader.GetString(3), true), reader.GetDecimal(4), reader.GetDecimal(5),
                reader.GetDecimal(6), reader.GetDecimal(7), reader.GetDecimal(8), reader.GetDecimal(9), reader.GetDecimal(10),
                reader.GetDecimal(11), reader.GetDecimal(12), reader.GetDecimal(13), reader.GetDecimal(14), reader.GetDecimal(15),
                reader.GetDecimal(16), reader.GetDecimal(17), DateTimeOffset.Parse(reader.GetString(18), CultureInfo.InvariantCulture),
                reader.GetString(19)));
        return output;
    }

    public async Task<int> CountOpenManagedPositionsAsync(CancellationToken cancellationToken)
    {
        await using var connection = new SqliteConnection(_connectionString); await connection.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand(); command.CommandText = "SELECT COUNT(*) FROM managed_positions WHERE state='OPEN'";
        return Convert.ToInt32(await command.ExecuteScalarAsync(cancellationToken), CultureInfo.InvariantCulture);
    }

    public async Task<int> CountActiveSignalCyclesAsync(CancellationToken cancellationToken)
    {
        await using var connection = new SqliteConnection(_connectionString); await connection.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand();
        command.CommandText = """
            SELECT COUNT(*) FROM (
              SELECT signal_id FROM managed_positions WHERE state='OPEN'
              UNION
              SELECT signal_id FROM execution_commands WHERE state NOT IN ('Final','Rejected')
            );
            """;
        return Convert.ToInt32(await command.ExecuteScalarAsync(cancellationToken), CultureInfo.InvariantCulture);
    }

    public async Task MarkPositionClosedAsync(ExchangeId exchange, string signalId, string reason,
        CancellationToken cancellationToken)
    {
        await using var connection = new SqliteConnection(_connectionString); await connection.OpenAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        await using var update = connection.CreateCommand(); update.Transaction = (SqliteTransaction)transaction;
        update.CommandText = "UPDATE managed_positions SET state='CLOSED',updated_at=$at WHERE exchange=$exchange AND signal_id=$signal AND state='OPEN'";
        Add(update, "$at", DateTimeOffset.UtcNow.ToString("O")); Add(update, "$exchange", exchange.ToString()); Add(update, "$signal", signalId);
        await update.ExecuteNonQueryAsync(cancellationToken);
        await using var log = connection.CreateCommand(); log.Transaction = (SqliteTransaction)transaction;
        log.CommandText = "INSERT INTO event_log(at,category,exchange,signal_id,message) VALUES($at,'POSITION_FINAL',$exchange,$signal,$reason)";
        Add(log, "$at", DateTimeOffset.UtcNow.ToString("O")); Add(log, "$exchange", exchange.ToString()); Add(log, "$signal", signalId); Add(log, "$reason", reason);
        await log.ExecuteNonQueryAsync(cancellationToken);
        await using var actions = connection.CreateCommand(); actions.Transaction = (SqliteTransaction)transaction;
        actions.CommandText = "UPDATE risk_actions SET state='CONFIRMED',updated_at=$at WHERE exchange=$exchange AND signal_id=$signal AND state NOT IN ('CONFIRMED','REJECTED')";
        Add(actions, "$at", DateTimeOffset.UtcNow.ToString("O")); Add(actions, "$exchange", exchange.ToString()); Add(actions, "$signal", signalId);
        await actions.ExecuteNonQueryAsync(cancellationToken);
        await using var execution = connection.CreateCommand(); execution.Transaction = (SqliteTransaction)transaction;
        execution.CommandText = """
            UPDATE execution_commands SET state='Final',last_error=NULL,updated_at=$at
            WHERE exchange=$exchange AND signal_id=$signal AND state NOT IN ('Final','Rejected');
            """;
        Add(execution, "$at", DateTimeOffset.UtcNow.ToString("O")); Add(execution, "$exchange", exchange.ToString());
        Add(execution, "$signal", signalId); await execution.ExecuteNonQueryAsync(cancellationToken);
        await transaction.CommitAsync(cancellationToken);
    }

    public async Task<bool> TryBeginRiskActionAsync(ExchangeId exchange, string signalId, string actionKey, string kind,
        decimal? requestedStop, CancellationToken cancellationToken)
    {
        await using var connection = new SqliteConnection(_connectionString); await connection.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand(); command.CommandText = """
            INSERT OR IGNORE INTO risk_actions(exchange,signal_id,action_key,kind,requested_stop,state,created_at,updated_at)
            VALUES($exchange,$signal,$key,$kind,$stop,'SUBMITTING',$at,$at);
            """;
        Add(command, "$exchange", exchange.ToString()); Add(command, "$signal", signalId); Add(command, "$key", actionKey);
        Add(command, "$kind", kind); Add(command, "$stop", requestedStop); Add(command, "$at", DateTimeOffset.UtcNow.ToString("O"));
        return await command.ExecuteNonQueryAsync(cancellationToken) == 1;
    }

    public async Task CompleteRiskActionAsync(string actionKey, string state, string? exchangeOrderId, string? error,
        CancellationToken cancellationToken)
    {
        await using var connection = new SqliteConnection(_connectionString); await connection.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand(); command.CommandText = """
            UPDATE risk_actions SET state=$state,exchange_order_id=COALESCE($order,exchange_order_id),error=$error,
              updated_at=CASE WHEN state=$state THEN updated_at ELSE $at END
            WHERE action_key=$key;
            """;
        Add(command, "$state", state); Add(command, "$order", exchangeOrderId); Add(command, "$error", error);
        Add(command, "$at", DateTimeOffset.UtcNow.ToString("O")); Add(command, "$key", actionKey);
        if (await command.ExecuteNonQueryAsync(cancellationToken) != 1) throw new InvalidOperationException("RISK_ACTION_MISSING");
    }

    public async Task<bool> HasUnresolvedRiskActionAsync(ExchangeId exchange, string signalId, CancellationToken cancellationToken)
    {
        await using var connection = new SqliteConnection(_connectionString); await connection.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT EXISTS(SELECT 1 FROM risk_actions WHERE exchange=$exchange AND signal_id=$signal AND state IN ('SUBMITTING','UNKNOWN','ACCEPTED'))";
        Add(command, "$exchange", exchange.ToString()); Add(command, "$signal", signalId);
        return Convert.ToInt32(await command.ExecuteScalarAsync(cancellationToken), CultureInfo.InvariantCulture) == 1;
    }

    public async Task<int> CountUnresolvedRiskActionsAsync(CancellationToken cancellationToken)
    {
        await using var connection = new SqliteConnection(_connectionString); await connection.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT COUNT(*) FROM risk_actions WHERE state IN ('SUBMITTING','UNKNOWN','ACCEPTED')";
        return Convert.ToInt32(await command.ExecuteScalarAsync(cancellationToken), CultureInfo.InvariantCulture);
    }

    public async Task<IReadOnlyList<RiskAction>> LoadUnresolvedRiskActionsAsync(CancellationToken cancellationToken)
    {
        const string sql = """
            SELECT id,exchange,signal_id,action_key,kind,requested_stop,state,exchange_order_id,error,created_at,updated_at
            FROM risk_actions WHERE state IN ('SUBMITTING','UNKNOWN','ACCEPTED') ORDER BY id;
            """;
        await using var connection = new SqliteConnection(_connectionString); await connection.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand(); command.CommandText = sql;
        await using var reader = await command.ExecuteReaderAsync(cancellationToken);
        var output = new List<RiskAction>();
        while (await reader.ReadAsync(cancellationToken))
            output.Add(new(reader.GetInt64(0), Enum.Parse<ExchangeId>(reader.GetString(1), true), reader.GetString(2),
                reader.GetString(3), reader.GetString(4), reader.IsDBNull(5) ? null : reader.GetDecimal(5), reader.GetString(6),
                reader.IsDBNull(7) ? null : reader.GetString(7), reader.IsDBNull(8) ? null : reader.GetString(8),
                DateTimeOffset.Parse(reader.GetString(9), CultureInfo.InvariantCulture),
                DateTimeOffset.Parse(reader.GetString(10), CultureInfo.InvariantCulture)));
        return output;
    }

    public async Task<int> CountRiskActionsAsync(ExchangeId exchange, string signalId, CancellationToken cancellationToken)
    {
        await using var connection = new SqliteConnection(_connectionString); await connection.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand();
        command.CommandText = "SELECT COUNT(*) FROM risk_actions WHERE exchange=$exchange AND signal_id=$signal";
        Add(command, "$exchange", exchange.ToString()); Add(command, "$signal", signalId);
        return Convert.ToInt32(await command.ExecuteScalarAsync(cancellationToken), CultureInfo.InvariantCulture);
    }

    public async Task FinalizeExecutionAfterRiskRecoveryAsync(ExchangeId exchange, string signalId, string reason,
        CancellationToken cancellationToken)
    {
        await using var connection = new SqliteConnection(_connectionString); await connection.OpenAsync(cancellationToken);
        await using var transaction = await connection.BeginTransactionAsync(cancellationToken);
        await using var read = connection.CreateCommand(); read.Transaction = (SqliteTransaction)transaction;
        read.CommandText = "SELECT state FROM execution_commands WHERE exchange=$exchange AND signal_id=$signal";
        Add(read, "$exchange", exchange.ToString()); Add(read, "$signal", signalId);
        var current = Convert.ToString(await read.ExecuteScalarAsync(cancellationToken), CultureInfo.InvariantCulture);
        if (string.IsNullOrEmpty(current) || current is "Final" or "Rejected")
        { await transaction.CommitAsync(cancellationToken); return; }
        var now = DateTimeOffset.UtcNow.ToString("O");
        await using var update = connection.CreateCommand(); update.Transaction = (SqliteTransaction)transaction;
        update.CommandText = "UPDATE execution_commands SET state='Final',last_error=NULL,updated_at=$at WHERE exchange=$exchange AND signal_id=$signal";
        Add(update, "$at", now); Add(update, "$exchange", exchange.ToString()); Add(update, "$signal", signalId);
        await update.ExecuteNonQueryAsync(cancellationToken);
        await using var transition = connection.CreateCommand(); transition.Transaction = (SqliteTransaction)transaction;
        transition.CommandText = """
            INSERT INTO execution_transitions(exchange,signal_id,from_state,to_state,reason,at)
            VALUES($exchange,$signal,$from,'Final',$reason,$at);
            """;
        Add(transition, "$exchange", exchange.ToString()); Add(transition, "$signal", signalId); Add(transition, "$from", current);
        Add(transition, "$reason", reason); Add(transition, "$at", now);
        await transition.ExecuteNonQueryAsync(cancellationToken); await transaction.CommitAsync(cancellationToken);
    }

    public async Task AppendLogAsync(string category, string message, string? exchange, string? signalId, CancellationToken cancellationToken)
    {
        await using var connection = new SqliteConnection(_connectionString); await connection.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand();
        command.CommandText = "INSERT INTO event_log(at,category,exchange,signal_id,message) VALUES($at,$category,$exchange,$signal,$message)";
        Add(command,"$at",DateTimeOffset.UtcNow.ToString("O")); Add(command,"$category",category); Add(command,"$exchange",exchange);
        Add(command,"$signal",signalId); Add(command,"$message",message); await command.ExecuteNonQueryAsync(cancellationToken);
    }

    public async Task<IReadOnlyList<Dictionary<string, object?>>> QueryAsync(string table, int limit, CancellationToken cancellationToken)
    {
        if (table is not ("canonical_signals" or "route_attempts" or "managed_positions" or "event_log" or
            "execution_commands" or "execution_transitions" or "risk_actions")) throw new ArgumentException("Invalid table");
        await using var connection = new SqliteConnection(_connectionString); await connection.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand(); command.CommandText = $"SELECT * FROM {table} ORDER BY rowid DESC LIMIT $limit"; Add(command,"$limit",Math.Clamp(limit,1,500));
        await using var reader = await command.ExecuteReaderAsync(cancellationToken); var rows = new List<Dictionary<string, object?>>();
        while (await reader.ReadAsync(cancellationToken))
        {
            var row = new Dictionary<string, object?>(StringComparer.OrdinalIgnoreCase);
            for (var i=0;i<reader.FieldCount;i++) row[reader.GetName(i)] = reader.IsDBNull(i) ? null : reader.GetValue(i);
            rows.Add(row);
        }
        return rows;
    }

    private static async Task ExecuteAsync(SqliteConnection connection, string sql, CancellationToken cancellationToken)
    { await using var command = connection.CreateCommand(); command.CommandText = sql; await command.ExecuteNonQueryAsync(cancellationToken); }
    private static async Task EnsureColumnAsync(SqliteConnection connection, string table, string column, string declaration,
        CancellationToken cancellationToken)
    {
        await using var inspect = connection.CreateCommand(); inspect.CommandText = $"PRAGMA table_info({table})";
        await using var reader = await inspect.ExecuteReaderAsync(cancellationToken); var exists = false;
        while (await reader.ReadAsync(cancellationToken)) if (reader.GetString(1).Equals(column, StringComparison.OrdinalIgnoreCase)) exists = true;
        await reader.DisposeAsync();
        if (!exists) await ExecuteAsync(connection, $"ALTER TABLE {table} ADD COLUMN {column} {declaration}", cancellationToken);
    }
    private static void Add(SqliteCommand command, string name, object? value) => command.Parameters.AddWithValue(name, value ?? DBNull.Value);

    private const string Schema = """
        CREATE TABLE IF NOT EXISTS canonical_signals(
          signal_id TEXT PRIMARY KEY, source_exchange TEXT NOT NULL, symbol TEXT NOT NULL, signal_time_ms INTEGER NOT NULL,
          base_direction TEXT NOT NULL, okx_entry_ref REAL NOT NULL, okx_stop_ref REAL NOT NULL,
          okx_risk_distance REAL NOT NULL, okx_risk_distance_pct REAL NOT NULL, m15_atr REAL NOT NULL,
          adx REAL NOT NULL, score REAL NOT NULL, entry_reason TEXT NOT NULL, created_at TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS route_attempts(
          id INTEGER PRIMARY KEY AUTOINCREMENT, exchange TEXT NOT NULL, signal_id TEXT NOT NULL,
          received_at TEXT NOT NULL, submitted_at TEXT, filled_at TEXT, result TEXT NOT NULL, reason TEXT NOT NULL,
          entry_price REAL, quantity REAL, order_id TEXT, UNIQUE(exchange, signal_id),
          FOREIGN KEY(signal_id) REFERENCES canonical_signals(signal_id));
        CREATE TABLE IF NOT EXISTS managed_positions(
          id INTEGER PRIMARY KEY AUTOINCREMENT, exchange TEXT NOT NULL, signal_id TEXT NOT NULL, symbol TEXT NOT NULL,
          direction TEXT NOT NULL, entry_price REAL NOT NULL, mark_price REAL NOT NULL, quantity REAL NOT NULL,
          remaining_quantity REAL NOT NULL, contract_value REAL NOT NULL DEFAULT 1, entry_fee REAL NOT NULL, taker_fee_rate REAL NOT NULL, spread REAL NOT NULL,
          peak_net REAL NOT NULL, protected_net REAL NOT NULL, mirrored_stop REAL NOT NULL, hard_loss_stop REAL NOT NULL,
          current_stop REAL NOT NULL, tick_size REAL NOT NULL, opened_at TEXT NOT NULL, state TEXT NOT NULL, updated_at TEXT NOT NULL,
          UNIQUE(exchange, signal_id));
        CREATE TABLE IF NOT EXISTS event_log(
          id INTEGER PRIMARY KEY AUTOINCREMENT, at TEXT NOT NULL, category TEXT NOT NULL, exchange TEXT,
          signal_id TEXT, message TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS execution_commands(
          id INTEGER PRIMARY KEY AUTOINCREMENT, exchange TEXT NOT NULL, signal_id TEXT NOT NULL,
          client_order_id TEXT NOT NULL, symbol TEXT NOT NULL, direction TEXT NOT NULL, state TEXT NOT NULL,
          order_id TEXT, stop_order_id TEXT, last_error TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
          UNIQUE(exchange,signal_id), UNIQUE(exchange,client_order_id),
          FOREIGN KEY(signal_id) REFERENCES canonical_signals(signal_id));
        CREATE TABLE IF NOT EXISTS execution_transitions(
          id INTEGER PRIMARY KEY AUTOINCREMENT, exchange TEXT NOT NULL, signal_id TEXT NOT NULL,
          from_state TEXT, to_state TEXT NOT NULL, reason TEXT NOT NULL, at TEXT NOT NULL,
          FOREIGN KEY(signal_id) REFERENCES canonical_signals(signal_id));
        CREATE TABLE IF NOT EXISTS risk_actions(
          id INTEGER PRIMARY KEY AUTOINCREMENT, exchange TEXT NOT NULL, signal_id TEXT NOT NULL,
          action_key TEXT NOT NULL UNIQUE, kind TEXT NOT NULL, requested_stop REAL, state TEXT NOT NULL,
          exchange_order_id TEXT, error TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
          FOREIGN KEY(signal_id) REFERENCES canonical_signals(signal_id));
        CREATE INDEX IF NOT EXISTS ix_event_log_at ON event_log(at DESC);
        CREATE INDEX IF NOT EXISTS ix_execution_commands_state ON execution_commands(state);
        CREATE INDEX IF NOT EXISTS ix_execution_transitions_signal ON execution_transitions(exchange,signal_id,id);
        CREATE INDEX IF NOT EXISTS ix_risk_actions_state ON risk_actions(state);
        """;
}
