using System.Globalization;
using System.Net.Http.Json;
using System.Text.Json;

namespace KeytrinsMultiExchange.Core;

public sealed record OkxUniverseInstrument(string InstrumentId, string BaseCurrency, decimal TickSize, decimal LotSize,
    decimal MinSize, decimal MaxMarketSize, decimal ContractValue, decimal Turnover24h);

public sealed class OkxMarketDataClient(HttpClient http)
{
    private readonly SemaphoreSlim _requestGate = new(1, 1);
    private DateTimeOffset _nextRequestAt = DateTimeOffset.MinValue;
    private static readonly HashSet<string> StableBases = new(StringComparer.OrdinalIgnoreCase)
    { "USDT", "USDC", "USDE", "FDUSD", "TUSD", "DAI", "USDD", "PYUSD", "USD1", "USDP" };

    public async Task<(DateTimeOffset ServerTime, TimeSpan Delta)> GetServerTimeAsync(CancellationToken cancellationToken)
    {
        using var document = await GetAsync("https://www.okx.com/api/v5/public/time", cancellationToken);
        var ts = document.RootElement.GetProperty("data")[0].GetProperty("ts").GetString()!;
        var server = DateTimeOffset.FromUnixTimeMilliseconds(long.Parse(ts, CultureInfo.InvariantCulture));
        return (server, server - DateTimeOffset.UtcNow);
    }

    public async Task<IReadOnlyList<OkxUniverseInstrument>> BuildUniverseAsync(int topN, decimal minTurnover,
        CancellationToken cancellationToken)
    {
        using var instrumentsDoc = await GetAsync("https://www.okx.com/api/v5/public/instruments?instType=SWAP", cancellationToken);
        using var tickersDoc = await GetAsync("https://www.okx.com/api/v5/market/tickers?instType=SWAP", cancellationToken);
        var turnover = new Dictionary<string, decimal>(StringComparer.OrdinalIgnoreCase);
        foreach (var item in tickersDoc.RootElement.GetProperty("data").EnumerateArray())
        {
            var instrumentId = Text(item, "instId");
            var last = Decimal(item, "last");
            var baseVolume = Decimal(item, "volCcy24h");
            turnover[instrumentId] = last > 0 && baseVolume > 0 ? last * baseVolume : 0;
        }

        var output = new List<OkxUniverseInstrument>();
        foreach (var item in instrumentsDoc.RootElement.GetProperty("data").EnumerateArray())
        {
            var instrumentId = Text(item, "instId");
            if (!instrumentId.EndsWith("-USDT-SWAP", StringComparison.OrdinalIgnoreCase) ||
                !Text(item, "settleCcy").Equals("USDT", StringComparison.OrdinalIgnoreCase) ||
                !Text(item, "ctType").Equals("linear", StringComparison.OrdinalIgnoreCase) ||
                !Text(item, "state").Equals("live", StringComparison.OrdinalIgnoreCase)) continue;
            var parts = instrumentId.Split('-');
            if (parts.Length < 3 || StableBases.Contains(parts[0])) continue;
            var ctVal = Decimal(item, "ctVal");
            if (ctVal <= 0) continue;
            var ctValCurrency = Text(item, "ctValCcy");
            if (ctValCurrency.Length > 0 && !ctValCurrency.Equals(parts[0], StringComparison.OrdinalIgnoreCase)) continue;
            var instrumentTurnover = turnover.GetValueOrDefault(instrumentId);
            if (instrumentTurnover < minTurnover) continue;
            output.Add(new(instrumentId, parts[0], Positive(item, "tickSz", 0.00000001m),
                Positive(item, "lotSz", 1m), Positive(item, "minSz", 1m),
                Positive(item, "maxMktSz", 999999999m), ctVal, instrumentTurnover));
        }
        return output.OrderByDescending(x => x.Turnover24h).ThenBy(x => x.InstrumentId, StringComparer.Ordinal)
            .Take(topN).ToArray();
    }

    public async Task<IReadOnlyList<Candle>> GetClosedCandlesAsync(string instrumentId, string bar, int limit,
        CancellationToken cancellationToken)
    {
        var url = $"https://www.okx.com/api/v5/market/candles?instId={Uri.EscapeDataString(instrumentId)}&bar={bar}&limit={Math.Clamp(limit, 1, 300)}";
        using var document = await GetAsync(url, cancellationToken);
        var output = new List<Candle>();
        foreach (var row in document.RootElement.GetProperty("data").EnumerateArray())
        {
            var cells = row.EnumerateArray().Select(x => x.GetString() ?? "0").ToArray();
            if (cells.Length > 8 && cells[8] != "1") continue;
            output.Add(new(long.Parse(cells[0], CultureInfo.InvariantCulture), Parse(cells[1]), Parse(cells[2]),
                Parse(cells[3]), Parse(cells[4]), cells.Length > 5 ? Parse(cells[5]) : 0,
                cells.Length > 7 ? Parse(cells[7]) : 0));
        }
        output.Sort((a, b) => a.StartMs.CompareTo(b.StartMs));
        return output;
    }

    private async Task<JsonDocument> GetAsync(string url, CancellationToken cancellationToken)
    {
        for (var attempt = 1; attempt <= 3; attempt++)
        {
            await ThrottleAsync(cancellationToken);
            using var response = await http.GetAsync(url, cancellationToken);
            var text = await response.Content.ReadAsStringAsync(cancellationToken);
            if ((int)response.StatusCode == 429)
            {
                if (attempt == 3) response.EnsureSuccessStatusCode();
                await Task.Delay(TimeSpan.FromSeconds(attempt * 2), cancellationToken);
                continue;
            }
            response.EnsureSuccessStatusCode();
            var document = JsonDocument.Parse(text);
            var code = document.RootElement.TryGetProperty("code", out var value) ? value.GetString() : "0";
            if (code == "0") return document;
            document.Dispose();
            if (code == "50011" && attempt < 3) { await Task.Delay(TimeSpan.FromSeconds(attempt * 2), cancellationToken); continue; }
            throw new HttpRequestException($"OKX public API code {code}");
        }
        throw new HttpRequestException("OKX public API retry budget exhausted");
    }

    private async Task ThrottleAsync(CancellationToken cancellationToken)
    {
        await _requestGate.WaitAsync(cancellationToken);
        try
        {
            var delay = _nextRequestAt - DateTimeOffset.UtcNow;
            if (delay > TimeSpan.Zero) await Task.Delay(delay, cancellationToken);
            _nextRequestAt = DateTimeOffset.UtcNow.AddMilliseconds(150);
        }
        finally { _requestGate.Release(); }
    }

    private static string Text(JsonElement item, string property) =>
        item.TryGetProperty(property, out var value) ? value.GetString() ?? string.Empty : string.Empty;
    private static decimal Decimal(JsonElement item, string property) => decimal.TryParse(Text(item, property),
        NumberStyles.Float, CultureInfo.InvariantCulture, out var value) ? value : 0m;
    private static decimal Positive(JsonElement item, string property, decimal fallback) => Decimal(item, property) is > 0m and var value ? value : fallback;
    private static double Parse(string value) => double.Parse(value, NumberStyles.Float, CultureInfo.InvariantCulture);
}
