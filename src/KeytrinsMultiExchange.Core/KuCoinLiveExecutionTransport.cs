using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace KeytrinsMultiExchange.Core;

public sealed class KuCoinLiveExecutionTransport(HttpClient http, Func<ExchangeId, ExchangeCredentials> credentials)
    : ILiveExecutionTransport
{
    private const string BaseUrl = "https://api-futures.kucoin.com";
    private const string ApiVersion = "3";
    public ExchangeId Id => ExchangeId.KuCoinFutures;
    private ExchangeCredentials Credentials => credentials(Id);

    public async Task<PreparedEntry> PrepareEntryAsync(CanonicalSignal signal, RuntimeOptions options,
        CancellationToken cancellationToken)
    {
        var symbol = ExchangeSymbolMapper.Map(Id, signal.Symbol);
        using var positionsDocument = await PrivateGetAsync("/api/v1/positions?currency=USDT", cancellationToken);
        var allPositions = positionsDocument.RootElement.GetProperty("data");
        if (allPositions.ValueKind == JsonValueKind.Array && allPositions.EnumerateArray().Any(x =>
                Text(x, "symbol") == symbol && (Math.Abs(Decimal(x, "currentQty")) > 0 || Math.Abs(Decimal(x, "size")) > 0)))
            throw new ExecutionRejectedException("POSITION_ALREADY_OPEN");

        using var modeDocument = await PrivateGetAsync("/api/v2/position/getPositionMode", cancellationToken);
        if (Integer(modeDocument.RootElement.GetProperty("data"), "positionMode") != 0)
            throw new ExecutionRejectedException("ACCOUNT_MODE");
        using var instrumentDocument = await PublicGetAsync("/api/v1/contracts/" + Escape(symbol), cancellationToken);
        var instrument = instrumentDocument.RootElement.GetProperty("data");
        if (!Text(instrument, "quoteCurrency").Equals("USDT", StringComparison.OrdinalIgnoreCase) ||
            !Text(instrument, "settleCurrency").Equals("USDT", StringComparison.OrdinalIgnoreCase) ||
            Boolean(instrument, "isInverse") || !Boolean(instrument, "supportCross") ||
            !Text(instrument, "marketStage").Equals("NORMAL", StringComparison.OrdinalIgnoreCase))
            throw new ExecutionRejectedException("SYMBOL_NOT_AVAILABLE");
        var rules = new InstrumentRules(symbol, Positive(instrument, "tickSize"), Positive(instrument, "lotSize", 1m),
            Positive(instrument, "lotSize", 1m), Positive(instrument, "marketMaxOrderQty", 999999999m), 0m,
            Positive(instrument, "multiplier"));

        using var tickerDocument = await PublicGetAsync("/api/v1/ticker?symbol=" + Escape(symbol), cancellationToken);
        var ticker = tickerDocument.RootElement.GetProperty("data");
        var bid = Decimal(ticker, "bestBidPrice"); var ask = Decimal(ticker, "bestAskPrice");
        var mark = Decimal(instrument, "markPrice");
        var quote = new MarketQuote(symbol, bid, ask, mark > 0 ? mark : (bid + ask) / 2m, DateTimeOffset.UtcNow);

        using var feeDocument = await PrivateGetAsync("/api/v1/trade-fees?symbol=" + Escape(symbol), cancellationToken);
        var fee = Math.Abs(Decimal(feeDocument.RootElement.GetProperty("data"), "takerFeeRate"));
        var entry = EntryPlanner.Plan(Id, signal, symbol, quote, rules, fee, options);
        using var balanceDocument = await PrivateGetAsync("/api/v1/account-overview?currency=USDT", cancellationToken);
        var available = Decimal(balanceDocument.RootElement.GetProperty("data"), "availableBalance");
        ExecutionBudget.RequireAvailableMargin(entry, options, available);
        return entry;
    }

    public async Task<MutationReceipt> SubmitEntryAsync(PreparedEntry entry, CancellationToken cancellationToken)
    {
        await EnsureCrossAndLeverageAsync(entry, cancellationToken);
        var body = EntryWithStopBody(entry);
        using var response = await PrivatePostAsync("/api/v1/st-orders", body, true, cancellationToken);
        var orderId = Text(response.RootElement.GetProperty("data"), "orderId");
        return new(MutationDisposition.Accepted, orderId, null, "ENTRY_WITH_EXCHANGE_STOP_ACCEPTED", DateTimeOffset.UtcNow);
    }

    public async Task<ReconciliationTruth> ReconcileEntryAsync(string symbol, string clientOrderId,
        CancellationToken cancellationToken)
    {
        ExchangeOrderTruth order;
        try
        {
            using var orderDocument = await PrivateGetAsync("/api/v1/orders/byClientOid?clientOid=" + Escape(clientOrderId), cancellationToken);
            var item = orderDocument.RootElement.GetProperty("data");
            var status = Text(item, "status");
            var state = status switch
            {
                "done" when Decimal(item, "filledSize") > 0 => ExchangeOrderState.Filled,
                "open" when Decimal(item, "filledSize") > 0 => ExchangeOrderState.PartiallyFilled,
                "open" => ExchangeOrderState.Open,
                "cancelled" or "canceled" => ExchangeOrderState.Cancelled,
                _ => ExchangeOrderState.Unknown
            };
            var orderId = Text(item, "id");
            var fee = await GetOrderFeeAsync(orderId, cancellationToken);
            order = new(state, orderId, clientOrderId, Decimal(item, "filledSize"), Decimal(item, "avgDealPrice"), fee, status);
        }
        catch (ExchangeApiException exception) when (exception.Code.Contains("404", StringComparison.OrdinalIgnoreCase) ||
                                                      exception.Code.Contains("300004", StringComparison.OrdinalIgnoreCase) ||
                                                      exception.Code.Contains("100001", StringComparison.OrdinalIgnoreCase) &&
                                                      (exception.Detail?.Contains("orderNotExist", StringComparison.OrdinalIgnoreCase) ?? false))
        { order = new(ExchangeOrderState.Missing, null, clientOrderId, 0, 0, 0, "ORDER_NOT_FOUND"); }
        var position = await GetPositionAsync(symbol, cancellationToken);
        return new(order, position);
    }

    public async Task<MutationReceipt> ReplaceStopAsync(ExchangePositionTruth position, decimal stopPrice,
        string clientActionId, CancellationToken cancellationToken)
    {
        var stopBody = StopBody(clientActionId, position, stopPrice, position.Leverage);
        using var response = await PrivatePostAsync("/api/v1/orders", stopBody, true, cancellationToken);
        var newId = Text(response.RootElement.GetProperty("data"), "orderId");
        if (!string.IsNullOrWhiteSpace(position.StopOrderId))
        {
            try { using var _ = await PrivateDeleteAsync("/api/v1/orders/" + Escape(position.StopOrderId), true, cancellationToken); }
            catch (AmbiguousMutationException) { }
            catch (ExchangeApiException) { }
        }
        return new(MutationDisposition.Accepted, null, newId, "STOP_REPLACED", DateTimeOffset.UtcNow);
    }

    public async Task<MutationReceipt> CloseReduceOnlyAsync(ExchangePositionTruth position, string clientActionId,
        CancellationToken cancellationToken)
    {
        var body = OrderBody(clientActionId, position.Symbol,
            position.Direction == TradeDirection.Long ? TradeDirection.Short : TradeDirection.Long,
            position.Quantity, 1, true);
        using var response = await PrivatePostAsync("/api/v1/orders", body, true, cancellationToken);
        return new(MutationDisposition.Accepted, Text(response.RootElement.GetProperty("data"), "orderId"), null,
            "CLOSE_ACCEPTED", DateTimeOffset.UtcNow);
    }

    public async Task<IReadOnlyList<ExchangePositionTruth>> GetOpenPositionsAsync(CancellationToken cancellationToken)
    {
        using var document = await PrivateGetAsync("/api/v1/positions?currency=USDT", cancellationToken);
        var output = new List<ExchangePositionTruth>(); var data = document.RootElement.GetProperty("data");
        if (data.ValueKind != JsonValueKind.Array) return output;
        foreach (var item in data.EnumerateArray())
        {
            var signed = Decimal(item, "currentQty"); if (signed == 0) signed = Decimal(item, "size"); if (signed == 0) continue;
            var symbol = Text(item, "symbol"); var stop = await FindStopAsync(symbol, signed > 0 ? TradeDirection.Long : TradeDirection.Short, cancellationToken);
            output.Add(new(symbol, signed > 0 ? TradeDirection.Long : TradeDirection.Short, Math.Abs(signed),
                Decimal(item, "avgEntryPrice") is > 0 and var avg ? avg : Decimal(item, "entryPrice"),
                Decimal(item, "markPrice"), stop.Price, stop.Id, true, DateTimeOffset.UtcNow,
                Math.Max(1, Integer(item, "realLeverage") is > 0 and var lev ? lev : Integer(item, "leverage"))));
        }
        return output;
    }

    private async Task EnsureCrossAndLeverageAsync(PreparedEntry entry, CancellationToken cancellationToken)
    {
        using var modeDocument = await PrivateGetAsync("/api/v2/position/getMarginMode?symbol=" + Escape(entry.Symbol), cancellationToken);
        if (!Text(modeDocument.RootElement.GetProperty("data"), "marginMode").Equals("CROSS", StringComparison.OrdinalIgnoreCase))
        {
            var modeBody = JsonSerializer.Serialize(new Dictionary<string, object> { ["symbol"] = entry.Symbol, ["marginMode"] = "CROSS" });
            using var _ = await PrivatePostAsync("/api/v2/position/changeMarginMode", modeBody, false, cancellationToken);
        }
        var leverageBody = JsonSerializer.Serialize(new Dictionary<string, object>
        { ["symbol"] = entry.Symbol, ["leverage"] = entry.Leverage.ToString(CultureInfo.InvariantCulture) });
        using var __ = await PrivatePostAsync("/api/v2/changeCrossUserLeverage", leverageBody, false, cancellationToken);
    }

    private async Task<ExchangePositionTruth?> GetPositionAsync(string symbol, CancellationToken cancellationToken)
    {
        using var document = await PrivateGetAsync("/api/v2/position?symbol=" + Escape(symbol), cancellationToken);
        var data = document.RootElement.GetProperty("data");
        var items = data.ValueKind == JsonValueKind.Array ? data.EnumerateArray().ToArray() : new[] { data };
        foreach (var item in items)
        {
            var signed = Decimal(item, "currentQty"); if (signed == 0) signed = Decimal(item, "size"); if (signed == 0) continue;
            var direction = signed > 0 ? TradeDirection.Long : TradeDirection.Short; var stop = await FindStopAsync(symbol, direction, cancellationToken);
            return new(symbol, direction, Math.Abs(signed), Decimal(item, "avgEntryPrice") is > 0 and var avg ? avg : Decimal(item, "entryPrice"),
                Decimal(item, "markPrice"), stop.Price, stop.Id, true, DateTimeOffset.UtcNow,
                Math.Max(1, Integer(item, "realLeverage") is > 0 and var lev ? lev : Integer(item, "leverage")));
        }
        return null;
    }

    private async Task<(string? Id, decimal Price)> FindStopAsync(string symbol, TradeDirection direction,
        CancellationToken cancellationToken)
    {
        using var document = await PrivateGetAsync($"/api/v1/stopOrders?symbol={Escape(symbol)}&pageSize=50", cancellationToken);
        var data = document.RootElement.GetProperty("data");
        var items = data.TryGetProperty("items", out var list) ? list : data;
        if (items.ValueKind != JsonValueKind.Array) return (null, 0);
        var closingSide = direction == TradeDirection.Long ? "sell" : "buy";
        foreach (var item in items.EnumerateArray())
            if (Text(item, "side").Equals(closingSide, StringComparison.OrdinalIgnoreCase) &&
                (Boolean(item, "reduceOnly") || Boolean(item, "closeOrder")) && Decimal(item, "stopPrice") > 0)
                return (Text(item, "id"), Decimal(item, "stopPrice"));
        return (null, 0);
    }

    private async Task<decimal> GetOrderFeeAsync(string orderId, CancellationToken cancellationToken)
    {
        if (orderId.Length == 0) return 0;
        using var document = await PrivateGetAsync("/api/v1/fills?orderId=" + Escape(orderId) + "&pageSize=50", cancellationToken);
        var data = document.RootElement.GetProperty("data"); if (!data.TryGetProperty("items", out var items)) return 0;
        return items.EnumerateArray().Sum(x => Math.Abs(Decimal(x, "fee")));
    }

    private static string OrderBody(string clientId, string symbol, TradeDirection direction, decimal quantity,
        int leverage, bool reduceOnly) => JsonSerializer.Serialize(new Dictionary<string, object>
    {
        ["clientOid"] = clientId, ["symbol"] = symbol, ["marginMode"] = "CROSS", ["leverage"] = leverage,
        ["positionSide"] = "BOTH", ["side"] = direction == TradeDirection.Long ? "buy" : "sell",
        ["type"] = "market", ["size"] = IntegralOrDecimal(quantity), ["reduceOnly"] = reduceOnly
    });

    private static string EntryWithStopBody(PreparedEntry entry)
    {
        var body = new Dictionary<string, object>
        {
            ["clientOid"] = entry.ClientOrderId, ["symbol"] = entry.Symbol, ["marginMode"] = "CROSS",
            ["leverage"] = entry.Leverage, ["positionSide"] = "BOTH",
            ["side"] = entry.Direction == TradeDirection.Long ? "buy" : "sell", ["type"] = "market",
            ["size"] = IntegralOrDecimal(entry.Quantity), ["reduceOnly"] = false, ["stopPriceType"] = "MP"
        };
        body[entry.Direction == TradeDirection.Long ? "triggerStopDownPrice" : "triggerStopUpPrice"] = Format(entry.InitialStop);
        return JsonSerializer.Serialize(body);
    }

    private static string StopBody(string clientId, ExchangePositionTruth position, decimal stopPrice, int leverage) =>
        JsonSerializer.Serialize(new Dictionary<string, object>
        {
            ["clientOid"] = clientId, ["symbol"] = position.Symbol, ["marginMode"] = "CROSS", ["leverage"] = leverage,
            ["positionSide"] = "BOTH", ["side"] = position.Direction == TradeDirection.Long ? "sell" : "buy",
            ["type"] = "market", ["size"] = IntegralOrDecimal(position.Quantity), ["reduceOnly"] = true,
            ["stop"] = position.Direction == TradeDirection.Long ? "down" : "up", ["stopPriceType"] = "MP",
            ["stopPrice"] = Format(stopPrice)
        });

    private async Task<JsonDocument> PublicGetAsync(string endpoint, CancellationToken cancellationToken)
    {
        using var response = await http.GetAsync(BaseUrl + endpoint, cancellationToken);
        return await ParseAsync(response, false, cancellationToken);
    }

    private async Task<JsonDocument> PrivateGetAsync(string endpoint, CancellationToken cancellationToken)
    {
        using var request = Signed(HttpMethod.Get, endpoint, ""); using var response = await http.SendAsync(request, cancellationToken);
        return await ParseAsync(response, false, cancellationToken);
    }

    private async Task<JsonDocument> PrivatePostAsync(string endpoint, string body, bool ambiguous,
        CancellationToken cancellationToken)
    {
        using var request = Signed(HttpMethod.Post, endpoint, body); request.Content = new StringContent(body, Encoding.UTF8, "application/json");
        HttpResponseMessage response;
        try { response = await http.SendAsync(request, cancellationToken); }
        catch (Exception exception) when (ambiguous && exception is HttpRequestException or TaskCanceledException)
        { throw new AmbiguousMutationException("KUCOIN_NETWORK", exception); }
        using (response) return await ParseAsync(response, ambiguous, cancellationToken);
    }

    private async Task<JsonDocument> PrivateDeleteAsync(string endpoint, bool ambiguous, CancellationToken cancellationToken)
    {
        using var request = Signed(HttpMethod.Delete, endpoint, ""); HttpResponseMessage response;
        try { response = await http.SendAsync(request, cancellationToken); }
        catch (Exception exception) when (ambiguous && exception is HttpRequestException or TaskCanceledException)
        { throw new AmbiguousMutationException("KUCOIN_NETWORK", exception); }
        using (response) return await ParseAsync(response, ambiguous, cancellationToken);
    }

    private HttpRequestMessage Signed(HttpMethod method, string endpoint, string body)
    {
        var timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds().ToString(CultureInfo.InvariantCulture);
        var request = new HttpRequestMessage(method, BaseUrl + endpoint);
        request.Headers.TryAddWithoutValidation("KC-API-KEY", Credentials.ApiKey);
        request.Headers.TryAddWithoutValidation("KC-API-SIGN", Hmac(timestamp + method.Method.ToUpperInvariant() + endpoint + body));
        request.Headers.TryAddWithoutValidation("KC-API-TIMESTAMP", timestamp);
        request.Headers.TryAddWithoutValidation("KC-API-PASSPHRASE", Hmac(Credentials.Passphrase));
        request.Headers.TryAddWithoutValidation("KC-API-KEY-VERSION", ApiVersion);
        return request;
    }

    private async Task<JsonDocument> ParseAsync(HttpResponseMessage response, bool ambiguous, CancellationToken cancellationToken)
    {
        var body = await response.Content.ReadAsStringAsync(cancellationToken);
        if (ambiguous && (int)response.StatusCode >= 500) throw new AmbiguousMutationException("KUCOIN_HTTP_5XX");
        var document = JsonDocument.Parse(body); var code = Text(document.RootElement, "code");
        if (response.IsSuccessStatusCode && code == "200000") return document;
        var detail = Text(document.RootElement, "msg");
        document.Dispose(); throw new ExchangeApiException("KUCOIN_" + code, detail);
    }

    private string Hmac(string value)
    {
        using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(Credentials.ApiSecret));
        return Convert.ToBase64String(hmac.ComputeHash(Encoding.UTF8.GetBytes(value)));
    }

    private static object IntegralOrDecimal(decimal value) => value == decimal.Truncate(value) ? decimal.ToInt64(value) : value;
    private static string Text(JsonElement item, string property) => item.TryGetProperty(property, out var value)
        ? value.ValueKind == JsonValueKind.String ? value.GetString() ?? "" : value.ToString() : "";
    private static decimal Decimal(JsonElement item, string property) => decimal.TryParse(Text(item, property), NumberStyles.Float,
        CultureInfo.InvariantCulture, out var value) ? value : 0m;
    private static int Integer(JsonElement item, string property) => int.TryParse(Text(item, property), NumberStyles.Integer,
        CultureInfo.InvariantCulture, out var value) ? value : 0;
    private static bool Boolean(JsonElement item, string property) => item.TryGetProperty(property, out var value) &&
        (value.ValueKind == JsonValueKind.True || value.ValueKind == JsonValueKind.String && bool.TryParse(value.GetString(), out var parsed) && parsed);
    private static decimal Positive(JsonElement item, string property, decimal fallback = 0m) => Decimal(item, property) is > 0 and var value ? value : fallback;
    private static string Escape(string value) => Uri.EscapeDataString(value);
    private static string Format(decimal value) => value.ToString("0.############################", CultureInfo.InvariantCulture);
}
