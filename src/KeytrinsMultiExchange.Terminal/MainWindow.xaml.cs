using System.Collections.ObjectModel;
using System.Data;
using System.Globalization;
using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using Line = System.Windows.Shapes.Line;
using Polyline = System.Windows.Shapes.Polyline;
using Rectangle = System.Windows.Shapes.Rectangle;

namespace KeytrinsMultiExchange.Terminal;

public partial class MainWindow : Window
{
    private readonly HttpClient _http = new() { Timeout = TimeSpan.FromSeconds(15) };
    private readonly System.Windows.Threading.DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(5) };
    private string _baseUrl = string.Empty;
    private string _token = string.Empty;
    private bool _settingsLoaded;
    private bool _updatingStrategyPicker;
    private JsonElement? _strategyChartPayload;
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
            await LoadStrategyOverviewAsync();
            ConnectionText.Text = "ONLINE"; ConnectionBadge.Background = new SolidColorBrush(Color.FromRgb(18, 86, 55));
        }
        catch (Exception exception)
        {
            ConnectionText.Text = exception is HttpRequestException { StatusCode: System.Net.HttpStatusCode.Unauthorized } ? "UNAUTHORIZED" : "OFFLINE";
            ConnectionBadge.Background = new SolidColorBrush(Color.FromRgb(105, 32, 45));
        }
    }

    private async Task LoadStrategyOverviewAsync()
    {
        using var overview = await GetAsync("/api/strategy/overview");
        var symbols = overview.RootElement.EnumerateArray().Select(x => Text(x, "symbol")).Where(x => x.Length > 0).ToArray();
        if (symbols.Length == 0) return;
        var selected = StrategySymbolPicker.SelectedItem?.ToString();
        _updatingStrategyPicker = true;
        StrategySymbolPicker.ItemsSource = symbols;
        StrategySymbolPicker.SelectedItem = selected is not null && symbols.Contains(selected) ? selected : symbols[0];
        _updatingStrategyPicker = false;
        await LoadStrategyChartAsync();
    }

    private async Task LoadStrategyChartAsync()
    {
        var symbol = StrategySymbolPicker.SelectedItem?.ToString();
        if (string.IsNullOrWhiteSpace(symbol)) return;
        using var document = await GetAsync("/api/strategy/chart?symbol=" + Uri.EscapeDataString(symbol));
        _strategyChartPayload = document.RootElement.Clone();
        RenderStrategySummary(_strategyChartPayload.Value);
        DrawStrategyChart(_strategyChartPayload.Value);
    }

    private async void StrategySymbolPicker_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (_updatingStrategyPicker) return;
        try { await LoadStrategyChartAsync(); } catch { }
    }

    private void StrategyChartCanvas_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (_strategyChartPayload is { } payload) DrawStrategyChart(payload);
    }

    private void RenderStrategySummary(JsonElement payload)
    {
        var chart = payload.GetProperty("chart");
        var decision = Text(chart, "decision");
        var signal = chart.TryGetProperty("signal", out var signalValue) && signalValue.ValueKind == JsonValueKind.Object
            ? signalValue : default;
        StrategyDecisionText.Text = $"{Text(chart, "symbol")}: {StrategyDecisionLabel(decision)} • " +
            $"H1 тренд: {YesNo(Flag(chart, "h1TrendPassed"))} • M15 возврат: {YesNo(Flag(chart, "pullbackPassed"))} • " +
            $"M15 подтверждение: {YesNo(Flag(chart, "confirmationPassed"))} • ADX {NumberText(chart, "adx")} / минимум {NumberText(chart, "adxMinimum")}" +
            (signal.ValueKind == JsonValueKind.Object
                ? $" • базовый сигнал {Text(signal, "baseSignalDirection")} • фактический вход {Text(signal, "actualDirection")} • score {NumberText(signal, "score")}" : "");
        var management = payload.GetProperty("management");
        var positionLines = payload.GetProperty("positions").EnumerateArray().Select(position =>
            $"{Text(position, "exchange")}: entry {NumberText(position, "entryPrice")}, mark {NumberText(position, "markPrice")}, " +
            $"hard stop {NumberText(position, "hardLossStop")}, current stop {NumberText(position, "currentStop")}, " +
            $"peak NET {NumberText(position, "peakNetProfitUsdt")}, protected NET {NumberText(position, "protectedNetProfitUsdt")}").ToArray();
        var locks = management.GetProperty("dollarLock").EnumerateArray().Select(level =>
            $"peak +{NumberText(level, "peakNet")} → защита +{NumberText(level, "protectedNet")}");
        StrategyManagementText.Text = $"Удержание: до аварийного NET-убытка −{NumberText(management, "maxNetLossUsdt")} USDT или подтверждённого защитного стопа. " +
            $"Dollar Lock: {string.Join(" • ", locks)}.\n" +
            (positionLines.Length == 0 ? "Активной позиции терминала по этому сигналу сейчас нет." : string.Join("\n", positionLines));
    }

    private void DrawStrategyChart(JsonElement payload)
    {
        var width = StrategyChartCanvas.ActualWidth;
        var height = StrategyChartCanvas.ActualHeight;
        if (width < 180 || height < 140) return;
        StrategyChartCanvas.Children.Clear();
        var chart = payload.GetProperty("chart");
        var points = chart.GetProperty("points").EnumerateArray().Select(point => new ChartPoint(
            Number(point, "startMs"), Number(point, "open"), Number(point, "high"), Number(point, "low"),
            Number(point, "close"), NullableNumber(point, "emaFast"), NullableNumber(point, "emaSlow"))).ToArray();
        if (points.Length == 0) return;
        var levels = new List<ChartLevel>();
        if (chart.TryGetProperty("signal", out var signal) && signal.ValueKind == JsonValueKind.Object)
        {
            levels.Add(new(Number(signal, "okxEntryRef"), "ВХОД", Brush("#FFE16A")));
            levels.Add(new(Number(signal, "okxStopRef"), "СТОП", Brush("#FF6F80")));
        }
        foreach (var position in payload.GetProperty("positions").EnumerateArray())
        {
            var stop = Number(position, "currentStop");
            if (stop > 0) levels.Add(new(stop, Text(position, "exchange") + " ЗАЩИТА", Brush("#73F0AA")));
        }
        var prices = points.SelectMany(x => new[] { x.Low, x.High }).Concat(levels.Select(x => x.Value)).ToArray();
        var minimum = prices.Min(); var maximum = prices.Max();
        var padding = Math.Max((maximum - minimum) * 0.08, Math.Abs(maximum == 0 ? 1 : maximum) * 0.001);
        minimum -= padding; maximum += padding;
        const double left = 66, right = 125, top = 18, bottom = 32;
        var plotWidth = Math.Max(10, width - left - right); var plotHeight = Math.Max(10, height - top - bottom);
        double X(int index) => left + (index + 0.5) * plotWidth / points.Length;
        double Y(double value) => top + (maximum - value) / (maximum - minimum) * plotHeight;
        for (var index = 0; index <= 5; index++)
        {
            var price = maximum - (maximum - minimum) * index / 5; var y = Y(price);
            AddLine(left, y, width - right, y, Brush("#17364B"));
            AddLabel(price.ToString("G7", CultureInfo.InvariantCulture), 4, y - 8, Brush("#91A9BF"));
        }
        var candleWidth = Math.Max(2, Math.Min(8, plotWidth / points.Length * 0.62));
        for (var index = 0; index < points.Length; index++)
        {
            var point = points[index]; var color = point.Close >= point.Open ? Brush("#50D99A") : Brush("#FF6F80");
            AddLine(X(index), Y(point.High), X(index), Y(point.Low), color);
            var body = new Rectangle { Width = candleWidth, Height = Math.Max(1, Math.Abs(Y(point.Open) - Y(point.Close))), Fill = color };
            Canvas.SetLeft(body, X(index) - candleWidth / 2); Canvas.SetTop(body, Math.Min(Y(point.Open), Y(point.Close)));
            StrategyChartCanvas.Children.Add(body);
        }
        AddSeries(points, x => x.EmaFast, Brush("#67D8FF"), X, Y);
        AddSeries(points, x => x.EmaSlow, Brush("#FFB45C"), X, Y);
        foreach (var level in levels)
        {
            var y = Y(level.Value); AddLine(left, y, width - right, y, level.Brush, [6, 4]);
            AddLabel($"{level.Label} {level.Value:G7}", width - right + 7, y - 8, level.Brush);
        }
        AddLine(left, top, width - right, top, Brush("#2B5671"));
        AddLine(left, top + plotHeight, width - right, top + plotHeight, Brush("#2B5671"));
        AddLine(left, top, left, top + plotHeight, Brush("#2B5671"));
        AddLine(width - right, top, width - right, top + plotHeight, Brush("#2B5671"));
    }

    private void AddSeries(IReadOnlyList<ChartPoint> points, Func<ChartPoint, double?> selector, Brush brush,
        Func<int, double> x, Func<double, double> y)
    {
        var line = new Polyline { Stroke = brush, StrokeThickness = 1.6 };
        for (var index = 0; index < points.Count; index++)
            if (selector(points[index]) is { } value) line.Points.Add(new Point(x(index), y(value)));
        StrategyChartCanvas.Children.Add(line);
    }

    private void AddLine(double x1, double y1, double x2, double y2, Brush brush, DoubleCollection? dash = null)
    {
        StrategyChartCanvas.Children.Add(new Line { X1 = x1, Y1 = y1, X2 = x2, Y2 = y2,
            Stroke = brush, StrokeThickness = 1, StrokeDashArray = dash });
    }

    private void AddLabel(string text, double left, double top, Brush brush)
    {
        var label = new TextBlock { Text = text, Foreground = brush, FontSize = 11 };
        Canvas.SetLeft(label, left); Canvas.SetTop(label, top); StrategyChartCanvas.Children.Add(label);
    }

    private static Brush Brush(string color) => new SolidColorBrush((Color)ColorConverter.ConvertFromString(color));
    private static double Number(JsonElement item, string property) =>
        item.TryGetProperty(property, out var value) && value.TryGetDouble(out var number) ? number : 0;
    private static double? NullableNumber(JsonElement item, string property) =>
        item.TryGetProperty(property, out var value) && value.ValueKind == JsonValueKind.Number && value.TryGetDouble(out var number) ? number : null;
    private static string NumberText(JsonElement item, string property) =>
        item.TryGetProperty(property, out var value) && value.ValueKind == JsonValueKind.Number && value.TryGetDouble(out var number)
            ? number.ToString("G8", CultureInfo.InvariantCulture) : "—";
    private static string StrategyDecisionLabel(string reason) => reason switch
    {
        "SIGNAL" => "СИГНАЛ СОЗДАН",
        "NO_H1_TREND" => "нет подтверждённого тренда H1",
        "NO_M15_PULLBACK" => "не было возврата M15 в зону EMA 20/50",
        "NO_M15_CONFIRMATION" => "нет подтверждающей свечи M15",
        "NOT_ENOUGH_BARS" => "недостаточно закрытых свечей",
        "INDICATOR_NAN" => "индикаторы ещё не рассчитаны",
        "INVALID_STOP" => "получен недопустимый структурный стоп",
        _ => reason
    };

    private sealed record ChartPoint(double StartMs, double Open, double High, double Low, double Close,
        double? EmaFast, double? EmaSlow);
    private sealed record ChartLevel(double Value, string Label, Brush Brush);

    private async Task LoadSettingsAsync(bool forceValues = false)
    {
        using var settings = await GetAsync("/api/settings");
        var root = settings.RootElement;
        var runtime = root.GetProperty("runtime");
        if (!_settingsLoaded || forceValues)
        {
            RiskUsdtInput.Text = runtime.GetProperty("riskUsdt").ToString();
            PositionNotionalUsdtInput.Text = runtime.GetProperty("positionNotionalUsdt").ToString();
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
            !TryDecimal(PositionNotionalUsdtInput.Text, out var positionNotionalUsdt) ||
            !TryDecimal(MaxNetLossUsdtInput.Text, out var maxNetLossUsdt) ||
            !int.TryParse(UniverseSizeInput.Text, out var universe) ||
            !int.TryParse(LeverageInput.Text, out var leverage) ||
            !TryDecimal(MaxNotionalInput.Text, out var maxNotional) ||
            !TryDecimal(MaxCostRInput.Text, out var maxCostR))
        { RuntimeSettingsResult.Text = "Проверьте числовые значения."; return; }
        try
        {
            using var _ = await SendJsonAsync(HttpMethod.Put, "/api/settings/runtime", new
            { riskUsdt = risk, positionNotionalUsdt, maxNetLossUsdt, universeSize = universe, leverage, maxNotionalUsdt = maxNotional, maxCostR });
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
