using System.Text.Json;
using System.Text.Json.Serialization;

namespace KeytrinsMultiExchange.Core;

public sealed class RuntimeSettingsStore
{
    private readonly object _gate = new();
    private readonly string _path;
    private RuntimeOptions _current;
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true,
        Converters = { new JsonStringEnumConverter() }
    };

    public RuntimeSettingsStore(RuntimeOptions defaults, string dataDirectory)
    {
        Directory.CreateDirectory(dataDirectory);
        _path = Path.Combine(dataDirectory, "runtime-settings.json");
        _current = Clone(defaults);
        if (File.Exists(_path))
        {
            var persisted = JsonSerializer.Deserialize<RuntimeOptions>(File.ReadAllText(_path), Json);
            if (persisted is not null)
            {
                // Settings written by v1.1.x do not contain the independently configurable position amount.
                if (persisted.PositionNotionalUsdt <= 0m)
                    persisted.PositionNotionalUsdt = defaults.PositionNotionalUsdt > 0m
                        ? defaults.PositionNotionalUsdt
                        : 100m;
                // LIVE admission is never restored from a client-editable settings file.
                persisted.TradingEnabled = defaults.TradingEnabled;
                persisted.OkxExclusiveWriterConfirmed = defaults.OkxExclusiveWriterConfirmed;
                _current = persisted;
            }
        }
    }

    public RuntimeOptions Current { get { lock (_gate) return Clone(_current); } }

    public RuntimeOptions Update(decimal riskUsdt, decimal positionNotionalUsdt, int universeSize, int leverage, decimal maxNotionalUsdt,
        decimal maxCostR, decimal maxNetLossUsdt)
    {
        if (riskUsdt is < 0.10m or > 100m) throw new ArgumentOutOfRangeException(nameof(riskUsdt));
        if (positionNotionalUsdt is < 10m or > 10_000m) throw new ArgumentOutOfRangeException(nameof(positionNotionalUsdt));
        if (universeSize is < 1 or > 100) throw new ArgumentOutOfRangeException(nameof(universeSize));
        if (leverage is < 1 or > 20) throw new ArgumentOutOfRangeException(nameof(leverage));
        if (maxNotionalUsdt is < 10m or > 10_000m) throw new ArgumentOutOfRangeException(nameof(maxNotionalUsdt));
        if (maxCostR is <= 0m or > 1m) throw new ArgumentOutOfRangeException(nameof(maxCostR));
        if (maxNetLossUsdt is < 0.05m or > 100m) throw new ArgumentOutOfRangeException(nameof(maxNetLossUsdt));
        lock (_gate)
        {
            var next = Clone(_current);
            next.RiskUsdt = riskUsdt; next.PositionNotionalUsdt = positionNotionalUsdt;
            next.UniverseSize = universeSize; next.Leverage = leverage;
            next.MaxNotionalUsdt = maxNotionalUsdt; next.MaxCostR = maxCostR;
            next.MaxNetLossUsdt = maxNetLossUsdt;
            _current = next; SaveLocked(); return Clone(_current);
        }
    }

    public RuntimeOptions SetExchangeMode(ExchangeId exchange, ExchangeMode mode)
    {
        lock (_gate)
        {
            var next = Clone(_current); next.Exchanges[exchange.ToString()] = mode; _current = next;
            SaveLocked(); return Clone(_current);
        }
    }

    private void SaveLocked()
    {
        var temporary = _path + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(_current, Json));
        File.Move(temporary, _path, true);
    }

    private static RuntimeOptions Clone(RuntimeOptions value) => new()
    {
        TradingEnabled = value.TradingEnabled,
        OkxExclusiveWriterConfirmed = value.OkxExclusiveWriterConfirmed,
        RiskUsdt = value.RiskUsdt, PositionNotionalUsdt = value.PositionNotionalUsdt, Leverage = value.Leverage,
        MaxNotionalUsdt = value.MaxNotionalUsdt, MaxCostR = value.MaxCostR,
        MaxNetLossUsdt = value.MaxNetLossUsdt, UniverseSize = value.UniverseSize,
        MinTurnoverUsdt = value.MinTurnoverUsdt, SignalStaleSeconds = value.SignalStaleSeconds,
        MaxConcurrentSignals = value.MaxConcurrentSignals,
        ExecutionSlippageBufferBps = value.ExecutionSlippageBufferBps, DataDirectory = value.DataDirectory,
        Exchanges = new Dictionary<string, ExchangeMode>(value.Exchanges, StringComparer.OrdinalIgnoreCase)
    };
}
