using System.Collections.ObjectModel;
using System.Data;
using System.Globalization;
using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Media;

namespace KeytrinsMultiExchange.Terminal;

public partial class MainWindow : Window
{
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(15) };
    private readonly System.Windows.Threading.DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(5) };
    private string _baseUrl = string.Empty;
    private string _token = string.Empty;
    private bool _settingsLoaded;
    private static readonly string[] Exchanges = ["Okx", "Bybit", "KuCoinFutures"];

    public MainWindow()
    {
        InitializeComponent();
        CredentialExchange.ItemsSource = Exchanges;
        CredentialExchange.SelectedIndex = 0;
        _timer.Tick += async (_, _) => await RefreshAsync();
        Loaded += async (_, _) =>
        {
            var directory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Keytrins", "MultiExchangeTerminal");
            var settings = Path.Combine(directory, "terminal.txt");
            if (File.Exists(settings)) ServerUrl.Text = File.ReadAllText(settings).Trim();
            var tokenFile = Path.Combine(directory, "control.token");
            if (File.Exists(tokenFile)) ControlToken.Password = File.ReadAllText(tokenFile).Trim();
            _baseUrl = ServerUrl.Text.Trim().TrimEnd('/');
            _token = ControlToken.Password.Trim();
            await RefreshAsync();
            _timer.Start();
        };
    }

    private async void Connect_Click(object sender, RoutedEventArgs e)
    {
        _baseUrl = ServerUrl.Text.Trim().TrimEnd('/'); _token = ControlToken.Password.Trim();
        var directory = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Keytrins", "MultiExchangeTerminal");
        Directory.CreateDirectory(directory); File.WriteAllText(Path.Combine(directory, "terminal.txt"), _baseUrl);
        await RefreshAsync(); _timer.Start();
    }

    private HttpRequestMessage Request(HttpMethod method, string path)
    {
        var request = new HttpRequestMessage(method, _baseUrl + path);
        if (_token.Length > 0) request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", _token);
        return request;
    }

    private async Task<JsonDocument> GetAsync(string path)
    {
        using var request = Request(HttpMethod.Get, path); using var response = await _http.SendAsync(request);
        response.EnsureSuccessStatusCode(); return JsonDocument.Parse(await response.Content.ReadAsStringAsync());
    }

    private async Task<JsonDocument> SendJsonAsync(HttpMethod method, string path, object? body = null)
    {
        using var request = Request(method, path);
        if (body is not null)
            request.Content = new StringContent(JsonSerializer.Serialize(body), Encoding.UTF8, "application/json");
        using var response = await _http.SendAsync(request);
        var content = await response.Content.ReadAsStringAsync();
        if (!response.IsSuccessStatusCode) throw new HttpRequestException(UserMessage(content), null, response.StatusCode);
        return JsonDocument.Parse(content);
    }

    private static string UserMessage(string content)
    {
        try
        {
            using var document = JsonDocument.Parse(content);
            var reason = document.RootElement.TryGetProperty("reason", out var value) ? value.GetString() : null;
            return reason switch
            {
                "GLOBAL_TRADING_DISABLED" => "LIVE-исполнение ещё не запущено на сервере. Ключи проверены, но модуль реальных заявок пока заблокирован.",
                "OKX_EXCLUSIVE_WRITER_NOT_CONFIRMED" => "Старый OKX-клиент пока не исключён. Отключите его или ограничьте этот API-ключ IP-адресом 37.252.21.226.",
                "EXCHANGE_NOT_FLAT" => "На выбранной бирже уже есть позиция, открытая не этим сервером. Сначала остановите старый клиент и штатно закройте его позицию.",
                "PRIVATE_PREFLIGHT_FAILED" => "Одна или несколько выбранных бирж не прошли проверку ключа, торгового разрешения или One-Way режима.",
                "EXECUTION_RECOVERY_NOT_READY" => "Сервер ещё не завершил восстановление заявок и позиций. Входы не включены.",
                "FOREIGN_OKX_WRITER_ACTIVE" => "Обнаружена другая программа, которая продолжает отправлять реальные OKX-заявки. Все новые входы оставлены на паузе.",
                "NO_EXCHANGES_SELECTED" => "Отметьте хотя бы одну биржу.",
                "OKX_MASTER_REQUIRED" => "OKX — источник сигнала и обязательный лидер. Отметьте OKX вместе с нужными ведомыми биржами.",
                _ => string.IsNullOrWhiteSpace(reason) ? "Сервер отклонил операцию." : reason
            };
        }
        catch (JsonException) { return "Сервер отклонил операцию."; }
    }

    private async Task RefreshAsync()
    {
        if (_baseUrl.Length == 0) return;
        try
        {
            using var status = await GetAsync("/api/status");
            var root = status.RootElement;
            var liveEnabled = root.TryGetProperty("tradingEnabled", out var live) && live.GetBoolean();
            var selected = ExchangeGrid.ItemsSource is IEnumerable<ExchangeRow> existing
                ? existing.Where(x => x.Selected).Select(x => x.Exchange).ToHashSet(StringComparer.OrdinalIgnoreCase)
                : new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            var lastAttempts = root.TryGetProperty("lastRouteAttempts", out var attempts)
                ? attempts.EnumerateArray().ToDictionary(x => Text(x, "exchange"), StringComparer.OrdinalIgnoreCase)
                : new Dictionary<string, JsonElement>(StringComparer.OrdinalIgnoreCase);
            var sessionStartedAt = root.GetProperty("startedAt").GetDateTimeOffset();
            ExchangeGrid.ItemsSource = ExchangeRows(root.GetProperty("exchanges"), lastAttempts, selected,
                liveEnabled, sessionStartedAt);
            PositionGrid.ItemsSource = PositionRows(root.GetProperty("positions"), root.GetProperty("externalPositions"));
            MasterStatus.Text = (root.GetProperty("masterHealth").GetString() ?? "—") +
                (liveEnabled ? " • LIVE ВКЛЮЧЁН" : " • LIVE ЗАБЛОКИРОВАН");
            UniverseStatus.Text = "Universe " + root.GetProperty("universeCount");
            LastSignalStatus.Text = "last signal " + (root.GetProperty("lastSignalId").GetString() ?? "—");
            ServerInfo.Text = JsonSerializer.Serialize(root, new JsonSerializerOptions { WriteIndented = true });
            using var history = await GetAsync("/api/history?limit=100"); HistoryGrid.ItemsSource = Rows(history.RootElement);
            using var logs = await GetAsync("/api/logs?limit=200"); LogGrid.ItemsSource = Rows(logs.RootElement);
            await LoadSettingsAsync();
            ConnectionText.Text = "ONLINE"; ConnectionBadge.Background = new SolidColorBrush(Color.FromRgb(18, 86, 55));
        }
        catch (Exception exception)
        {
            ConnectionText.Text = exception is HttpRequestException { StatusCode: System.Net.HttpStatusCode.Unauthorized } ? "UNAUTHORIZED" : "OFFLINE";
            ConnectionBadge.Background = new SolidColorBrush(Color.FromRgb(105, 32, 45));
        }
    }

    private async Task LoadSettingsAsync(bool forceValues = false)
    {
        using var settings = await GetAsync("/api/settings");
        var root = settings.RootElement;
        var runtime = root.GetProperty("runtime");
        if (!_settingsLoaded || forceValues)
        {
            RiskUsdtInput.Text = runtime.GetProperty("riskUsdt").ToString();
            MaxNetLossUsdtInput.Text = runtime.GetProperty("maxNetLossUsdt").ToString();
            UniverseSizeInput.Text = runtime.GetProperty("universeSize").ToString();
            LeverageInput.Text = runtime.GetProperty("leverage").ToString();
            MaxNotionalInput.Text = runtime.GetProperty("maxNotionalUsdt").ToString();
            MaxCostRInput.Text = runtime.GetProperty("maxCostR").ToString();
            _settingsLoaded = true;
        }
        var statuses = new ObservableCollection<string>();
        var credentials = root.GetProperty("credentials");
        foreach (var exchange in Exchanges)
        {
            var configured = credentials.TryGetProperty(exchange, out var value) && value.GetBoolean();
            statuses.Add($"{exchange}: {(configured ? "сохранён на сервере" : "нет ключа")}");
        }
        CredentialStatusList.ItemsSource = statuses;
    }

    private static bool TryDecimal(string text, out decimal value) =>
        decimal.TryParse(text, NumberStyles.Number, CultureInfo.CurrentCulture, out value) ||
        decimal.TryParse(text, NumberStyles.Number, CultureInfo.InvariantCulture, out value);

    private async void SaveRuntime_Click(object sender, RoutedEventArgs e)
    {
        if (!TryDecimal(RiskUsdtInput.Text, out var risk) ||
            !TryDecimal(MaxNetLossUsdtInput.Text, out var maxNetLossUsdt) ||
            !int.TryParse(UniverseSizeInput.Text, out var universe) ||
            !int.TryParse(LeverageInput.Text, out var leverage) ||
            !TryDecimal(MaxNotionalInput.Text, out var maxNotional) ||
            !TryDecimal(MaxCostRInput.Text, out var maxCostR))
        { RuntimeSettingsResult.Text = "Проверьте числовые значения."; return; }
        try
        {
            using var _ = await SendJsonAsync(HttpMethod.Put, "/api/settings/runtime", new
            { riskUsdt = risk, maxNetLossUsdt, universeSize = universe, leverage, maxNotionalUsdt = maxNotional, maxCostR });
            RuntimeSettingsResult.Text = "Сохранено на сервере.";
            await LoadSettingsAsync(true);
        }
        catch (Exception exception) { RuntimeSettingsResult.Text = exception.Message; }
    }

    private async void SaveCredentials_Click(object sender, RoutedEventArgs e)
    {
        var exchange = CredentialExchange.SelectedItem?.ToString();
        if (string.IsNullOrWhiteSpace(exchange)) return;
        try
        {
            using var saved = await SendJsonAsync(HttpMethod.Put, $"/api/settings/credentials/{exchange}", new
            { apiKey = ApiKeyInput.Password, apiSecret = ApiSecretInput.Password, passphrase = PassphraseInput.Password });
            ApiKeyInput.Clear(); ApiSecretInput.Clear(); PassphraseInput.Clear();
            var verified = saved.RootElement.GetProperty("verified").GetBoolean();
            var detail = saved.RootElement.GetProperty("detail").GetString() ?? "";
            CredentialSettingsResult.Text = verified
                ? $"{exchange}: ключ проверен и готов."
                : $"{exchange}: сохранён, но не проверен — {detail}";
            await LoadSettingsAsync();
        }
        catch (Exception exception) { CredentialSettingsResult.Text = exception.Message; }
    }

    private async void ClearCredentials_Click(object sender, RoutedEventArgs e)
    {
        var exchange = CredentialExchange.SelectedItem?.ToString();
        if (string.IsNullOrWhiteSpace(exchange) || MessageBox.Show($"{exchange}: удалить сохранённый ключ?", "Подтверждение", MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes) return;
        try
        {
            using var _ = await SendJsonAsync(HttpMethod.Delete, $"/api/settings/credentials/{exchange}");
            CredentialSettingsResult.Text = $"{exchange}: сохранённый ключ удалён.";
            await LoadSettingsAsync();
        }
        catch (Exception exception) { CredentialSettingsResult.Text = exception.Message; }
    }

    private static DataView Rows(JsonElement array)
    {
        var table = new DataTable();
        foreach (var item in array.EnumerateArray())
        {
            foreach (var property in item.EnumerateObject())
                if (!table.Columns.Contains(property.Name)) table.Columns.Add(property.Name, typeof(string));
            var row = table.NewRow();
            foreach (var property in item.EnumerateObject())
                row[property.Name] = property.Value.ValueKind == JsonValueKind.String ? property.Value.GetString() ?? "" : property.Value.ToString();
            table.Rows.Add(row);
        }
        return table.DefaultView;
    }

    private static DataView PositionRows(JsonElement managed, JsonElement external)
    {
        var table = new DataTable();
        table.Columns.Add("ownership", typeof(string));
        AddPositionRows(table, managed, "УПРАВЛЯЕТСЯ ТЕРМИНАЛОМ");
        AddPositionRows(table, external, "ВНЕШНЯЯ • ТОЛЬКО НАБЛЮДЕНИЕ");
        return table.DefaultView;
    }

    private static void AddPositionRows(DataTable table, JsonElement array, string ownership)
    {
        foreach (var item in array.EnumerateArray())
        {
            foreach (var property in item.EnumerateObject())
                if (!table.Columns.Contains(property.Name)) table.Columns.Add(property.Name, typeof(string));
            var row = table.NewRow();
            row["ownership"] = ownership;
            foreach (var property in item.EnumerateObject())
                row[property.Name] = property.Value.ValueKind == JsonValueKind.String
                    ? property.Value.GetString() ?? ""
                    : property.Value.ToString();
            table.Rows.Add(row);
        }
    }

    private static ObservableCollection<ExchangeRow> ExchangeRows(JsonElement array,
        IReadOnlyDictionary<string, JsonElement> lastAttempts, HashSet<string> selected, bool liveEnabled,
        DateTimeOffset sessionStartedAt)
    {
        var rows = new ObservableCollection<ExchangeRow>();
        foreach (var item in array.EnumerateArray())
        {
            var exchange = Text(item, "exchange");
            var authenticated = Flag(item, "privateAuthenticated");
            var trading = Flag(item, "tradingPermission");
            var withdraw = Flag(item, "withdrawPermission");
            var keyReady = authenticated && trading && !withdraw;
            var readiness = !keyReady ? "КЛЮЧ НЕ ПРОВЕРЕН" : liveEnabled ? "LIVE ГОТОВА" : "КЛЮЧ ГОТОВ • LIVE ЗАКРЫТ";
            lastAttempts.TryGetValue(exchange, out var lastAttempt);
            var hasAttempt = lastAttempt.ValueKind == JsonValueKind.Object;
            var historicalAttempt = hasAttempt && lastAttempt.TryGetProperty("receivedAt", out var receivedAt) &&
                                    receivedAt.GetDateTimeOffset() < sessionStartedAt;
            var mode = Text(item, "mode");
            rows.Add(new ExchangeRow
            {
                Selected = selected.Contains(exchange), Exchange = exchange,
                Mode = mode == "Disabling" ? "ЗАКРЫТИЕ…" : mode, Readiness = readiness,
                Public = YesNo(Flag(item, "publicConnected")), Private = YesNo(authenticated), Trade = YesNo(trading),
                Withdraw = YesNo(withdraw), Positions = Text(item, "openPositionCount"),
                LastResult = historicalAttempt ? "ОЖИДАЕТ НОВЫЙ СИГНАЛ"
                    : hasAttempt ? Text(lastAttempt, "result") : "ОЖИДАЕТ НОВЫЙ СИГНАЛ",
                LastReason = historicalAttempt
                    ? "ИСТОРИЯ ДО ТЕКУЩЕГО ЗАПУСКА: " + Text(lastAttempt, "reasonExplanation") +
                      $" [код: {Text(lastAttempt, "reason")}]"
                    : hasAttempt
                        ? Text(lastAttempt, "reasonExplanation") + $" [код: {Text(lastAttempt, "reason")}]"
                        : "Новых попыток после запуска ещё не было.",
                Detail = Text(item, "detail")
            });
        }
        return rows;
    }

    private static string Text(JsonElement item, string property) => item.TryGetProperty(property, out var value)
        ? value.ValueKind == JsonValueKind.String ? value.GetString() ?? "" : value.ToString()
        : "";
    private static bool Flag(JsonElement item, string property) => item.TryGetProperty(property, out var value) && value.ValueKind == JsonValueKind.True;
    private static string YesNo(bool value) => value ? "ДА" : "НЕТ";

    private async Task SendCommandAsync(string action)
    {
        var exchanges = ExchangeGrid.ItemsSource is IEnumerable<ExchangeRow> rows
            ? rows.Where(x => x.Selected).Select(x => x.Exchange).ToArray() : [];
        if (exchanges.Length == 0) { MessageBox.Show("Отметьте галочками нужные биржи.", "Биржи не выбраны"); return; }
        if (action == "close-all-disable" && MessageBox.Show($"Закрыть и отключить: {string.Join(", ", exchanges)}?", "Подтверждение", MessageBoxButton.YesNo, MessageBoxImage.Warning) != MessageBoxResult.Yes) return;
        try
        {
            if (action == "enable")
            {
                using var _ = await SendJsonAsync(HttpMethod.Post, "/api/exchanges/enable-selected", new { exchanges });
            }
            else
            {
                foreach (var exchange in exchanges)
                {
                    using var request = Request(HttpMethod.Post, $"/api/exchanges/{exchange}/{action}");
                    using var response = await _http.SendAsync(request);
                    if (!response.IsSuccessStatusCode) throw new HttpRequestException(UserMessage(await response.Content.ReadAsStringAsync()), null, response.StatusCode);
                }
            }
        }
        catch (Exception exception) { MessageBox.Show(exception.Message, "Операция не выполнена"); }
        await RefreshAsync();
    }

    private async void Pause_Click(object sender, RoutedEventArgs e) => await SendCommandAsync("pause");
    private async void Enable_Click(object sender, RoutedEventArgs e) => await SendCommandAsync("enable");
    private async void CloseAll_Click(object sender, RoutedEventArgs e) => await SendCommandAsync("close-all-disable");
}

public sealed class ExchangeRow
{
    public bool Selected { get; set; }
    public string Exchange { get; set; } = "";
    public string Mode { get; set; } = "";
    public string Readiness { get; set; } = "";
    public string Public { get; set; } = "";
    public string Private { get; set; } = "";
    public string Trade { get; set; } = "";
    public string Withdraw { get; set; } = "";
    public string Positions { get; set; } = "";
    public string LastResult { get; set; } = "";
    public string LastReason { get; set; } = "";
    public string Detail { get; set; } = "";
}
