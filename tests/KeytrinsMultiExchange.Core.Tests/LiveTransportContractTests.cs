using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using KeytrinsMultiExchange.Core;
using Xunit;

namespace KeytrinsMultiExchange.Core.Tests;

public sealed class LiveTransportContractTests
{
    private static readonly ExchangeCredentials Credentials = new("test-key", "test-secret", "test-passphrase");

    [Fact]
    public async Task Okx_entry_has_signed_market_order_and_attached_exchange_stop()
    {
        var handler = new RouteHandler(request => request.RequestUri!.AbsolutePath switch
        {
            "/api/v5/account/set-leverage" => Ok("{\"code\":\"0\",\"data\":[]}"),
            "/api/v5/trade/order" => Ok("{\"code\":\"0\",\"data\":[{\"ordId\":\"o1\",\"sCode\":\"0\"}]}"),
            _ => throw new InvalidOperationException(request.RequestUri.AbsolutePath)
        });
        var transport = new OkxLiveExecutionTransport(new HttpClient(handler), _ => Credentials);
        var result = await transport.SubmitEntryAsync(Entry(ExchangeId.Okx, "UNI-USDT-SWAP"), default);
        Assert.Equal(MutationDisposition.Accepted, result.Disposition);
        var request = handler.Requests.Single(x => x.Path == "/api/v5/trade/order");
        using var body = JsonDocument.Parse(request.Body);
        Assert.Equal("market", body.RootElement.GetProperty("ordType").GetString());
        Assert.Equal("net", body.RootElement.GetProperty("posSide").GetString());
        Assert.False(body.RootElement.GetProperty("reduceOnly").GetBoolean());
        var stop = body.RootElement.GetProperty("attachAlgoOrds")[0];
        Assert.Equal("5.1", stop.GetProperty("slTriggerPx").GetString());
        Assert.Equal("mark", stop.GetProperty("slTriggerPxType").GetString());
        VerifyOkxSignature(request);
    }

    [Fact]
    public async Task Okx_prepare_uses_derivatives_fee_group_and_verified_taker_rate()
    {
        var handler = new RouteHandler(request => request.RequestUri!.AbsolutePath switch
        {
            "/api/v5/account/positions" => Ok("{\"code\":\"0\",\"data\":[]}"),
            "/api/v5/public/instruments" => Ok("{\"code\":\"0\",\"data\":[{\"state\":\"live\",\"ctType\":\"linear\",\"settleCcy\":\"USDT\",\"tickSz\":\"0.001\",\"lotSz\":\"1\",\"minSz\":\"1\",\"maxMktSz\":\"100000\",\"ctVal\":\"1\",\"groupId\":\"4\",\"instFamily\":\"UNI-USDT\"}]}"),
            "/api/v5/market/ticker" => Ok("{\"code\":\"0\",\"data\":[{\"bidPx\":\"5.20\",\"askPx\":\"5.21\",\"last\":\"5.205\"}]}"),
            "/api/v5/account/trade-fee" => Ok("{\"code\":\"0\",\"data\":[{\"feeGroup\":[{\"groupId\":\"4\",\"taker\":\"-0.001\"}]}]}"),
            "/api/v5/account/balance" => Ok("{\"code\":\"0\",\"data\":[{\"details\":[{\"ccy\":\"USDT\",\"availBal\":\"100\"}]}]}"),
            _ => throw new InvalidOperationException(request.RequestUri.PathAndQuery)
        });
        var transport = new OkxLiveExecutionTransport(new HttpClient(handler), _ => Credentials);
        var signal = new CanonicalSignal("fee-signal", "OKX", "UNI-USDT-SWAP",
            DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), TradeDirection.Short, 5.205, 5.105,
            0.1, 0.0192, 0.08, 25, 1, "TEST", DateTimeOffset.UtcNow);
        var options = new RuntimeOptions
            { RiskUsdt = 3m, MaxCostR = 1m, MaxNotionalUsdt = 1000m, MaxNetLossUsdt = 3m };

        var entry = await transport.PrepareEntryAsync(signal, options, default);

        Assert.Equal(0.001m, entry.TakerFeeRate);
        Assert.Contains(handler.Requests, x => x.PathAndQuery.Contains("instType=SWAP&groupId=4", StringComparison.Ordinal));
        Assert.DoesNotContain(handler.Requests, x => x.PathAndQuery.Contains("instId=UNI-USDT-SWAP", StringComparison.Ordinal) &&
            x.Path == "/api/v5/account/trade-fee");
    }

    [Fact]
    public async Task Bybit_entry_is_one_way_and_contains_mandatory_mark_price_stop()
    {
        var handler = new RouteHandler(request => request.RequestUri!.AbsolutePath switch
        {
            "/v5/position/set-leverage" => Ok("{\"retCode\":0,\"result\":{}}"),
            "/v5/order/create" => Ok("{\"retCode\":0,\"result\":{\"orderId\":\"o1\"}}"),
            _ => throw new InvalidOperationException(request.RequestUri.AbsolutePath)
        });
        var transport = new BybitLiveExecutionTransport(new HttpClient(handler), _ => Credentials);
        var result = await transport.SubmitEntryAsync(Entry(ExchangeId.Bybit, "UNIUSDT"), default);
        Assert.Equal(MutationDisposition.Accepted, result.Disposition);
        var request = handler.Requests.Single(x => x.Path == "/v5/order/create");
        using var body = JsonDocument.Parse(request.Body);
        Assert.Equal(0, body.RootElement.GetProperty("positionIdx").GetInt32());
        Assert.False(body.RootElement.GetProperty("reduceOnly").GetBoolean());
        Assert.Equal("5.1", body.RootElement.GetProperty("stopLoss").GetString());
        Assert.Equal("MarkPrice", body.RootElement.GetProperty("slTriggerBy").GetString());
        VerifyBybitSignature(request);
    }

    [Fact]
    public async Task Bybit_prepare_uses_account_specific_instrument_eligibility()
    {
        var handler = new RouteHandler(request => request.RequestUri!.AbsolutePath switch
        {
            "/v5/position/list" => Ok("{\"retCode\":0,\"retMsg\":\"OK\",\"result\":{\"list\":[{\"size\":\"0\",\"positionIdx\":0}]}}"),
            "/v5/account/instruments-info" => Ok("{\"retCode\":0,\"retMsg\":\"OK\",\"result\":{\"list\":[{\"status\":\"Trading\",\"settleCoin\":\"USDT\",\"priceFilter\":{\"tickSize\":\"0.001\"},\"lotSizeFilter\":{\"qtyStep\":\"1\",\"minOrderQty\":\"1\",\"maxMktOrderQty\":\"100000\",\"minNotionalValue\":\"5\"}}]}}"),
            "/v5/market/tickers" => Ok("{\"retCode\":0,\"retMsg\":\"OK\",\"result\":{\"list\":[{\"bid1Price\":\"5.20\",\"ask1Price\":\"5.21\",\"markPrice\":\"5.205\"}]}}"),
            "/v5/account/fee-rate" => Ok("{\"retCode\":0,\"retMsg\":\"OK\",\"result\":{\"list\":[{\"takerFeeRate\":\"0.001\"}]}}"),
            "/v5/account/wallet-balance" => Ok("{\"retCode\":0,\"retMsg\":\"OK\",\"result\":{\"list\":[{\"totalAvailableBalance\":\"100\",\"coin\":[{\"coin\":\"USDT\",\"availableToWithdraw\":\"100\"}]}]}}"),
            _ => throw new InvalidOperationException(request.RequestUri.PathAndQuery)
        });
        var transport = new BybitLiveExecutionTransport(new HttpClient(handler), _ => Credentials);
        var signal = new CanonicalSignal("bybit-eligibility", "OKX", "UNI-USDT-SWAP",
            DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), TradeDirection.Short, 5.205, 5.105,
            0.1, 0.0192, 0.08, 25, 1, "TEST", DateTimeOffset.UtcNow);
        var options = new RuntimeOptions
            { RiskUsdt = 3m, MaxCostR = 1m, MaxNotionalUsdt = 1000m, MaxNetLossUsdt = 3m };

        var entry = await transport.PrepareEntryAsync(signal, options, default);

        Assert.Equal("UNIUSDT", entry.Symbol);
        Assert.Contains(handler.Requests, x => x.Path == "/v5/account/instruments-info" &&
            x.PathAndQuery.Contains("symbol=UNIUSDT", StringComparison.Ordinal));
        Assert.DoesNotContain(handler.Requests, x => x.Path == "/v5/market/instruments-info");
    }

    [Fact]
    public async Task Bybit_rejection_preserves_safe_ret_msg()
    {
        var handler = new RouteHandler(request => request.RequestUri!.AbsolutePath switch
        {
            "/v5/position/set-leverage" => Ok("{\"retCode\":0,\"retMsg\":\"OK\",\"result\":{}}"),
            "/v5/order/create" => Ok("{\"retCode\":110123,\"retMsg\":\"You must agree to the Trading Terms\",\"result\":{}}"),
            _ => throw new InvalidOperationException(request.RequestUri.AbsolutePath)
        });
        var transport = new BybitLiveExecutionTransport(new HttpClient(handler), _ => Credentials);

        var error = await Assert.ThrowsAsync<ExchangeApiException>(() =>
            transport.SubmitEntryAsync(Entry(ExchangeId.Bybit, "XAGUSDT"), default));

        Assert.Equal("BYBIT_110123", error.Code);
        Assert.Equal("You must agree to the Trading Terms", error.Detail);
        Assert.Equal("BYBIT_110123:You must agree to the Trading Terms", error.Message);
        Assert.DoesNotContain(Credentials.ApiSecret, error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Okx_rejection_preserves_item_subcode_and_message()
    {
        var handler = new RouteHandler(request => request.RequestUri!.AbsolutePath switch
        {
            "/api/v5/account/set-leverage" => Ok("{\"code\":\"0\",\"msg\":\"\",\"data\":[]}"),
            "/api/v5/trade/order" => Ok("{\"code\":\"1\",\"msg\":\"Operation failed\",\"data\":[{\"sCode\":\"51046\",\"sMsg\":\"The stop price is invalid\"}]}"),
            _ => throw new InvalidOperationException(request.RequestUri.AbsolutePath)
        });
        var transport = new OkxLiveExecutionTransport(new HttpClient(handler), _ => Credentials);

        var error = await Assert.ThrowsAsync<ExchangeApiException>(() =>
            transport.SubmitEntryAsync(Entry(ExchangeId.Okx, "SPCX-USDT-SWAP"), default));

        Assert.Equal("OKX_51046", error.Code);
        Assert.Equal("The stop price is invalid", error.Detail);
        Assert.Equal("OKX_51046:The stop price is invalid", error.Message);
        Assert.DoesNotContain(Credentials.ApiSecret, error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public async Task Kucoin_entry_is_followed_by_reduce_only_exchange_stop()
    {
        var handler = new RouteHandler(request => request.RequestUri!.AbsolutePath switch
        {
            "/api/v2/position/getMarginMode" => Ok("{\"code\":\"200000\",\"data\":{\"marginMode\":\"CROSS\"}}"),
            "/api/v2/changeCrossUserLeverage" => Ok("{\"code\":\"200000\",\"data\":{}}"),
            "/api/v1/st-orders" => Ok("{\"code\":\"200000\",\"data\":{\"orderId\":\"entry-1\"}}"),
            _ => throw new InvalidOperationException(request.RequestUri.AbsolutePath)
        });
        var transport = new KuCoinLiveExecutionTransport(new HttpClient(handler), _ => Credentials);
        var result = await transport.SubmitEntryAsync(Entry(ExchangeId.KuCoinFutures, "UNIUSDTM"), default);
        Assert.Equal(MutationDisposition.Accepted, result.Disposition);
        var orders = handler.Requests.Where(x => x.Path == "/api/v1/st-orders").ToArray();
        Assert.Single(orders);
        using var entry = JsonDocument.Parse(orders[0].Body);
        Assert.False(entry.RootElement.GetProperty("reduceOnly").GetBoolean());
        Assert.Equal("BOTH", entry.RootElement.GetProperty("positionSide").GetString());
        Assert.Equal("MP", entry.RootElement.GetProperty("stopPriceType").GetString());
        Assert.Equal("5.1", entry.RootElement.GetProperty("triggerStopDownPrice").GetString());
        VerifyKuCoinSignature(orders[0]);
    }

    [Fact]
    public async Task Kucoin_prepare_maps_btc_to_xbt_and_checks_its_own_available_margin()
    {
        var handler = new RouteHandler(request => request.RequestUri!.AbsolutePath switch
        {
            "/api/v1/positions" => Ok("{\"code\":\"200000\",\"data\":[]}"),
            "/api/v2/position/getPositionMode" => Ok("{\"code\":\"200000\",\"data\":{\"positionMode\":0}}"),
            "/api/v1/contracts/XBTUSDTM" => Ok("{\"code\":\"200000\",\"data\":{\"quoteCurrency\":\"USDT\",\"settleCurrency\":\"USDT\",\"isInverse\":false,\"supportCross\":true,\"marketStage\":\"NORMAL\",\"tickSize\":\"0.1\",\"lotSize\":\"1\",\"marketMaxOrderQty\":\"100000\",\"multiplier\":\"0.001\",\"markPrice\":\"50005\"}}"),
            "/api/v1/ticker" => Ok("{\"code\":\"200000\",\"data\":{\"bestBidPrice\":\"50000\",\"bestAskPrice\":\"50010\"}}"),
            "/api/v1/trade-fees" => Ok("{\"code\":\"200000\",\"data\":{\"takerFeeRate\":\"0.001\"}}"),
            "/api/v1/account-overview" => Ok("{\"code\":\"200000\",\"data\":{\"availableBalance\":\"100\"}}"),
            _ => throw new InvalidOperationException(request.RequestUri.PathAndQuery)
        });
        var transport = new KuCoinLiveExecutionTransport(new HttpClient(handler), _ => Credentials);
        var signal = new CanonicalSignal("kucoin-btc-map", "OKX", "BTC-USDT-SWAP",
            DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(), TradeDirection.Short, 50005, 49500,
            505, 0.01, 100, 25, 1, "TEST", DateTimeOffset.UtcNow);
        var options = new RuntimeOptions
        {
            PositionNotionalUsdt = 100m, MaxCostR = 1m, MaxNotionalUsdt = 1000m,
            MaxNetLossUsdt = 1.5m, Leverage = 5
        };

        var entry = await transport.PrepareEntryAsync(signal, options, default);

        Assert.Equal("XBTUSDTM", entry.Symbol);
        Assert.Contains(handler.Requests, x => x.Path == "/api/v1/account-overview");
    }

    [Fact]
    public async Task Kucoin_order_visibility_lag_still_reconciles_exchange_position_and_stop()
    {
        var handler = new RouteHandler(request => request.RequestUri!.AbsolutePath switch
        {
            "/api/v1/orders/byClientOid" => Ok("{\"code\":\"100001\",\"msg\":\"error.getOrder.orderNotExist\"}"),
            "/api/v2/position" => Ok("{\"code\":\"200000\",\"data\":{\"symbol\":\"XRPUSDTM\",\"currentQty\":\"24\",\"avgEntryPrice\":\"1.33145\",\"markPrice\":\"1.33000\",\"realLeverage\":\"5\"}}"),
            "/api/v1/stopOrders" => Ok("{\"code\":\"200000\",\"data\":{\"items\":[{\"id\":\"stop-1\",\"side\":\"sell\",\"reduceOnly\":true,\"stopPrice\":\"1.32709\"}]}}"),
            _ => throw new InvalidOperationException(request.RequestUri.PathAndQuery)
        });
        var transport = new KuCoinLiveExecutionTransport(new HttpClient(handler), _ => Credentials);

        var truth = await transport.ReconcileEntryAsync("XRPUSDTM", "KXKuCoinVisibility", default);

        Assert.Equal(ExchangeOrderState.Missing, truth.Order.State);
        Assert.NotNull(truth.Position);
        Assert.Equal(1.32709m, truth.Position!.StopPrice);
        Assert.Equal("stop-1", truth.Position.StopOrderId);
    }

    [Fact]
    public async Task Okx_reconciliation_finds_attached_stop_in_pending_algo_truth()
    {
        var stopClientId = ExecutionIds.Stop(ExchangeId.Okx, "okx-stop-visibility", 0);
        var handler = new RouteHandler(request => request.RequestUri!.AbsolutePath switch
        {
            "/api/v5/trade/order" => Ok("{\"code\":\"0\",\"data\":[{\"ordId\":\"entry-1\",\"state\":\"filled\",\"accFillSz\":\"10\",\"avgPx\":\"5.20\",\"fee\":\"-0.052\"}]}"),
            "/api/v5/account/positions" => Ok("{\"code\":\"0\",\"data\":[{\"instId\":\"UNI-USDT-SWAP\",\"pos\":\"10\",\"avgPx\":\"5.20\",\"markPx\":\"5.21\",\"posSide\":\"net\",\"closeOrderAlgo\":[]}]}"),
            "/api/v5/trade/orders-algo-pending" => Ok($"{{\"code\":\"0\",\"data\":[{{\"algoId\":\"algo-1\",\"algoClOrdId\":\"{stopClientId}\",\"side\":\"sell\",\"posSide\":\"net\",\"slTriggerPx\":\"5.10\"}}]}}"),
            _ => throw new InvalidOperationException(request.RequestUri.PathAndQuery)
        });
        var transport = new OkxLiveExecutionTransport(new HttpClient(handler), _ => Credentials);

        var truth = await transport.ReconcileEntryAsync("UNI-USDT-SWAP", "entry-client", default);

        Assert.NotNull(truth.Position);
        Assert.Equal(5.10m, truth.Position!.StopPrice);
        Assert.Equal("algo-1", truth.Position.StopOrderId);
    }

    [Theory]
    [InlineData("Okx")]
    [InlineData("Bybit")]
    [InlineData("KuCoinFutures")]
    public async Task Mutation_http_5xx_is_ambiguous_and_never_retried(string exchangeName)
    {
        var exchange = Enum.Parse<ExchangeId>(exchangeName);
        var handler = new RouteHandler(_ => new HttpResponseMessage(HttpStatusCode.ServiceUnavailable)
        { Content = new StringContent(exchange == ExchangeId.Okx ? "{\"code\":\"500\"}" :
            exchange == ExchangeId.Bybit ? "{\"retCode\":10000}" : "{\"code\":\"500000\"}") });
        ILiveExecutionTransport transport = exchange switch
        {
            ExchangeId.Okx => new OkxLiveExecutionTransport(new HttpClient(handler), _ => Credentials),
            ExchangeId.Bybit => new BybitLiveExecutionTransport(new HttpClient(handler), _ => Credentials),
            _ => new KuCoinLiveExecutionTransport(new HttpClient(handler), _ => Credentials)
        };
        var position = new ExchangePositionTruth(exchange == ExchangeId.Okx ? "UNI-USDT-SWAP" :
            exchange == ExchangeId.Bybit ? "UNIUSDT" : "UNIUSDTM", TradeDirection.Long, 1m, 5.2m, 5.2m,
            5.1m, "stop-1", true, DateTimeOffset.UtcNow, 5);
        await Assert.ThrowsAsync<AmbiguousMutationException>(() =>
            transport.CloseReduceOnlyAsync(position, "KXCloseContractTest", default));
        Assert.Single(handler.Requests);
    }

    private static PreparedEntry Entry(ExchangeId exchange, string symbol) => new(exchange, "signal-contract",
        ExecutionIds.Entry(exchange, "signal-contract"), symbol, TradeDirection.Long, 5, 10m, 1m, 5.2m,
        5.1m, 5.1m, 5.0m, 0.001m, 0.001m, 0.001m, 0.1m);

    private static HttpResponseMessage Ok(string json) => new(HttpStatusCode.OK) { Content = new StringContent(json) };

    private static void VerifyOkxSignature(CapturedRequest request)
    {
        var timestamp = request.Headers["OK-ACCESS-TIMESTAMP"];
        var expected = HmacBase64(Credentials.ApiSecret, timestamp + request.Method + request.PathAndQuery + request.Body);
        Assert.Equal(expected, request.Headers["OK-ACCESS-SIGN"]);
        Assert.Equal(Credentials.ApiKey, request.Headers["OK-ACCESS-KEY"]);
    }

    private static void VerifyBybitSignature(CapturedRequest request)
    {
        var timestamp = request.Headers["X-BAPI-TIMESTAMP"]; var window = request.Headers["X-BAPI-RECV-WINDOW"];
        var expected = HmacHex(Credentials.ApiSecret, timestamp + Credentials.ApiKey + window + request.Body);
        Assert.Equal(expected, request.Headers["X-BAPI-SIGN"]);
    }

    private static void VerifyKuCoinSignature(CapturedRequest request)
    {
        var timestamp = request.Headers["KC-API-TIMESTAMP"];
        var expected = HmacBase64(Credentials.ApiSecret, timestamp + request.Method + request.PathAndQuery + request.Body);
        Assert.Equal(expected, request.Headers["KC-API-SIGN"]);
        Assert.Equal(HmacBase64(Credentials.ApiSecret, Credentials.Passphrase), request.Headers["KC-API-PASSPHRASE"]);
    }

    private static string HmacBase64(string secret, string value)
    {
        using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(secret));
        return Convert.ToBase64String(hmac.ComputeHash(Encoding.UTF8.GetBytes(value)));
    }
    private static string HmacHex(string secret, string value)
    {
        using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(secret));
        return Convert.ToHexString(hmac.ComputeHash(Encoding.UTF8.GetBytes(value))).ToLowerInvariant();
    }

    private sealed record CapturedRequest(string Method, string Path, string PathAndQuery, string Body,
        Dictionary<string, string> Headers);

    private sealed class RouteHandler(Func<HttpRequestMessage, HttpResponseMessage> responder) : HttpMessageHandler
    {
        public List<CapturedRequest> Requests { get; } = [];
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var body = request.Content is null ? "" : await request.Content.ReadAsStringAsync(cancellationToken);
            var headers = request.Headers.ToDictionary(x => x.Key, x => string.Join(",", x.Value), StringComparer.OrdinalIgnoreCase);
            Requests.Add(new(request.Method.Method, request.RequestUri!.AbsolutePath, request.RequestUri.PathAndQuery, body, headers));
            return responder(request);
        }
    }
}
