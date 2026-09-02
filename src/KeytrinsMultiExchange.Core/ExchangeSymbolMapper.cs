namespace KeytrinsMultiExchange.Core;

public static class ExchangeSymbolMapper
{
    public static string Map(ExchangeId exchange, string okxInstrumentId)
    {
        if (exchange == ExchangeId.Okx) return okxInstrumentId;
        var baseAsset = okxInstrumentId.Split('-', StringSplitOptions.RemoveEmptyEntries)[0]
            .ToUpperInvariant();
        return exchange switch
        {
            ExchangeId.Bybit => baseAsset + "USDT",
            ExchangeId.KuCoinFutures => (baseAsset == "BTC" ? "XBT" : baseAsset) + "USDTM",
            ExchangeId.Bitget or ExchangeId.MexcFutures or ExchangeId.BingX => baseAsset + "USDT",
            ExchangeId.GateFutures or ExchangeId.CoinExFutures => baseAsset + "_USDT",
            _ => throw new ArgumentOutOfRangeException(nameof(exchange), exchange, null)
        };
    }
}
