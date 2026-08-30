# Trading Rules Lock — server parity baseline

This is the behavioral contract for server-v1.

## Universe
- Bybit USDT linear perpetual crypto only.
- Current static crypto whitelist from `BybitClient.CRYPTO_BASES`.
- `minAgeDays = 30`.
- `minTurnoverUsdt = 5,000,000`.
- sort by 24h turnover descending.
- top-N, default `30`.
- refresh about hourly.

## Decision timing
- position management about every 5 sec;
- one scan after each closed M15, roughly 8 sec after close;
- only fully closed H1/M15 candles may be used for signals.

## H1 trend
Parameters: EMA50, EMA200, EMA50 slope over 3 H1 bars, ADX(14) >= 22.

LONG:
- EMA50 > EMA200;
- EMA50 now > EMA50 3 bars ago;
- ADX >= 22;
- H1 close > EMA50.

SHORT: exact inverse.

## M15 pullback
EMA20/EMA50, lookback 4. One of the prior closed M15 bars must overlap the EMA20-EMA50 zone.

## M15 confirmation
LONG latest closed M15:
- close > open;
- close > previous high;
- close > EMA20.

SHORT:
- close < open;
- close < previous low;
- close < EMA20.

## Initial stop
ATR(14), multiplier 1.2, swing lookback 5.

LONG: `min(entry - 1.2*ATR, min(low last 5 M15))`.
SHORT: `max(entry + 1.2*ATR, max(high last 5 M15))`.

## Sizing / entry
- default `1R = 3 USDT`;
- leverage 5x;
- max notional 1000 USDT;
- `desiredQty = riskUsdt / abs(entry-stop)`;
- honor qtyStep/minQty/maxMarketQty/minNotional;
- Market order;
- One-Way mode, `positionIdx=0`;
- initial exchange-side SL with MarkPrice trigger;
- one position per symbol;
- no duplicate entry for same signal timestamp.

## Cost/R gate
Estimated round-trip taker fees + spread. Reject if `costR > 0.25`.

## Structural break
Last closed M15 versus previous 3 M15 bars.

LONG break:
- last close < minimum low of previous 3;
- last close < last open.

SHORT break: inverse.

Fresh window about 20 minutes.

## Current loss control
- `r <= -0.20R` + fresh structural break => reduce-only close **85%** of current position;
- no re-add;
- `r <= -0.35R` => reduce-only close **100% of all remaining size**, with or without structural break;
- initial exchange stop remains emergency protection.

## Positive-side control
- `+1.5R` => BE adjusted for fee/spread and 2 ticks;
- from `+2R` trailing active;
- `>= +2R` floor `+1R`;
- `>= +2.5R` floor `+2R` and trail `1.6 ATR`;
- `>= +3R` floor `+2.25R`;
- before +2.5R trail uses 2.2 ATR;
- choose the more protective stop between ATR candidate and R-floor;
- stop never moves backward;
- no fixed TP.

Do not alter any of these numbers in server-v1 without a separate explicit strategy change.