using System.Globalization;

namespace KeytrinsMultiExchange.Core;

public sealed class ExternalWriterGuard(IWriterAuditSource source, bool operatorConfirmedExclusiveWriter = false)
{
    public static readonly TimeSpan QuietWindow = TimeSpan.FromMinutes(20);

    public async Task<WriterExclusivityStatus> CheckAsync(CancellationToken cancellationToken)
    {
        var now = DateTimeOffset.UtcNow;
        var cutoff = now - QuietWindow;
        var audit = await source.GetRecentOrderAuditAsync(null, cancellationToken);
        var foreign = audit
            // OKX app/manual orders have no client order id. They are deliberately outside this runtime's
            // ownership and must not globally pause it. A foreign API writer with its own client id still blocks.
            .Where(x => !IsReduceOnly(x) && !IsOurs(x) && !IsManual(x))
            .Select(x => new { Row = x, At = ParseTime(x) })
            .Where(x => x.At >= cutoff)
            .OrderByDescending(x => x.At)
            .FirstOrDefault();
        return foreign is null
            ? new(operatorConfirmedExclusiveWriter, now, null, null,
                operatorConfirmedExclusiveWriter
                    ? $"OPERATOR_CONFIRMED_EXCLUSIVE;NO_FOREIGN_ENTRY_IN_LAST_{(int)QuietWindow.TotalMinutes}_MIN"
                    : $"QUIET_WINDOW_PASS_NOT_EXCLUSIVE;NO_FOREIGN_ENTRY_IN_LAST_{(int)QuietWindow.TotalMinutes}_MIN")
            : new(false, now, foreign.At, Value(foreign.Row, "clOrdId"),
                $"FOREIGN_OKX_ENTRY:{Value(foreign.Row, "instId")}:{Value(foreign.Row, "clOrdId")}:{foreign.At:O}");
    }

    private static bool IsOurs(IReadOnlyDictionary<string, string> row) =>
        Value(row, "clOrdId").StartsWith("KX", StringComparison.OrdinalIgnoreCase);

    private static bool IsManual(IReadOnlyDictionary<string, string> row) =>
        string.IsNullOrWhiteSpace(Value(row, "clOrdId"));

    private static bool IsReduceOnly(IReadOnlyDictionary<string, string> row) =>
        Value(row, "reduceOnly").Equals("true", StringComparison.OrdinalIgnoreCase);

    private static DateTimeOffset ParseTime(IReadOnlyDictionary<string, string> row) =>
        long.TryParse(Value(row, "cTime"), NumberStyles.Integer, CultureInfo.InvariantCulture, out var value)
            ? DateTimeOffset.FromUnixTimeMilliseconds(value) : DateTimeOffset.MinValue;

    private static string Value(IReadOnlyDictionary<string, string> row, string key) =>
        row.TryGetValue(key, out var value) ? value : string.Empty;
}
