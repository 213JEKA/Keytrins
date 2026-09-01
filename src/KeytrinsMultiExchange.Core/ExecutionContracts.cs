namespace KeytrinsMultiExchange.Core;

public enum MutationDisposition { Accepted, Rejected, Ambiguous }
public enum ExchangeOrderState { Missing, Open, PartiallyFilled, Filled, Rejected, Cancelled, Unknown }

public sealed record PreparedEntry(
    ExchangeId Exchange,
    string SignalId,
    string ClientOrderId,
    string Symbol,
    TradeDirection Direction,
    int Leverage,
    decimal Quantity,
    decimal ContractValue,
    decimal ReferencePrice,
    decimal InitialStop,
    decimal MirroredStrategyStop,
    decimal HardLossStop,
    decimal TickSize,
    decimal TakerFeeRate,
    decimal Spread,
    decimal EstimatedCostR);

public sealed record MutationReceipt(
    MutationDisposition Disposition,
    string? OrderId,
    string? StopOrderId,
    string Reason,
    DateTimeOffset SubmittedAt);

public sealed record ExchangeOrderTruth(
    ExchangeOrderState State,
    string? OrderId,
    string ClientOrderId,
    decimal FilledQuantity,
    decimal AveragePrice,
    decimal FeePaid,
    string Detail);

public sealed record ExchangePositionTruth(
    string Symbol,
    TradeDirection Direction,
    decimal Quantity,
    decimal EntryPrice,
    decimal MarkPrice,
    decimal StopPrice,
    string? StopOrderId,
    bool IsOneWay,
    DateTimeOffset ObservedAt,
    int Leverage = 1);

public sealed record ReconciliationTruth(ExchangeOrderTruth Order, ExchangePositionTruth? Position);

public sealed class AmbiguousMutationException(string operation, Exception? inner = null)
    : Exception(operation, inner)
{
    public string Operation { get; } = operation;
}

public sealed class ExecutionRejectedException(string reason) : Exception(reason)
{
    public string Reason { get; } = reason;
}

public interface ILiveExecutionTransport
{
    ExchangeId Id { get; }
    Task<PreparedEntry> PrepareEntryAsync(CanonicalSignal signal, RuntimeOptions options, CancellationToken cancellationToken);
    Task<MutationReceipt> SubmitEntryAsync(PreparedEntry entry, CancellationToken cancellationToken);
    Task<ReconciliationTruth> ReconcileEntryAsync(string symbol, string clientOrderId, CancellationToken cancellationToken);
    Task<MutationReceipt> ReplaceStopAsync(ExchangePositionTruth position, decimal stopPrice, string clientActionId,
        CancellationToken cancellationToken);
    Task<MutationReceipt> CloseReduceOnlyAsync(ExchangePositionTruth position, string clientActionId,
        CancellationToken cancellationToken);
    Task<IReadOnlyList<ExchangePositionTruth>> GetOpenPositionsAsync(CancellationToken cancellationToken);
}

public interface IWriterAuditSource
{
    Task<IReadOnlyList<Dictionary<string, string>>> GetRecentOrderAuditAsync(string? symbol,
        CancellationToken cancellationToken);
}

public sealed record WriterExclusivityStatus(bool IsExclusive, DateTimeOffset CheckedAt,
    DateTimeOffset? LatestForeignEntryAt, string? LatestForeignClientId, string Detail);

public static class ExecutionIds
{
    public static string Entry(ExchangeId exchange, string signalId) => Safe($"KX-{exchange}-E-{signalId}", 32);
    public static string Stop(ExchangeId exchange, string signalId, int revision) => Safe($"KX-{exchange}-S{revision}-{signalId}", 32);
    public static string Close(ExchangeId exchange, string signalId, int revision) => Safe($"KX-{exchange}-C{revision}-{signalId}", 32);

    private static string Safe(string value, int maximumLength)
    {
        var normalized = new string(value.Where(char.IsLetterOrDigit).ToArray());
        if (normalized.Length <= maximumLength) return normalized;
        var hash = Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(
            System.Text.Encoding.UTF8.GetBytes(normalized))).ToLowerInvariant()[..16];
        return normalized[..(maximumLength - hash.Length)] + hash;
    }
}
