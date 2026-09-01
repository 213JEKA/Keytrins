namespace KeytrinsMultiExchange.Core;

public sealed class PendingDisableCoordinator(
    TradingDatabase database,
    RuntimeSettingsStore settings,
    RuntimeSnapshot snapshot)
{
    public async Task CompleteConfirmedFlatAsync(CancellationToken cancellationToken)
    {
        var current = settings.Current;
        var disabling = current.Exchanges
            .Where(x => x.Value == ExchangeMode.Disabling && Enum.TryParse<ExchangeId>(x.Key, true, out _))
            .Select(x => Enum.Parse<ExchangeId>(x.Key, true))
            .ToArray();
        if (disabling.Length == 0) return;

        var open = await database.LoadOpenManagedPositionsAsync(cancellationToken);
        foreach (var exchange in disabling.Where(exchange => open.All(position => position.Exchange != exchange)))
        {
            settings.SetExchangeMode(exchange, ExchangeMode.Off);
            if (snapshot.Exchanges.TryGetValue(exchange, out var existing))
                snapshot.Exchanges[exchange] = existing with
                {
                    Mode = ExchangeMode.Off,
                    LastActivity = DateTimeOffset.UtcNow,
                    Detail = "MANAGED_FLAT_CONFIRMED_OFF"
                };
            await database.AppendLogAsync("DISABLE_COMPLETE", "MANAGED_FLAT_CONFIRMED_OFF",
                exchange.ToString(), null, cancellationToken);
        }
    }
}
