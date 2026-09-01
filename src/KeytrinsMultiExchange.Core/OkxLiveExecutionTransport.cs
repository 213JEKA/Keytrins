using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace KeytrinsMultiExchange.Core;

public sealed class OkxLiveExecutionTransport(
    HttpClient http,
    Func<ExchangeId, ExchangeCredentials> credentials) : ILiveExecutionTransport, IWriterAuditSource
{
    private const string BaseUrl = "https://www.okx.com";
    public ExchangeId Id => ExchangeId.Okx;
    private ExchangeCredentials Credentials => credentials(Id);

    public async Task<IReadOnlyList<Dictionary<string, string>>> GetRecentOrderAuditAsync(string? symbol,
        CancellationToken cancellationToken)
    {
        var path = "/api/v5/trade/orders-history?instType=SWAP" +
            (string.IsNullOrWhiteSpace(symbol) ? "" : "&instId=" + Escape(symbol)) + "&limit=100";
        using var document = await PrivateGetAsync(path, cancellationToken);
        var output = new List<Dictionary<string, string>>();
        foreach (var item in document.RootElement.GetProperty("data").EnumerateArray())
        {
            output.Add(new(StringComparer.OrdinalIgnoreCase)
            {
                ["instId"] = Text(item, "instId"), ["ordId"] = Text(item, "ordId"), ["clOrdId"] = Text(item, "clOrdId"),
                ["side"] = Text(item, "side"), ["posSide"] = Text(item, "posSide"),
                ["state"] = Text(item, "state"), ["avgPx"] = Text(item, "avgPx"),
                ["accFillSz"] = Text(item, "accFillSz"), ["reduceOnly"] = Text(item, "reduceOnly"),
                ["fee"] = Text(item, "fee"), ["ordType"] = Text(item, "ordType"),
                ["source"] = Text(item, "source"), ["category"] = Text(item, "category"),
                ["tag"] = Text(item, "tag"), ["algoClOrdId"] = Text(item, "algoClOrdId"),
                ["cTime"] = Text(item, "cTime"), ["uTime"] = Text(item, "uTime")
            });
        }
        return output;
    }

    public async Task<PreparedEntry> PrepareEntryAsync(CanonicalSignal signal, RuntimeOptions options,
        CancellationToken cancellationToken)
    {
        var symbol = signal.Symbol;
        using var positions = await PrivateGetAsync("/api/v5/account/positions?instId=" + Escape(symbol), cancellationToken);
        if (positions.RootElement.GetProperty("data").EnumerateArray().Any(x => Math.Abs(Decimal(x, "pos")) > 0m))
            throw new ExecutionRejectedException("POSITION_ALREADY_OPEN");

        using var instrumentDocument = await PublicGetAsync("/api/v5/public/instruments?instType=SWAP&instId=" + Escape(symbol), cancellationToken);
        var instruments = instrumentDocument.RootElement.GetProperty("data");
        if (instruments.GetArrayLength() == 0) throw new ExecutionRejectedException("SYMBOL_NOT_AVAILABLE");
        var instrument = instruments[0];
        if (!Text(instrument, "state").Equals("live", StringComparison.OrdinalIgnoreCase) ||
            !Text(instrument, "ctType").Equals("linear", StringComparison.OrdinalIgnoreCase) ||
            !Text(instrument, "settleCcy").Equals("USDT", StringComparison.OrdinalIgnoreCase))
            throw new ExecutionRejectedException("SYMBOL_NOT_AVAILABLE");
        var rules = new InstrumentRules(symbol, Positive(instrument, "tickSz"), Positive(instrument, "lotSz"),
            Positive(instrument, "minSz"), Positive(instrument, "maxMktSz", 999999999m), 0m,
            Positive(instrument, "ctVal"));

        using var tickerDocument = await PublicGetAsync("/api/v5/market/ticker?instId=" + Escape(symbol), cancellationToken);
        var tickers = tickerDocument.RootElement.GetProperty("data");
        if (tickers.GetArrayLength() == 0) throw new ExecutionRejectedException("STALE_TARGET_MARKET");
        var ticker = tickers[0];
        var bid = Decimal(ticker, "bidPx"); var ask = Decimal(ticker, "askPx"); var last = Decimal(ticker, "last");
        var quote = new MarketQuote(symbol, bid, ask, last > 0 ? last : (bid + ask) / 2m, DateTimeOffset.UtcNow);

        var groupId = Text(instrument, "groupId");
        var instrumentFamily = Text(instrument, "instFamily");
        var feeSelector = groupId.Length > 0
            ? "&groupId=" + Escape(groupId)
            : "&instFamily=" + Escape(instrumentFamily);
        using var feeDocument = await PrivateGetAsync("/api/v5/account/trade-fee?instType=SWAP" + feeSelector, cancellationToken);
        var fees = feeDocument.RootElement.GetProperty("data");
        if (fees.GetArrayLength() == 0) throw new ExecutionRejectedException("FEE_RATE_UNAVAILABLE");
        var feeItem = fees[0];
        var fee = 0m;
        if (feeItem.TryGetProperty("feeGroup", out var feeGroups) && feeGroups.ValueKind == JsonValueKind.Array)
        {
            var selected = feeGroups.EnumerateArray().FirstOrDefault(x => groupId.Length == 0 || Text(x, "groupId") == groupId);
            if (selected.ValueKind == JsonValueKind.Object) fee = Math.Abs(Decimal(selected, "taker"));
        }
        if (fee <= 0m) fee = Math.Abs(Decimal(feeItem, "takerU"));
        if (fee <= 0m) fee = Math.Abs(Decimal(feeItem, "taker"));
        if (fee <= 0m) throw new ExecutionRejectedException("FEE_RATE_UNAVAILABLE");
        return EntryPlanner.Plan(Id, signal, symbol, quote, rules, fee, options);
    }

    public async Task<MutationReceipt> SubmitEntryAsync(PreparedEntry entry, CancellationToken cancellationToken)
    {
        var optionsLeverage = entry.Leverage;
        var leverageBody = JsonSerializer.Serialize(new Dictionary<string, object>
        {
            ["instId"] = entry.Symbol, ["lever"] = optionsLeverage.ToString(CultureInfo.InvariantCulture), ["mgnMode"] = "cross"
        });
        try { using var _ = await PrivatePostAsync("/api/v5/account/set-leverage", leverageBody, false, cancellationToken); }
        catch (Exception exception) when (exception is not OperationCanceledException || !cancellationToken.IsCancellationRequested)
        { throw new ExecutionRejectedException("LEVERAGE_SETUP_FAILED"); }

        var stopId = ExecutionIds.Stop(Id, entry.SignalId, 0);
        var body = JsonSerializer.Serialize(new Dictionary<string, object>
        {
            ["instId"] = entry.Symbol,
            ["tdMode"] = "cross",
            ["clOrdId"] = entry.ClientOrderId,
            ["side"] = entry.Direction == TradeDirection.Long ? "buy" : "sell",
            ["posSide"] = "net",
            ["ordType"] = "market",
            ["sz"] = Format(entry.Quantity),
            ["reduceOnly"] = false,
            ["attachAlgoOrds"] = new[] { new Dictionary<string, object>
            {
                ["attachAlgoClOrdId"] = stopId,
                ["slTriggerPx"] = Format(entry.InitialStop),
                ["slOrdPx"] = "-1",
                ["slTriggerPxType"] = "mark"
            }}
        });
        using var response = await PrivatePostAsync("/api/v5/trade/order", body, true, cancellationToken);
        var data = response.RootElement.GetProperty("data");
        if (data.GetArrayLength() == 0) return new(MutationDisposition.Rejected, null, null, "EMPTY_ORDER_RESPONSE", DateTimeOffset.UtcNow);
        var item = data[0]; var subCode = Text(item, "sCode");
        if (subCode.Length > 0 && subCode != "0")
        {
            var detail = Text(item, "sMsg");
            var reason = new ExchangeApiException("OKX_" + subCode, detail).Message;
            return new(MutationDisposition.Rejected, Text(item, "ordId"), null, reason, DateTimeOffset.UtcNow);
        }
        return new(MutationDisposition.Accepted, Text(item, "ordId"), stopId, "ACCEPTED", DateTimeOffset.UtcNow);
    }

    public async Task<ReconciliationTruth> ReconcileEntryAsync(string symbol, string clientOrderId,
        CancellationToken cancellationToken)
    {
        ExchangeOrderTruth order;
        using (var orderDocument = await PrivateGetAsync($"/api/v5/trade/order?instId={Escape(symbol)}&clOrdId={Escape(clientOrderId)}", cancellationToken))
        {
            var data = orderDocument.RootElement.GetProperty("data");
            if (data.GetArrayLength() == 0) order = new(ExchangeOrderState.Missing, null, clientOrderId, 0, 0, 0, "ORDER_NOT_FOUND");
            else
            {
                var item = data[0];
                var state = Text(item, "state") switch
                {
                    "filled" => ExchangeOrderState.Filled,
                    "partially_filled" => ExchangeOrderState.PartiallyFilled,
                    "live" => ExchangeOrderState.Open,
                    "canceled" => ExchangeOrderState.Cancelled,
                    _ => ExchangeOrderState.Unknown
                };
                order = new(state, Text(item, "ordId"), clientOrderId, Decimal(item, "accFillSz"),
                    Decimal(item, "avgPx"), Math.Abs(Decimal(item, "fee")), Text(item, "state"));
            }
        }

        ExchangePositionTruth? position = null;
        using (var positionDocument = await PrivateGetAsync("/api/v5/account/positions?instId=" + Escape(symbol), cancellationToken))
        {
            foreach (var item in positionDocument.RootElement.GetProperty("data").EnumerateArray())
            {
                var signed = Decimal(item, "pos"); if (signed == 0) continue;
                decimal stop = 0m; string? stopId = null;
                if (item.TryGetProperty("closeOrderAlgo", out var algos) && algos.ValueKind == JsonValueKind.Array && algos.GetArrayLength() > 0)
                { stop = Decimal(algos[0], "slTriggerPx"); stopId = Text(algos[0], "algoId"); }
                position = new(symbol, signed > 0 ? TradeDirection.Long : TradeDirection.Short, Math.Abs(signed),
                    Decimal(item, "avgPx"), Decimal(item, "markPx"), stop, stopId,
                    Text(item, "posSide").Equals("net", StringComparison.OrdinalIgnoreCase), DateTimeOffset.UtcNow);
                break;
            }
        }
        return new(order, position);
    }

    public async Task<MutationReceipt> ReplaceStopAsync(ExchangePositionTruth position, decimal stopPrice,
        string clientActionId, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(position.StopOrderId))
        {
            var createBody = JsonSerializer.Serialize(new Dictionary<string, object>
            {
                ["instId"] = position.Symbol, ["tdMode"] = "cross", ["algoClOrdId"] = clientActionId,
                ["side"] = position.Direction == TradeDirection.Long ? "sell" : "buy", ["posSide"] = "net",
                ["ordType"] = "conditional", ["sz"] = Format(position.Quantity), ["reduceOnly"] = true,
                ["slTriggerPx"] = Format(stopPrice), ["slOrdPx"] = "-1", ["slTriggerPxType"] = "mark"
            });
            using var created = await PrivatePostAsync("/api/v5/trade/order-algo", createBody, true, cancellationToken);
            var createdItem = created.RootElement.GetProperty("data")[0]; var createdCode = Text(createdItem, "sCode");
            return createdCode is "" or "0"
                ? new(MutationDisposition.Accepted, null, Text(createdItem, "algoId"), "STOP_CREATED", DateTimeOffset.UtcNow)
                : new(MutationDisposition.Rejected, null, null, "OKX_" + createdCode, DateTimeOffset.UtcNow);
        }
        var body = JsonSerializer.Serialize(new[] { new Dictionary<string, object>
        {
            ["instId"] = position.Symbol, ["algoId"] = position.StopOrderId,
            ["newSlTriggerPx"] = Format(stopPrice), ["newSlOrdPx"] = "-1", ["newSlTriggerPxType"] = "mark"
        }});
        using var response = await PrivatePostAsync("/api/v5/trade/amend-algos", body, true, cancellationToken);
        var item = response.RootElement.GetProperty("data")[0]; var code = Text(item, "sCode");
        return code is "" or "0"
            ? new(MutationDisposition.Accepted, null, position.StopOrderId, "STOP_AMENDED", DateTimeOffset.UtcNow)
            : new(MutationDisposition.Rejected, null, position.StopOrderId, "OKX_" + code, DateTimeOffset.UtcNow);
    }

    public async Task<MutationReceipt> CloseReduceOnlyAsync(ExchangePositionTruth position, string clientActionId,
        CancellationToken cancellationToken)
    {
        var body = JsonSerializer.Serialize(new Dictionary<string, object>
        {
            ["instId"] = position.Symbol, ["tdMode"] = "cross", ["clOrdId"] = clientActionId,
            ["side"] = position.Direction == TradeDirection.Long ? "sell" : "buy", ["posSide"] = "net",
            ["ordType"] = "market", ["sz"] = Format(position.Quantity), ["reduceOnly"] = true
        });
        using var response = await PrivatePostAsync("/api/v5/trade/order", body, true, cancellationToken);
        var item = response.RootElement.GetProperty("data")[0]; var code = Text(item, "sCode");
        return code is "" or "0"
            ? new(MutationDisposition.Accepted, Text(item, "ordId"), null, "CLOSE_ACCEPTED", DateTimeOffset.UtcNow)
            : new(MutationDisposition.Rejected, Text(item, "ordId"), null, "OKX_" + code, DateTimeOffset.UtcNow);
    }

    public async Task<IReadOnlyList<ExchangePositionTruth>> GetOpenPositionsAsync(CancellationToken cancellationToken)
    {
        using var document = await PrivateGetAsync("/api/v5/account/positions?instType=SWAP", cancellationToken);
        var output = new List<ExchangePositionTruth>();
        foreach (var item in document.RootElement.GetProperty("data").EnumerateArray())
        {
            var signed = Decimal(item, "pos"); if (signed == 0 || !Text(item, "instId").EndsWith("-USDT-SWAP")) continue;
            decimal stop = 0; string? stopId = null;
            if (item.TryGetProperty("closeOrderAlgo", out var algos) && algos.ValueKind == JsonValueKind.Array && algos.GetArrayLength() > 0)
            { stop = Decimal(algos[0], "slTriggerPx"); stopId = Text(algos[0], "algoId"); }
            output.Add(new(Text(item, "instId"), signed > 0 ? TradeDirection.Long : TradeDirection.Short, Math.Abs(signed),
                Decimal(item, "avgPx"), Decimal(item, "markPx"), stop, stopId, Text(item, "posSide") == "net", DateTimeOffset.UtcNow));
        }
        return output;
    }

    private async Task<JsonDocument> PublicGetAsync(string path, CancellationToken cancellationToken)
    {
        using var response = await http.GetAsync(BaseUrl + path, cancellationToken);
        var body = await response.Content.ReadAsStringAsync(cancellationToken); var document = JsonDocument.Parse(body);
        var code = Text(document.RootElement, "code");
        if (response.IsSuccessStatusCode && code == "0") return document;
        var error = ReadError(document.RootElement);
        document.Dispose(); throw error;
    }

    private async Task<JsonDocument> PrivateGetAsync(string path, CancellationToken cancellationToken)
    {
        var timestamp = DateTimeOffset.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture);
        using var request = Signed(HttpMethod.Get, path, "", timestamp);
        using var response = await http.SendAsync(request, cancellationToken);
        var body = await response.Content.ReadAsStringAsync(cancellationToken); var document = JsonDocument.Parse(body);
        var code = Text(document.RootElement, "code");
        if (response.IsSuccessStatusCode && code == "0") return document;
        var error = ReadError(document.RootElement);
        document.Dispose(); throw error;
    }

    private async Task<JsonDocument> PrivatePostAsync(string path, string body, bool ambiguousOnFailure,
        CancellationToken cancellationToken)
    {
        var timestamp = DateTimeOffset.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture);
        using var request = Signed(HttpMethod.Post, path, body, timestamp);
        request.Content = new StringContent(body, Encoding.UTF8, "application/json");
        HttpResponseMessage response;
        try { response = await http.SendAsync(request, cancellationToken); }
        catch (Exception exception) when (ambiguousOnFailure && exception is HttpRequestException or TaskCanceledException)
        { throw new AmbiguousMutationException("OKX_NETWORK", exception); }
        using (response)
        {
            var responseBody = await response.Content.ReadAsStringAsync(cancellationToken);
            if (ambiguousOnFailure && (int)response.StatusCode >= 500)
                throw new AmbiguousMutationException("OKX_HTTP_5XX");
            var document = JsonDocument.Parse(responseBody); var code = Text(document.RootElement, "code");
            if (response.IsSuccessStatusCode && code == "0") return document;
            var error = ReadError(document.RootElement);
            document.Dispose(); throw error;
        }
    }

    private static ExchangeApiException ReadError(JsonElement root)
    {
        var code = Text(root, "code");
        var detail = Text(root, "msg");
        if (root.TryGetProperty("data", out var data) && data.ValueKind == JsonValueKind.Array && data.GetArrayLength() > 0)
        {
            var item = data[0];
            var subCode = Text(item, "sCode");
            var subDetail = Text(item, "sMsg");
            if (!string.IsNullOrWhiteSpace(subCode) && subCode != "0")
                return new ExchangeApiException("OKX_" + subCode,
                    string.IsNullOrWhiteSpace(subDetail) ? detail : subDetail);
        }
        return new ExchangeApiException("OKX_" + code, detail);
    }

    private HttpRequestMessage Signed(HttpMethod method, string path, string body, string timestamp)
    {
        var request = new HttpRequestMessage(method, BaseUrl + path);
        request.Headers.TryAddWithoutValidation("OK-ACCESS-KEY", Credentials.ApiKey);
        request.Headers.TryAddWithoutValidation("OK-ACCESS-SIGN", Hmac(timestamp + method.Method.ToUpperInvariant() + path + body));
        request.Headers.TryAddWithoutValidation("OK-ACCESS-TIMESTAMP", timestamp);
        request.Headers.TryAddWithoutValidation("OK-ACCESS-PASSPHRASE", Credentials.Passphrase);
        return request;
    }

    private string Hmac(string value)
    {
        using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(Credentials.ApiSecret));
        return Convert.ToBase64String(hmac.ComputeHash(Encoding.UTF8.GetBytes(value)));
    }

    private static string Text(JsonElement item, string property) => item.TryGetProperty(property, out var value)
        ? value.ValueKind == JsonValueKind.String ? value.GetString() ?? "" : value.ToString() : "";
    private static decimal Decimal(JsonElement item, string property) => decimal.TryParse(Text(item, property), NumberStyles.Float,
        CultureInfo.InvariantCulture, out var value) ? value : 0m;
    private static decimal Positive(JsonElement item, string property, decimal fallback = 0m) => Decimal(item, property) is > 0 and var value ? value : fallback;
    private static string Escape(string value) => Uri.EscapeDataString(value).Replace("%20", "%20", StringComparison.Ordinal);
    private static string Format(decimal value) => value.ToString("0.############################", CultureInfo.InvariantCulture);
}
