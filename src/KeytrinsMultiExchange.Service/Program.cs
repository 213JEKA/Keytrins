using System.Net;
using System.Text.Json.Serialization;
using KeytrinsMultiExchange.Core;
using KeytrinsMultiExchange.Service;
using Microsoft.Extensions.Options;

var builder = WebApplication.CreateBuilder(args);
if (OperatingSystem.IsWindows()) builder.Host.UseWindowsService(options => options.ServiceName = "KeytrinsMultiExchange");
builder.Configuration.AddEnvironmentVariables("KEYTRINS_");
builder.Services.Configure<RuntimeOptions>(builder.Configuration.GetSection(RuntimeOptions.SectionName));
builder.Services.ConfigureHttpJsonOptions(options => options.SerializerOptions.Converters.Add(new JsonStringEnumConverter()));
builder.Services.AddSingleton<RuntimeSnapshot>();
builder.Services.AddSingleton(new HttpClient(new SocketsHttpHandler
{
    AutomaticDecompression = DecompressionMethods.All,
    PooledConnectionLifetime = TimeSpan.FromMinutes(10),
    ConnectTimeout = TimeSpan.FromSeconds(10)
}) { Timeout = TimeSpan.FromSeconds(20) });
builder.Services.AddSingleton(sp => new RuntimeSettingsStore(sp.GetRequiredService<IOptions<RuntimeOptions>>().Value, ResolveDataDirectory(sp)));
builder.Services.AddSingleton(sp => new CredentialVault(ResolveDataDirectory(sp)));
builder.Services.AddSingleton(sp => new TradingDatabase(ResolveDataDirectory(sp)));
builder.Services.AddSingleton(sp => new OkxMarketDataClient(sp.GetRequiredService<HttpClient>()));
builder.Services.AddSingleton<IReadOnlyList<IExchangeAdapter>>(sp => ExchangeAdapterFactory.Create(
    sp.GetRequiredService<HttpClient>(), sp.GetRequiredService<CredentialVault>().Get));
builder.Services.AddSingleton(sp => new OkxLiveExecutionTransport(sp.GetRequiredService<HttpClient>(), sp.GetRequiredService<CredentialVault>().Get));
builder.Services.AddSingleton(sp => new BybitLiveExecutionTransport(sp.GetRequiredService<HttpClient>(), sp.GetRequiredService<CredentialVault>().Get));
builder.Services.AddSingleton(sp => new KuCoinLiveExecutionTransport(sp.GetRequiredService<HttpClient>(), sp.GetRequiredService<CredentialVault>().Get));
builder.Services.AddSingleton<IReadOnlyList<ILiveExecutionTransport>>(sp =>
[
    sp.GetRequiredService<OkxLiveExecutionTransport>(), sp.GetRequiredService<BybitLiveExecutionTransport>(),
    sp.GetRequiredService<KuCoinLiveExecutionTransport>()
]);
builder.Services.AddSingleton<IWriterAuditSource>(sp => sp.GetRequiredService<OkxLiveExecutionTransport>());
builder.Services.AddSingleton(sp => new ExternalWriterGuard(
    sp.GetRequiredService<IWriterAuditSource>(),
    sp.GetRequiredService<RuntimeSettingsStore>().Current.OkxExclusiveWriterConfirmed));
builder.Services.AddSingleton<ExchangeOperationLocks>();
builder.Services.AddSingleton(sp => new ExecutionCoordinator(sp.GetRequiredService<TradingDatabase>(),
    sp.GetRequiredService<IReadOnlyList<ILiveExecutionTransport>>(),
    sp.GetRequiredService<ExchangeOperationLocks>()));
builder.Services.AddSingleton(sp => new PositionManager(sp.GetRequiredService<TradingDatabase>(),
    sp.GetRequiredService<IReadOnlyList<ILiveExecutionTransport>>(),
    sp.GetRequiredService<ExchangeOperationLocks>(), sp.GetRequiredService<RuntimeSnapshot>()));
builder.Services.AddSingleton<PendingDisableCoordinator>();
builder.Services.AddHostedService<TradingRuntimeWorker>();
builder.Services.AddHostedService<PositionManagementWorker>();

var app = builder.Build();
await app.Services.GetRequiredService<TradingDatabase>().InitializeAsync(CancellationToken.None);
app.UseDefaultFiles();
app.UseStaticFiles();

var controlToken = Environment.GetEnvironmentVariable("KEYTRINS_CONTROL_TOKEN") ?? string.Empty;
app.Use(async (context, next) =>
{
    if (!context.Request.Path.StartsWithSegments("/api") || context.Request.Path.StartsWithSegments("/api/health"))
    { await next(); return; }
    var local = IPAddress.IsLoopback(context.Connection.RemoteIpAddress ?? IPAddress.None);
    var bearer = context.Request.Headers.Authorization.ToString();
    var authorized = controlToken.Length >= 32 && bearer.Equals($"Bearer {controlToken}", StringComparison.Ordinal);
    if (!authorized && !(controlToken.Length == 0 && local)) { context.Response.StatusCode = 401; return; }
    await next();
});

app.MapGet("/api/health", async (RuntimeSnapshot state, RuntimeSettingsStore settings, TradingDatabase database,
    CancellationToken ct) =>
{
    var unresolved = await database.CountUnresolvedExecutionCommandsAsync(ct);
    var unresolvedRisk = await database.CountUnresolvedRiskActionsAsync(ct);
    var managedOpen = await database.CountOpenManagedPositionsAsync(ct);
    var exchangeOpen = state.Exchanges.Values.Sum(x => x.OpenPositionCount);
    var unmanagedOpen = Math.Max(0, exchangeOpen - managedOpen);
    return Results.Ok(new
    {
        status = state.MasterHealth == "ERROR" || unresolved > 0 || unresolvedRisk > 0 || unmanagedOpen > 0 ||
                 state.WriterExclusivity != "EXCLUSIVE" ? "degraded" : "ready",
        version = state.Version,
        uptimeSeconds = (long)(DateTimeOffset.UtcNow - state.StartedAt).TotalSeconds,
        masterSignalSource = "OKX",
        masterHealth = state.MasterHealth,
        executionRecovery = unresolved == 0 && unresolvedRisk == 0 ? "READY" : "RECONCILE_REQUIRED",
        unresolvedExecutionCommands = unresolved,
        unresolvedRiskActions = unresolvedRisk,
        managedOpenPositions = managedOpen,
        exchangeOpenPositions = exchangeOpen,
        unmanagedExchangePositions = unmanagedOpen,
        writerExclusivity = state.WriterExclusivity,
        writerExclusivityDetail = state.WriterExclusivityDetail,
        tradingEnabled = settings.Current.TradingEnabled,
        mutationGate = settings.Current.TradingEnabled ? "CONFIGURED" : "DISARMED"
    });
});
app.MapGet("/api/status", (RuntimeSnapshot state, RuntimeSettingsStore settings) => Results.Ok(new
{
    state.Version, state.StartedAt, state.MasterHealth, state.MasterDetail, state.UniverseCount,
    state.LastScanAt, state.LastSignalId, state.LastSignalAt, state.WriterExclusivity, state.WriterExclusivityDetail,
    tradingEnabled = settings.Current.TradingEnabled,
    mutationGate = settings.Current.TradingEnabled ? "ARMED" : "DISARMED",
    exchanges = state.Exchanges.Values.OrderBy(x => x.Exchange).ToArray(),
    lastRouteAttempts = state.LastRouteAttempts.Values.OrderBy(x => x.Exchange).ToArray(),
    positions = state.Positions.Values.OrderBy(x => x.Exchange).ThenBy(x => x.Symbol).ToArray()
}));
app.MapGet("/api/history", async (TradingDatabase db, int? limit, CancellationToken ct) =>
    Results.Ok(await db.QueryAsync("route_attempts", limit ?? 100, ct)));
app.MapGet("/api/signals", async (TradingDatabase db, int? limit, CancellationToken ct) =>
    Results.Ok(await db.QueryAsync("canonical_signals", limit ?? 100, ct)));
app.MapGet("/api/logs", async (TradingDatabase db, int? limit, CancellationToken ct) =>
    Results.Ok(await db.QueryAsync("event_log", limit ?? 200, ct)));
app.MapGet("/api/execution/commands", async (TradingDatabase db, int? limit, CancellationToken ct) =>
    Results.Ok(await db.QueryAsync("execution_commands", limit ?? 100, ct)));
app.MapGet("/api/execution/risk-actions", async (TradingDatabase db, int? limit, CancellationToken ct) =>
    Results.Ok(await db.QueryAsync("risk_actions", limit ?? 100, ct)));
app.MapGet("/api/execution/exchange-truth", async (IReadOnlyList<ILiveExecutionTransport> transports, CancellationToken ct) =>
{
    var output = new List<object>();
    foreach (var transport in transports)
    {
        try
        {
            var positions = await transport.GetOpenPositionsAsync(ct);
            output.Add(new { exchange = transport.Id, positions });
        }
        catch (Exception exception)
        {
            output.Add(new { exchange = transport.Id, error = exception.GetType().Name + ":" + exception.Message });
        }
    }
    return Results.Ok(output);
});
app.MapGet("/api/execution/okx/order-audit", async (string? symbol,
    IReadOnlyList<ILiveExecutionTransport> transports, CancellationToken ct) =>
{
    var okx = transports.OfType<OkxLiveExecutionTransport>().Single();
    return Results.Ok(await okx.GetRecentOrderAuditAsync(symbol, ct));
});
app.MapGet("/api/settings", (RuntimeSettingsStore settings, CredentialVault vault) => Results.Ok(new
{
    runtime = settings.Current,
    credentials = vault.Status()
}));
app.MapPut("/api/settings/runtime", (RuntimeSettingsRequest request, RuntimeSettingsStore settings) =>
{
    try { return Results.Ok(settings.Update(request.RiskUsdt, request.UniverseSize, request.Leverage,
        request.MaxNotionalUsdt, request.MaxCostR, request.MaxNetLossUsdt)); }
    catch (ArgumentOutOfRangeException exception) { return Results.BadRequest(new { reason = "INVALID_SETTING", field = exception.ParamName }); }
});
app.MapPut("/api/settings/credentials/{exchange}", async (string exchange, CredentialRequest request, CredentialVault vault,
    IReadOnlyList<IExchangeAdapter> adapters, RuntimeSnapshot state, RuntimeSettingsStore settings, CancellationToken ct) =>
{
    if (!Enum.TryParse<ExchangeId>(exchange, true, out var id)) return Results.NotFound(new { reason = "UNKNOWN_EXCHANGE" });
    try
    {
        vault.Set(id, request.ApiKey, request.ApiSecret, request.Passphrase ?? string.Empty);
        settings.SetExchangeMode(id, ExchangeMode.Paused);
        var adapter = adapters.Single(x => x.Id == id);
        var snapshot = await adapter.ReadOnlyPreflightAsync(ExchangeMode.Paused, ct);
        state.Exchanges[id] = snapshot;
        var verified = snapshot.PrivateAuthenticated && snapshot.TradingPermission && !snapshot.WithdrawPermission;
        return Results.Ok(new { exchange = id, stored = true, verified, snapshot.Detail });
    }
    catch (ArgumentException) { return Results.BadRequest(new { reason = "API_KEY_AND_SECRET_REQUIRED" }); }
});
app.MapDelete("/api/settings/credentials/{exchange}", (string exchange, CredentialVault vault) =>
{
    if (!Enum.TryParse<ExchangeId>(exchange, true, out var id)) return Results.NotFound(new { reason = "UNKNOWN_EXCHANGE" });
    vault.Clear(id); return Results.Ok(new { exchange = id, configured = vault.Get(id).IsPresent, environmentFallback = vault.Get(id).IsPresent });
});
app.MapPost("/api/exchanges/{exchange}/pause", (string exchange, RuntimeSnapshot state, RuntimeSettingsStore settings) => Control.SetMode(exchange, ExchangeMode.Paused, state, settings));
app.MapPost("/api/exchanges/{exchange}/enable", (string exchange, RuntimeSnapshot state, RuntimeSettingsStore settings) => Control.SetMode(exchange, ExchangeMode.Active, state, settings));
app.MapPost("/api/exchanges/enable-selected", async (ExchangeSelectionRequest request, RuntimeSnapshot state,
    RuntimeSettingsStore settings, IReadOnlyList<IExchangeAdapter> adapters, TradingDatabase database,
    ExternalWriterGuard writerGuard, CancellationToken ct) =>
    await Control.EnableSelectedAsync(request.Exchanges, state, settings, adapters, database, writerGuard, ct));
app.MapPost("/api/exchanges/{exchange}/close", (string exchange) => Results.Conflict(new { reason = "NO_MANAGED_POSITION_OR_MUTATION_GATE_DISARMED", exchange }));
app.MapPost("/api/exchanges/{exchange}/close-all-disable", async (string exchange, RuntimeSnapshot state,
    RuntimeSettingsStore settings, PositionManager manager, PendingDisableCoordinator disableCoordinator,
    CancellationToken ct) =>
    await Control.CloseAllDisableAsync(exchange, state, settings, manager, disableCoordinator, ct));
app.MapFallbackToFile("index.html");
app.Run();

static string ResolveDataDirectory(IServiceProvider services)
{
    var options = services.GetRequiredService<IOptions<RuntimeOptions>>().Value;
    var configured = Environment.GetEnvironmentVariable("KEYTRINS_DATA_DIR");
    return string.IsNullOrWhiteSpace(configured) ? Path.GetFullPath(options.DataDirectory) : configured;
}

static class Control
{
    public static async Task<IResult> CloseAllDisableAsync(string name, RuntimeSnapshot state,
        RuntimeSettingsStore settings, PositionManager manager, PendingDisableCoordinator disableCoordinator,
        CancellationToken cancellationToken)
    {
        if (!Enum.TryParse<ExchangeId>(name, true, out var id))
            return Results.NotFound(new { reason = "UNKNOWN_EXCHANGE" });
        settings.SetExchangeMode(id, ExchangeMode.Disabling);
        if (state.Exchanges.TryGetValue(id, out var current))
            state.Exchanges[id] = current with { Mode = ExchangeMode.Disabling, LastActivity = DateTimeOffset.UtcNow,
                Detail = "CLOSE_ALL_REQUESTED" };
        var requested = await manager.RequestCloseExchangeAsync(id, "MANUAL_CLOSE_ALL_DISABLE", cancellationToken);
        await disableCoordinator.CompleteConfirmedFlatAsync(cancellationToken);
        var mode = settings.Current.Exchanges[id.ToString()];
        if (state.Exchanges.TryGetValue(id, out current) && mode == ExchangeMode.Disabling)
            state.Exchanges[id] = current with { Mode = mode, LastActivity = DateTimeOffset.UtcNow,
                Detail = $"CLOSE_ALL_DISABLE_PENDING_FLAT:{requested}" };
        return Results.Accepted(value: new { exchange = id, mode, closeRequests = requested,
            pendingFlat = mode == ExchangeMode.Disabling });
    }

    public static IResult SetMode(string name, ExchangeMode mode, RuntimeSnapshot state, RuntimeSettingsStore settings)
    {
        if (!Enum.TryParse<ExchangeId>(name, true, out var id)) return Results.NotFound(new { reason = "UNKNOWN_EXCHANGE" });
        if (!state.Exchanges.TryGetValue(id, out var current)) return Results.Conflict(new { reason = "EXCHANGE_NOT_INITIALIZED" });
        if (mode == ExchangeMode.Active && !settings.Current.TradingEnabled)
            return Results.Conflict(new { reason = "GLOBAL_TRADING_DISABLED", exchange = id });
        if (mode == ExchangeMode.Active && (!current.PrivateAuthenticated || !current.TradingPermission || current.WithdrawPermission))
            return Results.Conflict(new { reason = "PRIVATE_PREFLIGHT_NOT_VERIFIED", exchange = id, current.Detail });
        if (mode == ExchangeMode.Off && current.OpenPositionCount > 0) return Results.Conflict(new { reason = "OPEN_POSITIONS_REQUIRE_CONFIRMED_CLOSE" });
        settings.SetExchangeMode(id, mode);
        state.Exchanges[id] = current with { Mode = mode, LastActivity = DateTimeOffset.UtcNow, Detail = $"MANUAL_{mode.ToString().ToUpperInvariant()}" };
        return Results.Ok(state.Exchanges[id]);
    }

    public static async Task<IResult> EnableSelectedAsync(string[] names, RuntimeSnapshot state, RuntimeSettingsStore settings,
        IReadOnlyList<IExchangeAdapter> adapters, TradingDatabase database, ExternalWriterGuard writerGuard,
        CancellationToken cancellationToken)
    {
        var selected = new HashSet<ExchangeId>();
        foreach (var name in names.Distinct(StringComparer.OrdinalIgnoreCase))
        {
            if (!Enum.TryParse<ExchangeId>(name, true, out var id)) return Results.BadRequest(new { reason = "UNKNOWN_EXCHANGE", exchange = name });
            selected.Add(id);
        }
        if (selected.Count == 0) return Results.BadRequest(new { reason = "NO_EXCHANGES_SELECTED" });
        if (!selected.Contains(ExchangeId.Okx))
            return Results.BadRequest(new { reason = "OKX_MASTER_REQUIRED",
                detail = "OKX is the canonical signal source and must be selected with any follower exchange." });

        var checks = new List<ExchangeSnapshot>();
        foreach (var adapter in adapters.Where(x => selected.Contains(x.Id)))
        {
            var snapshot = await adapter.ReadOnlyPreflightAsync(ExchangeMode.Paused, cancellationToken);
            state.Exchanges[adapter.Id] = snapshot;
            checks.Add(snapshot);
        }
        var failed = checks.Where(x => !x.PublicConnected || !x.PrivateAuthenticated || !x.TradingPermission ||
                                       x.WithdrawPermission).ToArray();
        if (failed.Length > 0)
            return Results.Conflict(new { reason = "PRIVATE_PREFLIGHT_FAILED", detail = "Credentials and One-Way mode are required.",
                exchanges = failed.Select(x => new { x.Exchange, x.OpenPositionCount, x.Detail }).ToArray() });
        var nonFlat = checks.Where(x => x.OpenPositionCount != 0).ToArray();
        if (nonFlat.Length > 0)
            return Results.Conflict(new { reason = "EXCHANGE_NOT_FLAT",
                detail = "Existing exchange positions are not adopted or managed by this runtime.",
                exchanges = nonFlat.Select(x => new { x.Exchange, x.OpenPositionCount, x.Detail }).ToArray() });
        var writer = await writerGuard.CheckAsync(cancellationToken);
        state.WriterExclusivity = writer.IsExclusive ? "EXCLUSIVE" :
            writer.LatestForeignEntryAt is null ? "QUIET_WINDOW_NOT_EXCLUSIVE" : "FOREIGN_WRITER_ACTIVE";
        state.WriterExclusivityDetail = writer.Detail;
        if (!writer.IsExclusive)
            return Results.Conflict(new { reason = writer.LatestForeignEntryAt is null
                    ? "OKX_EXCLUSIVE_WRITER_NOT_CONFIRMED" : "FOREIGN_OKX_WRITER_ACTIVE", writer.Detail,
                writer.LatestForeignEntryAt, writer.LatestForeignClientId });
        if (!settings.Current.TradingEnabled)
            return Results.Conflict(new { reason = "GLOBAL_TRADING_DISABLED", detail = "Credentials verified; LIVE mutation gate is not armed." });
        var unresolvedExecution = await database.CountUnresolvedExecutionCommandsAsync(cancellationToken);
        var unresolvedRisk = await database.CountUnresolvedRiskActionsAsync(cancellationToken);
        if (state.MasterHealth != "ONLINE" || unresolvedExecution != 0 || unresolvedRisk != 0)
            return Results.Conflict(new { reason = "EXECUTION_RECOVERY_NOT_READY", state.MasterHealth,
                unresolvedExecutionCommands = unresolvedExecution, unresolvedRiskActions = unresolvedRisk });

        foreach (var id in selected)
        {
            settings.SetExchangeMode(id, ExchangeMode.Active);
            state.Exchanges[id] = state.Exchanges[id] with { Mode = ExchangeMode.Active, LastActivity = DateTimeOffset.UtcNow, Detail = "BATCH_ACTIVE" };
        }
        return Results.Ok(new { enabled = selected.OrderBy(x => x).ToArray(), tradingEnabled = true });
    }
}

sealed record RuntimeSettingsRequest(decimal RiskUsdt, int UniverseSize, int Leverage, decimal MaxNotionalUsdt,
    decimal MaxCostR, decimal MaxNetLossUsdt);
sealed record CredentialRequest(string ApiKey, string ApiSecret, string? Passphrase);
sealed record ExchangeSelectionRequest(string[] Exchanges);
