namespace KeytrinsMultiExchange.Core;

public static class RouteReasonCatalog
{
    public static string Explain(string reason)
    {
        if (reason.StartsWith("BYBIT_110123", StringComparison.OrdinalIgnoreCase))
            return "Bybit требует принять Trading Terms для этого торгового продукта. Заявка отклонена биржей.";
        if (reason.Equals("OKX_1", StringComparison.OrdinalIgnoreCase))
            return "Старый runtime сохранил только общий код OKX 1 без вложенного текста биржи. Для следующих отказов полный текст сохраняется.";
        if (reason.Equals("INVALID_INITIAL_STOP", StringComparison.OrdinalIgnoreCase))
            return "При котировке этой биржи безопасный начальный стоп оказался с недопустимой стороны рынка. Заявка не отправлялась.";
        if (reason.Equals("SYMBOL_NOT_AVAILABLE", StringComparison.OrdinalIgnoreCase))
            return "Контракт недоступен для этого аккаунта или сейчас не находится в торговом статусе.";
        if (reason.Equals("ACCOUNT_MODE", StringComparison.OrdinalIgnoreCase))
            return "Аккаунт или контракт не находится в обязательном One-Way режиме.";
        if (reason.Equals("FEE_RATE_UNAVAILABLE", StringComparison.OrdinalIgnoreCase))
            return "Не удалось получить подтверждённую актуальную комиссию. LIVE-вход заблокирован.";
        if (reason.Equals("COST_R", StringComparison.OrdinalIgnoreCase))
            return "Оценочные комиссии и спред превысили разрешённый Max Cost/R.";
        if (reason.Equals("QTY_MIN", StringComparison.OrdinalIgnoreCase))
            return "Рассчитанное количество меньше минимально допустимого на этой бирже.";
        if (reason.Equals("QTY_MAX_MARKET", StringComparison.OrdinalIgnoreCase))
            return "Рассчитанное количество превышает лимит рыночной заявки этой биржи.";
        if (reason.Equals("MIN_NOTIONAL", StringComparison.OrdinalIgnoreCase))
            return "Рассчитанная стоимость позиции меньше биржевого минимального notional.";
        if (reason.Equals("MAX_NOTIONAL", StringComparison.OrdinalIgnoreCase))
            return "Рассчитанная стоимость позиции превышает серверный лимит Max Notional.";
        if (reason.Equals("EXCHANGE_PAUSED", StringComparison.OrdinalIgnoreCase))
            return "Биржа была на паузе; заявка не создавалась.";
        if (reason.Equals("GLOBAL_ADMISSION_DISABLED", StringComparison.OrdinalIgnoreCase))
            return "Глобальный LIVE-контур выключен; заявка не создавалась.";
        if (reason.StartsWith("MAX_CONCURRENT_SIGNALS_", StringComparison.OrdinalIgnoreCase))
            return "Достигнут лимит одновременно сопровождаемых сигналов; заявка не создавалась.";
        if (reason.Equals("POSITION_ALREADY_OPEN", StringComparison.OrdinalIgnoreCase))
            return "На этой бирже уже существует позиция по данному контракту; новый вход заблокирован.";
        return reason;
    }
}
