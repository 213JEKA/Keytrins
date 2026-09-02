using System.Collections.Concurrent;

namespace KeytrinsMultiExchange.Core;

public enum ExchangeId { Okx, Bybit, KuCoinFutures, Bitget, MexcFutures, GateFutures, BingX, CoinExFutures }
public enum ExchangeMode { Active, Paused, Disabling, Off, Error, NotConfigured }
public enum TradeDirection { Long, Short }
public enum RouteResult { Filled, Skipped, Rejected, Unknown }
public enum ExecutionCommandState
{
    Intent, Prechecked, Submitting, SubmitUnknown, Submitted, Acknowledged, PartiallyFilled,
    Filled, StopConfirmed, ReconcileRequired, Closing, Final, Rejected
}

public sealed record Candle(long StartMs, double Open, double High, double Low, double Close, double Volume, double Turnover);

public sealed record CanonicalSignal(
    string SignalId,
    string SourceExchange,
    string Symbol,
    long SignalTimeMs,
    TradeDirection BaseSignalDirection,
    double OkxEntryRef,
    double OkxStopRef,
    double OkxRiskDistance,
    double OkxRiskDistancePct,
    double M15Atr,
    double Adx,
    double Score,
    string EntryReason,
    DateTimeOffset CreatedAt)
{
    public TradeDirection ActualDirection => BaseSignalDirection == TradeDirection.Long ? TradeDirection.Short : TradeDirection.Long;
}

public sealed record StrategyDecision(CanonicalSignal? Signal, string Reason);

public sealed record StrategyChartPoint(
    long StartMs,
    double Open,
    double High,
    double Low,
    double Close,
    double? EmaFast,
    double? EmaSlow);

public sealed record StrategyChartSnapshot(
    string Symbol,
    DateTimeOffset EvaluatedAt,
    string Decision,
    CanonicalSignal? Signal,
    double? H1EmaFast,
    double? H1EmaSlow,
    double? H1EmaFastThen,
    double? H1Close,
    double? Adx,
    double AdxMinimum,
    double? M15Atr,
    double AtrStopMultiplier,
    bool H1TrendPassed,
    bool PullbackPassed,
    bool ConfirmationPassed,
    IReadOnlyList<StrategyChartPoint> Points);

public sealed record MarketQuote(string Symbol, decimal Bid, decimal Ask, decimal Mark, DateTimeOffset ObservedAt)
{
    public bool IsStale(TimeSpan maximumAge, DateTimeOffset now) => now - ObservedAt > maximumAge;
}

public sealed record InstrumentRules(
    string Symbol,
    decimal TickSize,
    decimal QtyStep,
    decimal MinQty,
    decimal MaxMarketQty,
    decimal MinNotional,
    decimal ContractValue = 1m);

public sealed record RouteAttempt(
    ExchangeId Exchange,
    string SignalId,
    DateTimeOffset ReceivedAt,
    DateTimeOffset? SubmittedAt,
    DateTimeOffset? FilledAt,
    RouteResult Result,
    string Reason,
    decimal? EntryPrice = null,
    decimal? Quantity = null,
    string? OrderId = null)
{
    public double? ExecutionLatencyMs => FilledAt is null ? null : (FilledAt.Value - ReceivedAt).TotalMilliseconds;
}

public sealed record ExecutionCommand(
    long Id,
    ExchangeId Exchange,
    string SignalId,
    string ClientOrderId,
    string Symbol,
    TradeDirection Direction,
    string? PlanJson,
    ExecutionCommandState State,
    string? OrderId,
    string? StopOrderId,
    string? LastError,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt);

public sealed record RiskAction(
    long Id,
    ExchangeId Exchange,
    string SignalId,
    string ActionKey,
    string Kind,
    decimal? RequestedStop,
    string State,
    string? ExchangeOrderId,
    string? Error,
    DateTimeOffset CreatedAt,
    DateTimeOffset UpdatedAt);

public sealed record ManagedPosition(
    ExchangeId Exchange,
    string SignalId,
    string Symbol,
    TradeDirection Direction,
    decimal EntryPrice,
    decimal MarkPrice,
    decimal Quantity,
    decimal RemainingQuantity,
    decimal ContractValue,
    decimal EntryFee,
    decimal TakerFeeRate,
    decimal Spread,
    decimal PeakNetProfitUsdt,
    decimal ProtectedNetProfitUsdt,
    decimal MirroredStrategyStop,
    decimal HardLossStop,
    decimal CurrentStop,
    decimal TickSize,
    DateTimeOffset OpenedAt,
    string State = "OPEN");

public sealed record ExchangeSnapshot(
    ExchangeId Exchange,
    ExchangeMode Mode,
    bool PublicConnected,
    bool PrivateAuthenticated,
    bool TradingPermission,
    bool WithdrawPermission,
    string Health,
    decimal? Balance,
    decimal? Equity,
    decimal RealizedPnl,
    decimal UnrealizedPnl,
    int OpenPositionCount,
    string? LastSignalId,
    DateTimeOffset? LastActivity,
    string Detail);

public sealed class RuntimeSnapshot
{
    public DateTimeOffset StartedAt { get; } = DateTimeOffset.UtcNow;
    public string Version { get; init; } = "1.1.10";
    public volatile string MasterHealth = "STARTING";
    public volatile string MasterDetail = "initializing";
    public volatile string? LastSignalId;
    public DateTimeOffset? LastScanAt { get; set; }
    public DateTimeOffset? LastSignalAt { get; set; }
    public int UniverseCount { get; set; }
    public volatile string WriterExclusivity = "CHECKING";
    public volatile string WriterExclusivityDetail = "not checked";
    public ConcurrentDictionary<ExchangeId, ExchangeSnapshot> Exchanges { get; } = new();
    public ConcurrentDictionary<string, ManagedPosition> Positions { get; } = new();
    public ConcurrentDictionary<string, ExchangePositionTruth> ExternalPositions { get; } = new();
    public ConcurrentDictionary<ExchangeId, RouteAttempt> LastRouteAttempts { get; } = new();
    public ConcurrentDictionary<string, StrategyChartSnapshot> StrategyCharts { get; } =
        new(StringComparer.OrdinalIgnoreCase);
}

public sealed class RuntimeOptions
{
    public const string SectionName = "Runtime";
    public bool TradingEnabled { get; set; }
    // Environment-controlled only. A quiet order-history window is not proof that a legacy writer is gone.
    public bool OkxExclusiveWriterConfirmed { get; set; }
    public decimal RiskUsdt { get; set; } = 3m;
    public int Leverage { get; set; } = 5;
    public decimal MaxNotionalUsdt { get; set; } = 1000m;
    public decimal MaxCostR { get; set; } = 0.25m;
    public decimal MaxNetLossUsdt { get; set; } = 0.50m;
    public int UniverseSize { get; set; } = 30;
    public decimal MinTurnoverUsdt { get; set; } = 5_000_000m;
    public int SignalStaleSeconds { get; set; } = 180;
    public int MaxConcurrentSignals { get; set; } = 3;
    public decimal ExecutionSlippageBufferBps { get; set; } = 2m;
    public string DataDirectory { get; set; } = "data";
    public Dictionary<string, ExchangeMode> Exchanges { get; set; } = Enum.GetNames<ExchangeId>()
        .ToDictionary(x => x, _ => ExchangeMode.NotConfigured, StringComparer.OrdinalIgnoreCase);
}
