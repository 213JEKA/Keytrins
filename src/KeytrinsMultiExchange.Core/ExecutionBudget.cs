namespace KeytrinsMultiExchange.Core;

public static class ExecutionBudget
{
    public static void RequireAvailableMargin(PreparedEntry entry, RuntimeOptions options, decimal availableMarginUsdt)
    {
        if (availableMarginUsdt <= 0m) throw new ExecutionRejectedException("AVAILABLE_MARGIN_UNVERIFIED");
        var notional = entry.ReferencePrice * entry.Quantity * entry.ContractValue;
        var initialMargin = notional / Math.Max(1, entry.Leverage);
        var feeReserve = notional * entry.TakerFeeRate * 2m;
        var required = initialMargin + Math.Abs(options.MaxNetLossUsdt) + feeReserve;
        if (required > availableMarginUsdt)
            throw new ExecutionRejectedException("INSUFFICIENT_AVAILABLE_MARGIN_PRECHECK");
    }
}
