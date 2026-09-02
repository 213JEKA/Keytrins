namespace KeytrinsMultiExchange.Core;

public static class EntryPlanner
{
    public static PreparedEntry Plan(ExchangeId exchange, CanonicalSignal signal, string targetSymbol,
        MarketQuote quote, InstrumentRules rules, decimal takerFeeRate, RuntimeOptions options)
    {
        if (signal.OkxRiskDistancePct <= 0 || signal.OkxRiskDistancePct >= 1)
            throw new ExecutionRejectedException("INVALID_OKX_RISK_DISTANCE");
        if (quote.Bid <= 0 || quote.Ask <= 0 || quote.Ask < quote.Bid || quote.Mark <= 0)
            throw new ExecutionRejectedException("STALE_TARGET_MARKET");
        if (quote.IsStale(TimeSpan.FromSeconds(10), DateTimeOffset.UtcNow))
            throw new ExecutionRejectedException("STALE_TARGET_MARKET");
        if (takerFeeRate <= 0 || takerFeeRate >= 0.01m)
            throw new ExecutionRejectedException("FEE_RATE_UNAVAILABLE");
        if (rules.TickSize <= 0 || rules.QtyStep <= 0 || rules.ContractValue <= 0)
            throw new ExecutionRejectedException("INVALID_INSTRUMENT_RULES");

        var direction = signal.ActualDirection;
        var referencePrice = direction == TradeDirection.Long ? quote.Ask : quote.Bid;
        var requestedNotional = options.PositionNotionalUsdt;
        if (requestedNotional <= 0m) throw new ExecutionRejectedException("POSITION_NOTIONAL_INVALID");
        var desiredBaseQuantity = requestedNotional / referencePrice;
        var desiredContracts = desiredBaseQuantity / rules.ContractValue;
        var quantity = FloorToStep(desiredContracts, rules.QtyStep);
        if (quantity < rules.MinQty || quantity <= 0) throw new ExecutionRejectedException("QTY_MIN");
        if (quantity > rules.MaxMarketQty) throw new ExecutionRejectedException("QTY_MAX_MARKET");

        var baseQuantity = quantity * rules.ContractValue;
        var notional = baseQuantity * referencePrice;
        if (notional < rules.MinNotional) throw new ExecutionRejectedException("MIN_NOTIONAL");
        if (notional > options.MaxNotionalUsdt) throw new ExecutionRejectedException("MAX_NOTIONAL");

        var spread = quote.Ask - quote.Bid;
        var estimatedRoundTripCost = notional * takerFeeRate * 2m + spread * baseQuantity;
        var costR = estimatedRoundTripCost / Math.Abs(options.MaxNetLossUsdt);
        if (costR > options.MaxCostR) throw new ExecutionRejectedException("COST_R");

        var mirroredRaw = direction == TradeDirection.Long
            ? referencePrice * (1m - (decimal)signal.OkxRiskDistancePct)
            : referencePrice * (1m + (decimal)signal.OkxRiskDistancePct);
        var mirrored = direction == TradeDirection.Long
            ? RiskController.CeilingToTick(mirroredRaw, rules.TickSize)
            : RiskController.FloorToTick(mirroredRaw, rules.TickSize);
        var entryFee = notional * takerFeeRate;
        var hard = RiskController.RequiredStopForNet(direction, referencePrice, baseQuantity, entryFee,
            takerFeeRate, -Math.Abs(options.MaxNetLossUsdt), rules.TickSize, spread,
            options.ExecutionSlippageBufferBps);
        var initial = RiskController.MoreProtective(direction, mirrored, hard);
        var valid = direction == TradeDirection.Long ? initial < quote.Bid : initial > quote.Ask;
        if (!valid) throw new ExecutionRejectedException("INVALID_INITIAL_STOP");

        return new(exchange, signal.SignalId, ExecutionIds.Entry(exchange, signal.SignalId), targetSymbol,
            direction, options.Leverage, quantity, rules.ContractValue, referencePrice, initial, mirrored, hard,
            rules.TickSize, takerFeeRate, spread, costR);
    }

    public static decimal FloorToStep(decimal value, decimal step) => decimal.Floor(value / step) * step;
}
