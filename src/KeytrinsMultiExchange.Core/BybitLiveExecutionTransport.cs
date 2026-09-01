using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace KeytrinsMultiExchange.Core;

public sealed class BybitLiveExecutionTransport(HttpClient http, Func<ExchangeId, ExchangeCredentials> credentials)
    : ILiveExecutionTransport
{
    private const string BaseUrl = "https://api.bybit.com";
    private const string ReceiveWindow = "5000";
    public ExchangeId Id => ExchangeId.Bybit;
    private ExchangeCredentials Credentials => credentials(Id);

    public async Task<PreparedEntry> PrepareEntryAsync(CanonicalSignal signal, RuntimeOptions options,
        CancellationToken cancellationToken)
    {
        var symbol = signal.Symbol.Split('-')[0] + "USDT";
        using var positionDocument = await PrivateGetAsync("/v5/position/list", $"category=linear&symbol={Escape(symbol)}", cancellationToken);
        var positions = positionDocument.RootElement.GetProperty("result").GetProperty("list");
        if (positions.EnumerateArray().Any(x => Decimal(x, "size") > 0m)) throw new ExecutionRejectedException("POSITION_ALREADY_OPEN");
        if (positions.GetArrayLength() == 0 || positions.EnumerateArray().Any(x => Integer(x, "positionIdx") != 0))
            throw new ExecutionRejectedException("ACCOUNT_MODE");

        using var instrumentDocument = await PublicGetAsync("/v5/market/instruments-info?category=linear&symbol=" + Escape(symbol), cancellationToken);
        var instruments = instrumentDocument.RootElement.GetProperty("result").GetProperty("list");
        if (instruments.GetArrayLength() == 0) throw new ExecutionRejectedException("SYMBOL_NOT_AVAILABLE");
        var instrument = instruments[0];
        if (!Text(instrument, "status").Equals("Trading", StringComparison.OrdinalIgnoreCase) ||
            !Text(instrument, "settleCoin").Equals("USDT", StringComparison.OrdinalIgnoreCase))
            throw new ExecutionRejectedException("SYMBOL_NOT_AVAILABLE");
        var priceFilter = instrument.GetProperty("priceFilter"); var lot = instrument.GetProperty("lotSizeFilter");
        var rules = new InstrumentRules(symbol, Positive(priceFilter, "tickSize"), Positive(lot, "qtyStep"),
            Positive(lot, "minOrderQty"), Positive(lot, "maxMktOrderQty", 999999999m),
            Positive(lot, "minNotionalValue"), 1m);

        using var tickerDocument = await PublicGetAsync("/v5/market/tickers?category=linear&symbol=" + Escape(symbol), cancellationToken);
        var tickers = tickerDocument.RootElement.GetProperty("result").GetProperty("list");
        if (tickers.GetArrayLength() == 0) throw new ExecutionRejectedException("STALE_TARGET_MARKET");
        var ticker = tickers[0]; var bid = Decimal(ticker, "bid1Price"); var ask = Decimal(ticker, "ask1Price");
        var mark = Decimal(ticker, "markPrice");
        var quote = new MarketQuote(symbol, bid, ask, mark > 0 ? mark : (bid + ask) / 2m, DateTimeOffset.UtcNow);

        using var feeDocument = await PrivateGetAsync("/v5/account/fee-rate", $"category=linear&symbol={Escape(symbol)}", cancellationToken);
        var fees = feeDocument.RootElement.GetProperty("result").GetProperty("list");
        if (fees.GetArrayLength() == 0) throw new ExecutionRejectedException("FEE_RATE_UNAVAILABLE");
        var fee = Math.Abs(Decimal(fees[0], "takerFeeRate"));
        return EntryPlanner.Plan(Id, signal, symbol, quote, rules, fee, options);
    }

    public async Task<MutationReceipt> SubmitEntryAsync(PreparedEntry entry, CancellationToken cancellationToken)
    {
        var leverage = entry.Leverage.ToString(CultureInfo.InvariantCulture);
        var leverageBody = JsonSerializer.Serialize(new Dictionary<string, object>
        {
            ["category"] = "linear", ["symbol"] = entry.Symbol,
            ["buyLeverage"] = leverage, ["sellLeverage"] = leverage
        });
        try { using var _ = await PrivatePostAsync("/v5/position/set-leverage", leverageBody, false, cancellationToken); }
        catch (ExchangeApiException exception) when (exception.Code is "BYBIT_110043" or "BYBIT_110025") { }
        catch (Exception exception) when (exception is not OperationCanceledException || !cancellationToken.IsCancellationRequested)
        { throw new ExecutionRejectedException("LEVERAGE_SETUP_FAILED"); }

        var body = JsonSerializer.Serialize(new Dictionary<string, object>
        {
            ["category"] = "linear", ["symbol"] = entry.Symbol,
            ["side"] = entry.Direction == TradeDirection.Long ? "Buy" : "Sell",
            ["orderType"] = "Market", ["qty"] = Format(entry.Quantity), ["positionIdx"] = 0,
            ["orderLinkId"] = entry.ClientOrderId, ["reduceOnly"] = false,
            ["stopLoss"] = Format(entry.InitialStop), ["slTriggerBy"] = "MarkPrice",
            ["tpslMode"] = "Full", ["slOrderType"] = "Market"
        });
        using var response = await PrivatePostAsync("/v5/order/create", body, true, cancellationToken);
        var result = response.RootElement.GetProperty("result");
        return new(MutationDisposition.Accepted, Text(result, "orderId"), null, "ACCEPTED", DateTimeOffset.UtcNow);
    }

    public async Task<ReconciliationTruth> ReconcileEntryAsync(string symbol, string clientOrderId,
        CancellationToken cancellationToken)
    {
        var order = await FindOrderAsync(symbol, clientOrderId, cancellationToken);
        ExchangePositionTruth? position = null;
        using var positionDocument = await PrivateGetAsync("/v5/position/list", $"category=linear&symbol={Escape(symbol)}", cancellationToken);
        foreach (var item in positionDocument.RootElement.GetProperty("result").GetProperty("list").EnumerateArray())
        {
            var quantity = Decimal(item, "size"); if (quantity <= 0) continue;
            position = new(symbol, Text(item, "side") == "Buy" ? TradeDirection.Long : TradeDirection.Short,
                quantity, Decimal(item, "avgPrice"), Decimal(item, "markPrice"), Decimal(item, "stopLoss"), null,
                Integer(item, "positionIdx") == 0, DateTimeOffset.UtcNow);
            break;
        }
        return new(order, position);
    }

    public async Task<MutationReceipt> ReplaceStopAsync(ExchangePositionTruth position, decimal stopPrice,
        string clientActionId, CancellationToken cancellationToken)
    {
        var body = JsonSerializer.Serialize(new Dictionary<string, object>
        {
            ["category"] = "linear", ["symbol"] = position.Symbol, ["tpslMode"] = "Full",
            ["positionIdx"] = 0, ["stopLoss"] = Format(stopPrice), ["slTriggerBy"] = "MarkPrice"
        });
        using var _ = await PrivatePostAsync("/v5/position/trading-stop", body, true, cancellationToken);
        return new(MutationDisposition.Accepted, null, null, "STOP_UPDATED", DateTimeOffset.UtcNow);
    }

    public async Task<MutationReceipt> CloseReduceOnlyAsync(ExchangePositionTruth position, string clientActionId,
        CancellationToken cancellationToken)
    {
        var body = JsonSerializer.Serialize(new Dictionary<string, object>
        {
            ["category"] = "linear", ["symbol"] = position.Symbol,
            ["side"] = position.Direction == TradeDirection.Long ? "Sell" : "Buy",
            ["orderType"] = "Market", ["qty"] = Format(position.Quantity), ["positionIdx"] = 0,
            ["orderLinkId"] = clientActionId, ["reduceOnly"] = true
        });
        using var response = await PrivatePostAsync("/v5/order/create", body, true, cancellationToken);
        return new(MutationDisposition.Accepted, Text(response.RootElement.GetProperty("result"), "orderId"), null,
            "CLOSE_ACCEPTED", DateTimeOffset.UtcNow);
    }

    public async Task<IReadOnlyList<ExchangePositionTruth>> GetOpenPositionsAsync(CancellationToken cancellationToken)
    {
        using var document = await PrivateGetAsync("/v5/position/list", "category=linear&settleCoin=USDT&limit=200", cancellationToken);
        var output = new List<ExchangePositionTruth>();
        foreach (var item in document.RootElement.GetProperty("result").GetProperty("list").EnumerateArray())
        {
            var quantity = Decimal(item, "size"); if (quantity <= 0) continue;
            output.Add(new(Text(item, "symbol"), Text(item, "side") == "Buy" ? TradeDirection.Long : TradeDirection.Short,
                quantity, Decimal(item, "avgPrice"), Decimal(item, "markPrice"), Decimal(item, "stopLoss"), null,
                Integer(item, "positionIdx") == 0, DateTimeOffset.UtcNow));
        }
        return output;
    }

    private async Task<ExchangeOrderTruth> FindOrderAsync(string symbol, string clientOrderId, CancellationToken cancellationToken)
    {
        foreach (var path in new[] { "/v5/order/realtime", "/v5/order/history" })
        {
            using var document = await PrivateGetAsync(path,
                $"category=linear&symbol={Escape(symbol)}&orderLinkId={Escape(clientOrderId)}&limit=1", cancellationToken);
            var list = document.RootElement.GetProperty("result").GetProperty("list");
            if (list.GetArrayLength() == 0) continue;
            var item = list[0];
            var state = Text(item, "orderStatus") switch
            {
                "Filled" => ExchangeOrderState.Filled,
                "PartiallyFilled" => ExchangeOrderState.PartiallyFilled,
                "New" or "Created" or "Untriggered" => ExchangeOrderState.Open,
                "Rejected" => ExchangeOrderState.Rejected,
                "Cancelled" or "Deactivated" => ExchangeOrderState.Cancelled,
                _ => ExchangeOrderState.Unknown
            };
            return new(state, Text(item, "orderId"), clientOrderId, Decimal(item, "cumExecQty"),
                Decimal(item, "avgPrice"), Math.Abs(Decimal(item, "cumExecFee")), Text(item, "orderStatus"));
        }
        return new(ExchangeOrderState.Missing, null, clientOrderId, 0, 0, 0, "ORDER_NOT_FOUND");
    }

    private async Task<JsonDocument> PublicGetAsync(string path, CancellationToken cancellationToken)
    {
        using var response = await http.GetAsync(BaseUrl + path, cancellationToken);
        var body = await response.Content.ReadAsStringAsync(cancellationToken); var document = JsonDocument.Parse(body);
        var code = Integer(document.RootElement, "retCode");
        if (response.IsSuccessStatusCode && code == 0) return document;
        document.Dispose(); throw new ExchangeApiException("BYBIT_" + code);
    }

    private async Task<JsonDocument> PrivateGetAsync(string path, string query, CancellationToken cancellationToken)
    {
        using var request = Signed(HttpMethod.Get, path + (query.Length == 0 ? "" : "?" + query), query, "");
        using var response = await http.SendAsync(request, cancellationToken);
        return await ParseAsync(response, false, cancellationToken);
    }

    private async Task<JsonDocument> PrivatePostAsync(string path, string body, bool ambiguousOnFailure,
        CancellationToken cancellationToken)
    {
        using var request = Signed(HttpMethod.Post, path, body, body);
        HttpResponseMessage response;
        try { response = await http.SendAsync(request, cancellationToken); }
        catch (Exception exception) when (ambiguousOnFailure && exception is HttpRequestException or TaskCanceledException)
        { throw new AmbiguousMutationException("BYBIT_NETWORK", exception); }
        using (response) return await ParseAsync(response, ambiguousOnFailure, cancellationToken);
    }

    private HttpRequestMessage Signed(HttpMethod method, string requestPath, string signaturePayload, string body)
    {
        var timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds().ToString(CultureInfo.InvariantCulture);
        var request = new HttpRequestMessage(method, BaseUrl + requestPath);
        request.Headers.TryAddWithoutValidation("X-BAPI-API-KEY", Credentials.ApiKey);
        request.Headers.TryAddWithoutValidation("X-BAPI-SIGN", Hmac(timestamp + Credentials.ApiKey + ReceiveWindow + signaturePayload));
        request.Headers.TryAddWithoutValidation("X-BAPI-TIMESTAMP", timestamp);
        request.Headers.TryAddWithoutValidation("X-BAPI-RECV-WINDOW", ReceiveWindow);
        if (body.Length > 0) request.Content = new StringContent(body, Encoding.UTF8, "application/json");
        return request;
    }

    private static async Task<JsonDocument> ParseAsync(HttpResponseMessage response, bool ambiguousOnFailure,
        CancellationToken cancellationToken)
    {
        var body = await response.Content.ReadAsStringAsync(cancellationToken);
        if (ambiguousOnFailure && (int)response.StatusCode >= 500) throw new AmbiguousMutationException("BYBIT_HTTP_5XX");
        var document = JsonDocument.Parse(body); var code = Integer(document.RootElement, "retCode");
        if (response.IsSuccessStatusCode && code == 0) return document;
        document.Dispose(); throw new ExchangeApiException("BYBIT_" + code);
    }

    private string Hmac(string value)
    {
        using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(Credentials.ApiSecret));
        return Convert.ToHexString(hmac.ComputeHash(Encoding.UTF8.GetBytes(value))).ToLowerInvariant();
    }

    private static string Text(JsonElement item, string property) => item.TryGetProperty(property, out var value)
        ? value.ValueKind == JsonValueKind.String ? value.GetString() ?? "" : value.ToString() : "";
    private static decimal Decimal(JsonElement item, string property) => decimal.TryParse(Text(item, property), NumberStyles.Float,
        CultureInfo.InvariantCulture, out var value) ? value : 0m;
    private static int Integer(JsonElement item, string property) => int.TryParse(Text(item, property), NumberStyles.Integer,
        CultureInfo.InvariantCulture, out var value) ? value : 0;
    private static decimal Positive(JsonElement item, string property, decimal fallback = 0m) => Decimal(item, property) is > 0 and var value ? value : fallback;
    private static string Escape(string value) => Uri.EscapeDataString(value);
    private static string Format(decimal value) => value.ToString("0.############################", CultureInfo.InvariantCulture);
}
