namespace KeytrinsMultiExchange.Core;

public static class RiskController
{
    public static decimal EstimateNetPnl(TradeDirection direction, decimal entry, decimal exit, decimal quantity,
        decimal entryFee, decimal exitFeeRate, decimal funding = 0m)
    {
        var gross = direction == TradeDirection.Long ? (exit - entry) * quantity : (entry - exit) * quantity;
        var exitFee = exit * quantity * exitFeeRate;
        return gross - entryFee - exitFee + funding;
    }

    public static decimal RequiredStopForNet(TradeDirection direction, decimal entry, decimal quantity,
        decimal entryFee, decimal exitFeeRate, decimal desiredNet, decimal tickSize, decimal spread,
        decimal slippageBufferBps)
    {
        if (quantity <= 0 || tickSize <= 0) throw new ArgumentOutOfRangeException(nameof(quantity));
        var slippage = entry * slippageBufferBps / 10_000m;
        decimal raw;
        if (direction == TradeDirection.Long)
        {
            raw = (desiredNet + entry * quantity + entryFee) / (quantity * (1m - exitFeeRate));
            raw += spread + slippage;
            return CeilingToTick(raw, tickSize);
        }
        raw = (entry * quantity - entryFee - desiredNet) / (quantity * (1m + exitFeeRate));
        raw -= spread + slippage;
        return FloorToTick(raw, tickSize);
    }

    public static decimal HardLossStop(ManagedPosition position, decimal maxLoss, decimal slippageBufferBps) =>
        RequiredStopForNet(position.Direction, position.EntryPrice, position.RemainingQuantity * position.ContractValue, position.EntryFee,
            position.TakerFeeRate, -Math.Abs(maxLoss), position.TickSize, position.Spread, slippageBufferBps);

    public static decimal ProtectedProfitForPeak(decimal peakNet)
    {
        if (peakNet < 1.30m) return 0m;
        if (peakNet < 2.00m) return 1.00m;
        return 1.50m + decimal.Floor((peakNet - 2.00m) / 0.50m) * 0.50m;
    }

    public static decimal MoreProtective(TradeDirection direction, decimal first, decimal second) =>
        first <= 0m ? second : second <= 0m ? first :
        direction == TradeDirection.Long ? Math.Max(first, second) : Math.Min(first, second);

    public static bool WouldLoosen(TradeDirection direction, decimal current, decimal proposed) => current > 0 &&
        (direction == TradeDirection.Long ? proposed < current : proposed > current);

    public static decimal FloorToTick(decimal value, decimal tick) => decimal.Floor(value / tick) * tick;
    public static decimal CeilingToTick(decimal value, decimal tick) => decimal.Ceiling(value / tick) * tick;
}
