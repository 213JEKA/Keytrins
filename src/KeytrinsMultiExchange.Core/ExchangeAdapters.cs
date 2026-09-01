using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace KeytrinsMultiExchange.Core;

public sealed record ExchangeCredentials(string ApiKey, string ApiSecret, string Passphrase = "")
{
    public bool IsPresent => !string.IsNullOrWhiteSpace(ApiKey) && !string.IsNullOrWhiteSpace(ApiSecret);
}

public sealed record CredentialAudit(bool Authenticated, bool TradingPermission, bool WithdrawPermission, string Detail,
    decimal? Balance = null, decimal? Equity = null, int OpenPositionCount = 0);

public sealed class ExchangeApiException(string code) : Exception(code)
{
    public string Code { get; } = code;
}

public interface IExchangeAdapter
{
    ExchangeId Id { get; }
    string MapOkxSymbol(string okxInstrumentId);
    Task<ExchangeSnapshot> ReadOnlyPreflightAsync(ExchangeMode requestedMode, CancellationToken cancellationToken);
    Task<RouteAttempt> RouteAsync(CanonicalSignal signal, RuntimeOptions options, CancellationToken cancellationToken);
}

public abstract class ReadOnlyGuardedAdapter : IExchangeAdapter
{
    private readonly HttpClient _http;
    private readonly Func<ExchangeId, ExchangeCredentials> _credentials;
    protected ExchangeCredentials Credentials => _credentials(Id);
    protected ReadOnlyGuardedAdapter(HttpClient http, Func<ExchangeId, ExchangeCredentials> credentials) { _http = http; _credentials = credentials; }
    public abstract ExchangeId Id { get; }
    protected abstract string PublicProbeUrl { get; }
    public virtual string MapOkxSymbol(string okxInstrumentId) => okxInstrumentId.Replace("-", "", StringComparison.OrdinalIgnoreCase).Replace("SWAP", "", StringComparison.OrdinalIgnoreCase);

    public virtual async Task<ExchangeSnapshot> ReadOnlyPreflightAsync(ExchangeMode requestedMode, CancellationToken cancellationToken)
    {
        var publicConnected = false;
        var detail = "public endpoint unavailable";
        try
        {
            using var response = await _http.GetAsync(PublicProbeUrl, cancellationToken);
            publicConnected = response.IsSuccessStatusCode;
            detail = publicConnected ? "PUBLIC_OK" : $"PUBLIC_HTTP_{(int)response.StatusCode}";
        }
        catch (Exception exception) { detail = exception.GetType().Name; }
        var audit = await AuditCredentialsAsync(cancellationToken);
        var mode = requestedMode;
        if (!Credentials.IsPresent) mode = ExchangeMode.NotConfigured;
        else if (!publicConnected || !audit.Authenticated || !audit.TradingPermission || audit.WithdrawPermission) mode = ExchangeMode.Error;
        return new(Id, mode, publicConnected, audit.Authenticated, audit.TradingPermission, audit.WithdrawPermission,
            publicConnected ? "ONLINE" : "ERROR", audit.Balance, audit.Equity, 0, 0, audit.OpenPositionCount, null, DateTimeOffset.UtcNow,
            $"{detail}; {audit.Detail}");
    }

    protected virtual Task<CredentialAudit> AuditCredentialsAsync(CancellationToken cancellationToken) =>
        Task.FromResult(Credentials.IsPresent
            ? new CredentialAudit(false, false, false, "PRIVATE_PREFLIGHT_NOT_AVAILABLE")
            : new CredentialAudit(false, false, false, "NOT_CONFIGURED"));

    public Task<RouteAttempt> RouteAsync(CanonicalSignal signal, RuntimeOptions options, CancellationToken cancellationToken)
    {
        var received = DateTimeOffset.UtcNow;
        var configuredMode = options.Exchanges.TryGetValue(Id.ToString(), out var value) ? value : ExchangeMode.NotConfigured;
        var reason = !Credentials.IsPresent ? "API_ERROR_NOT_CONFIGURED"
            : configuredMode != ExchangeMode.Active ? "EXCHANGE_PAUSED"
            : !options.TradingEnabled ? "GLOBAL_ADMISSION_DISABLED"
            : "LIVE_MUTATION_GATE_NOT_ARMED";
        // Mutation endpoints are intentionally unreachable during build/read-only deployment.
        return Task.FromResult(new RouteAttempt(Id, signal.SignalId, received, null, null, RouteResult.Skipped, reason));
    }
}

public sealed class OkxExchangeAdapter : ReadOnlyGuardedAdapter
{
    private readonly HttpClient _http;
    public OkxExchangeAdapter(HttpClient http, Func<ExchangeId, ExchangeCredentials> credentials) : base(http, credentials) => _http = http;
    public override ExchangeId Id => ExchangeId.Okx;
    protected override string PublicProbeUrl => "https://www.okx.com/api/v5/public/time";
    public override string MapOkxSymbol(string okxInstrumentId) => okxInstrumentId;

    protected override async Task<CredentialAudit> AuditCredentialsAsync(CancellationToken cancellationToken)
    {
        if (!Credentials.IsPresent) return new(false, false, false, "NOT_CONFIGURED");
        if (string.IsNullOrWhiteSpace(Credentials.Passphrase)) return new(false, false, false, "PRIVATE_PASSPHRASE_REQUIRED");
        try
        {
            using var document = await PrivateGetAsync("/api/v5/account/config", cancellationToken);
            var data = document.RootElement.GetProperty("data");
            if (data.GetArrayLength() == 0) return new(false, false, false, "PRIVATE_EMPTY_ACCOUNT_CONFIG");
            var account = data[0];
            var permissions = Tokens(Text(account, "perm"));
            var trading = permissions.Contains("trade");
            var withdraw = permissions.Contains("withdraw");
            var positionMode = Text(account, "posMode");
            var oneWay = positionMode.Equals("net_mode", StringComparison.OrdinalIgnoreCase);

            using var balanceDocument = await PrivateGetAsync("/api/v5/account/balance?ccy=USDT", cancellationToken);
            decimal? balance = null, equity = null;
            var balances = balanceDocument.RootElement.GetProperty("data");
            if (balances.GetArrayLength() > 0)
            {
                var summary = balances[0];
                equity = DecimalOrNull(summary, "totalEq");
                if (summary.TryGetProperty("details", out var details))
                    foreach (var detail in details.EnumerateArray())
                        if (Text(detail, "ccy").Equals("USDT", StringComparison.OrdinalIgnoreCase))
                        { balance = DecimalOrNull(detail, "cashBal"); break; }
            }
            using var positionsDocument = await PrivateGetAsync("/api/v5/account/positions?instType=SWAP", cancellationToken);
            var openPositions = positionsDocument.RootElement.GetProperty("data").EnumerateArray()
                .Count(x => Text(x, "instId").EndsWith("-USDT-SWAP", StringComparison.OrdinalIgnoreCase) && Math.Abs(Decimal(x, "pos")) > 0m);
            return new(true, trading && oneWay, withdraw,
                $"PRIVATE_OK; trade={(trading ? 1 : 0)}; withdraw={(withdraw ? 1 : 0)}; posMode={positionMode}; oneWay={(oneWay ? 1 : 0)}; open={openPositions}",
                balance, equity, openPositions);
        }
        catch (ExchangeApiException exception) { return new(false, false, false, $"PRIVATE_ERROR_{exception.Code}"); }
        catch (Exception exception) { return new(false, false, false, $"PRIVATE_{exception.GetType().Name}"); }
    }

    private async Task<JsonDocument> PrivateGetAsync(string path, CancellationToken cancellationToken)
    {
        var timestamp = DateTimeOffset.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture);
        using var request = new HttpRequestMessage(HttpMethod.Get, "https://www.okx.com" + path);
        request.Headers.TryAddWithoutValidation("OK-ACCESS-KEY", Credentials.ApiKey);
        request.Headers.TryAddWithoutValidation("OK-ACCESS-SIGN", Base64HmacSha256(timestamp + "GET" + path, Credentials.ApiSecret));
        request.Headers.TryAddWithoutValidation("OK-ACCESS-TIMESTAMP", timestamp);
        request.Headers.TryAddWithoutValidation("OK-ACCESS-PASSPHRASE", Credentials.Passphrase);
        using var response = await _http.SendAsync(request, cancellationToken);
        var body = await response.Content.ReadAsStringAsync(cancellationToken);
        var document = JsonDocument.Parse(body);
        var code = document.RootElement.TryGetProperty("code", out var value) ? value.GetString() ?? "-1" : "-1";
        if (response.IsSuccessStatusCode && code == "0") return document;
        document.Dispose();
        throw new ExchangeApiException(code);
    }

    private static HashSet<string> Tokens(string value) => value.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
        .ToHashSet(StringComparer.OrdinalIgnoreCase);
    private static string Text(JsonElement item, string property) => item.TryGetProperty(property, out var value)
        ? value.ValueKind == JsonValueKind.String ? value.GetString() ?? "" : value.ToString() : "";
    private static decimal Decimal(JsonElement item, string property) => decimal.TryParse(Text(item, property), NumberStyles.Float,
        CultureInfo.InvariantCulture, out var value) ? value : 0m;
    private static decimal? DecimalOrNull(JsonElement item, string property) => decimal.TryParse(Text(item, property), NumberStyles.Float,
        CultureInfo.InvariantCulture, out var value) ? value : null;
    private static string Base64HmacSha256(string value, string secret)
    {
        using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(secret));
        return Convert.ToBase64String(hmac.ComputeHash(Encoding.UTF8.GetBytes(value)));
    }
}

public sealed class BybitExchangeAdapter : ReadOnlyGuardedAdapter
{
    private readonly HttpClient _http;
    public BybitExchangeAdapter(HttpClient http, Func<ExchangeId, ExchangeCredentials> credentials) : base(http, credentials) => _http = http;
    public override ExchangeId Id => ExchangeId.Bybit;
    protected override string PublicProbeUrl => "https://api.bybit.com/v5/market/time";

    protected override async Task<CredentialAudit> AuditCredentialsAsync(CancellationToken cancellationToken)
    {
        if (!Credentials.IsPresent) return new(false, false, false, "NOT_CONFIGURED");
        try
        {
            using var document = await PrivateGetAsync("/v5/user/query-api", "", cancellationToken);
            var result = document.RootElement.GetProperty("result");
            var readOnly = result.TryGetProperty("readOnly", out var read) && (read.ValueKind == JsonValueKind.Number ? read.GetInt32() : int.Parse(read.GetString() ?? "1")) == 1;
            var permissionsText = result.TryGetProperty("permissions", out var permissions) ? permissions.GetRawText() : "{}";
            var trading = permissionsText.Contains("Order", StringComparison.OrdinalIgnoreCase) || permissionsText.Contains("ContractTrade", StringComparison.OrdinalIgnoreCase);
            var withdraw = permissionsText.Contains("Withdraw", StringComparison.OrdinalIgnoreCase);

            using var modeDocument = await PrivateGetAsync("/v5/position/list", "category=linear&symbol=BTCUSDT", cancellationToken);
            var modeList = modeDocument.RootElement.GetProperty("result").GetProperty("list");
            var oneWay = modeList.GetArrayLength() > 0 && modeList.EnumerateArray().All(x =>
                !x.TryGetProperty("positionIdx", out var idx) || idx.GetInt32() == 0);
            using var positionsDocument = await PrivateGetAsync("/v5/position/list", "category=linear&settleCoin=USDT&limit=200", cancellationToken);
            var positions = positionsDocument.RootElement.GetProperty("result").GetProperty("list");
            var openPositions = positions.EnumerateArray().Count(x => Decimal(x, "size") > 0m);
            using var balanceDocument = await PrivateGetAsync("/v5/account/wallet-balance", "accountType=UNIFIED&coin=USDT", cancellationToken);
            decimal? balance = null, equity = null;
            var accounts = balanceDocument.RootElement.GetProperty("result").GetProperty("list");
            if (accounts.GetArrayLength() > 0)
            {
                var account = accounts[0];
                balance = DecimalOrNull(account, "totalWalletBalance");
                equity = DecimalOrNull(account, "totalEquity");
            }
            return new(true, trading && !readOnly && oneWay, withdraw,
                $"PRIVATE_OK; readOnly={(readOnly ? 1 : 0)}; oneWay={(oneWay ? 1 : 0)}; open={openPositions}",
                balance, equity, openPositions);
        }
        catch (ExchangeApiException exception) { return new(false, false, false, $"PRIVATE_ERROR_{exception.Code}"); }
        catch (Exception exception) { return new(false, false, false, $"PRIVATE_{exception.GetType().Name}"); }
    }

    private async Task<JsonDocument> PrivateGetAsync(string path, string query, CancellationToken cancellationToken)
    {
        var timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds().ToString(CultureInfo.InvariantCulture);
        const string recvWindow = "5000";
        var requestPath = query.Length == 0 ? path : path + "?" + query;
        using var request = new HttpRequestMessage(HttpMethod.Get, "https://api.bybit.com" + requestPath);
        request.Headers.TryAddWithoutValidation("X-BAPI-API-KEY", Credentials.ApiKey);
        request.Headers.TryAddWithoutValidation("X-BAPI-SIGN", HexHmacSha256(timestamp + Credentials.ApiKey + recvWindow + query, Credentials.ApiSecret));
        request.Headers.TryAddWithoutValidation("X-BAPI-TIMESTAMP", timestamp);
        request.Headers.TryAddWithoutValidation("X-BAPI-RECV-WINDOW", recvWindow);
        using var response = await _http.SendAsync(request, cancellationToken);
        var body = await response.Content.ReadAsStringAsync(cancellationToken);
        var document = JsonDocument.Parse(body);
        var retCode = document.RootElement.TryGetProperty("retCode", out var value) ? value.GetInt32() : -1;
        if (response.IsSuccessStatusCode && retCode == 0) return document;
        document.Dispose();
        throw new ExchangeApiException(retCode.ToString(CultureInfo.InvariantCulture));
    }

    private static string Text(JsonElement item, string property) => item.TryGetProperty(property, out var value)
        ? value.ValueKind == JsonValueKind.String ? value.GetString() ?? "" : value.ToString() : "";
    private static decimal Decimal(JsonElement item, string property) => decimal.TryParse(Text(item, property), NumberStyles.Float,
        CultureInfo.InvariantCulture, out var value) ? value : 0m;
    private static decimal? DecimalOrNull(JsonElement item, string property) => decimal.TryParse(Text(item, property), NumberStyles.Float,
        CultureInfo.InvariantCulture, out var value) ? value : null;

    private static string HexHmacSha256(string value, string secret)
    {
        using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(secret));
        return Convert.ToHexString(hmac.ComputeHash(Encoding.UTF8.GetBytes(value))).ToLowerInvariant();
    }
}

public sealed class KuCoinExchangeAdapter : ReadOnlyGuardedAdapter
{
    private readonly HttpClient _http;
    public KuCoinExchangeAdapter(HttpClient http, Func<ExchangeId, ExchangeCredentials> credentials) : base(http, credentials) => _http = http;
    public override ExchangeId Id => ExchangeId.KuCoinFutures;
    protected override string PublicProbeUrl => "https://api-futures.kucoin.com/api/v1/timestamp";
    public override string MapOkxSymbol(string okxInstrumentId) => okxInstrumentId.Split('-')[0] + "USDTM";

    protected override async Task<CredentialAudit> AuditCredentialsAsync(CancellationToken cancellationToken)
    {
        if (!Credentials.IsPresent) return new(false, false, false, "NOT_CONFIGURED");
        if (string.IsNullOrWhiteSpace(Credentials.Passphrase)) return new(false, false, false, "PRIVATE_PASSPHRASE_REQUIRED");
        try
        {
            const string keyInfoPath = "/api/v1/user/api-key";
            using var keyInfoResponse = await SendPrivateGetAsync("https://api.kucoin.com", keyInfoPath, "3", cancellationToken);
            var keyInfoBody = await keyInfoResponse.Content.ReadAsStringAsync(cancellationToken);
            using var keyInfoDocument = JsonDocument.Parse(keyInfoBody);
            var keyInfoCode = Code(keyInfoDocument.RootElement);
            if (!keyInfoResponse.IsSuccessStatusCode || keyInfoCode != "200000")
                return new(false, false, false, $"PRIVATE_ERROR_{keyInfoCode}");
            var keyData = keyInfoDocument.RootElement.GetProperty("data");
            var permissions = Tokens(Text(keyData, "permission"));
            var trading = permissions.Contains("Futures");
            var withdraw = permissions.Contains("Withdrawal") || permissions.Contains("Withdraw");
            var apiVersion = keyData.TryGetProperty("apiVersion", out var versionValue) ? versionValue.ToString() : "3";

            const string positionModePath = "/api/v2/position/getPositionMode";
            using var modeResponse = await SendPrivateGetAsync("https://api-futures.kucoin.com", positionModePath, apiVersion, cancellationToken);
            var modeBody = await modeResponse.Content.ReadAsStringAsync(cancellationToken);
            using var modeDocument = JsonDocument.Parse(modeBody);
            var modeCode = Code(modeDocument.RootElement);
            if (!modeResponse.IsSuccessStatusCode || modeCode != "200000")
                return new(false, trading, withdraw, $"PRIVATE_POSITION_MODE_ERROR_{modeCode}; apiVersion={apiVersion}");
            var mode = modeDocument.RootElement.GetProperty("data").GetProperty("positionMode").GetInt32();
            var oneWay = mode == 0;

            using var balanceResponse = await SendPrivateGetAsync("https://api-futures.kucoin.com",
                "/api/v1/account-overview?currency=USDT", apiVersion, cancellationToken);
            var balanceBody = await balanceResponse.Content.ReadAsStringAsync(cancellationToken);
            using var balanceDocument = JsonDocument.Parse(balanceBody);
            var balanceCode = Code(balanceDocument.RootElement);
            if (!balanceResponse.IsSuccessStatusCode || balanceCode != "200000")
                return new(false, trading && oneWay, withdraw, $"PRIVATE_BALANCE_ERROR_{balanceCode}; apiVersion={apiVersion}");
            var balanceData = balanceDocument.RootElement.GetProperty("data");
            var balance = DecimalOrNull(balanceData, "marginBalance") ?? DecimalOrNull(balanceData, "availableBalance");
            var equity = DecimalOrNull(balanceData, "accountEquity") ?? balance;

            using var positionsResponse = await SendPrivateGetAsync("https://api-futures.kucoin.com",
                "/api/v1/positions?currency=USDT", apiVersion, cancellationToken);
            var positionsBody = await positionsResponse.Content.ReadAsStringAsync(cancellationToken);
            using var positionsDocument = JsonDocument.Parse(positionsBody);
            var positionsCode = Code(positionsDocument.RootElement);
            if (!positionsResponse.IsSuccessStatusCode || positionsCode != "200000")
                return new(false, trading && oneWay, withdraw, $"PRIVATE_POSITIONS_ERROR_{positionsCode}; apiVersion={apiVersion}");
            var positions = positionsDocument.RootElement.GetProperty("data");
            var openPositions = positions.ValueKind == JsonValueKind.Array
                ? positions.EnumerateArray().Count(x => Math.Abs(Decimal(x, "currentQty")) > 0m || Math.Abs(Decimal(x, "size")) > 0m)
                : 0;
            return new(true, trading && oneWay, withdraw,
                $"PRIVATE_OK; futures={(trading ? 1 : 0)}; withdraw={(withdraw ? 1 : 0)}; positionMode={mode}; oneWay={(oneWay ? 1 : 0)}; apiVersion={apiVersion}; open={openPositions}",
                balance, equity, openPositions);
        }
        catch (Exception exception) { return new(false, false, false, $"PRIVATE_{exception.GetType().Name}"); }
    }

    private HttpRequestMessage SignedGet(string baseUrl, string endpoint, string apiVersion)
    {
        var timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds().ToString(CultureInfo.InvariantCulture);
        var signature = Base64HmacSha256(timestamp + "GET" + endpoint, Credentials.ApiSecret);
        var passphrase = apiVersion == "1" ? Credentials.Passphrase : Base64HmacSha256(Credentials.Passphrase, Credentials.ApiSecret);
        var request = new HttpRequestMessage(HttpMethod.Get, baseUrl + endpoint);
        request.Headers.TryAddWithoutValidation("KC-API-KEY", Credentials.ApiKey);
        request.Headers.TryAddWithoutValidation("KC-API-SIGN", signature);
        request.Headers.TryAddWithoutValidation("KC-API-TIMESTAMP", timestamp);
        request.Headers.TryAddWithoutValidation("KC-API-PASSPHRASE", passphrase);
        request.Headers.TryAddWithoutValidation("KC-API-KEY-VERSION", apiVersion);
        return request;
    }

    private async Task<HttpResponseMessage> SendPrivateGetAsync(string baseUrl, string endpoint, string apiVersion,
        CancellationToken cancellationToken)
    {
        using var request = SignedGet(baseUrl, endpoint, apiVersion);
        return await _http.SendAsync(request, cancellationToken);
    }

    private static string Code(JsonElement root) => root.TryGetProperty("code", out var value) ? value.GetString() ?? value.ToString() : "-1";
    private static string Text(JsonElement item, string property) => item.TryGetProperty(property, out var value)
        ? value.ValueKind == JsonValueKind.String ? value.GetString() ?? "" : value.ToString() : "";
    private static decimal Decimal(JsonElement item, string property) => decimal.TryParse(Text(item, property), NumberStyles.Float,
        CultureInfo.InvariantCulture, out var value) ? value : 0m;
    private static decimal? DecimalOrNull(JsonElement item, string property) => decimal.TryParse(Text(item, property), NumberStyles.Float,
        CultureInfo.InvariantCulture, out var value) ? value : null;
    private static HashSet<string> Tokens(string value) => value.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
        .ToHashSet(StringComparer.OrdinalIgnoreCase);
    private static string Base64HmacSha256(string value, string secret)
    {
        using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(secret));
        return Convert.ToBase64String(hmac.ComputeHash(Encoding.UTF8.GetBytes(value)));
    }
}
public sealed class BitgetExchangeAdapter(HttpClient http, Func<ExchangeId, ExchangeCredentials> credentials) : ReadOnlyGuardedAdapter(http, credentials)
{ public override ExchangeId Id => ExchangeId.Bitget; protected override string PublicProbeUrl => "https://api.bitget.com/api/v2/public/time"; }
public sealed class MexcExchangeAdapter(HttpClient http, Func<ExchangeId, ExchangeCredentials> credentials) : ReadOnlyGuardedAdapter(http, credentials)
{ public override ExchangeId Id => ExchangeId.MexcFutures; protected override string PublicProbeUrl => "https://contract.mexc.com/api/v1/contract/ping"; }
public sealed class GateExchangeAdapter(HttpClient http, Func<ExchangeId, ExchangeCredentials> credentials) : ReadOnlyGuardedAdapter(http, credentials)
{
    public override ExchangeId Id => ExchangeId.GateFutures;
    protected override string PublicProbeUrl => "https://api.gateio.ws/api/v4/futures/usdt/contracts?limit=1";
    public override string MapOkxSymbol(string okxInstrumentId) => okxInstrumentId.Split('-')[0] + "_USDT";
}
public sealed class BingXExchangeAdapter(HttpClient http, Func<ExchangeId, ExchangeCredentials> credentials) : ReadOnlyGuardedAdapter(http, credentials)
{ public override ExchangeId Id => ExchangeId.BingX; protected override string PublicProbeUrl => "https://open-api.bingx.com/openApi/swap/v2/server/time"; }
public sealed class CoinExExchangeAdapter(HttpClient http, Func<ExchangeId, ExchangeCredentials> credentials) : ReadOnlyGuardedAdapter(http, credentials)
{ public override ExchangeId Id => ExchangeId.CoinExFutures; protected override string PublicProbeUrl => "https://api.coinex.com/v2/time"; }

public static class ExchangeAdapterFactory
{
    public static IReadOnlyList<IExchangeAdapter> Create(HttpClient http, Func<ExchangeId, ExchangeCredentials> credentials) =>
    [
        new OkxExchangeAdapter(http, credentials),
        new BybitExchangeAdapter(http, credentials),
        new KuCoinExchangeAdapter(http, credentials)
    ];
}
