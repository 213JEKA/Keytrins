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
- Android v0.1.3.5 position management targets about every **1 sec** and is interleaved through a long M15 universe scan so the scan does not intentionally pause protection for the full scan duration;
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

## Primary positive-side control: $0.50 step lock
Android v0.1.3.5 uses a dollar-step lock based on a separately persisted maximum observed open-position PnL.

Definitions:
- `peakProfitUsdt` is monotonic: it may only increase and is persisted with the trade state so restart must not forget a previously reached higher profit;
- Android updates it from observed Bybit unrealized PnL / mark-derived gross PnL during position management;
- for an upgraded position that was already open before v0.1.3.5, persisted favorable high/low-water is used as a migration lower bound for the peak;
- `step = 0.50 USDT`;
- `lag = 0.50 USDT`;
- `targetProtectedUsd = max(0, floor(peakProfitUsdt / 0.50) * 0.50 - 0.50)`.

Behavior:
- peak reaches about `+0.50 USDT` => exchange stop moves to BE plus estimated round-trip fee/spread/tick costs;
- peak reaches `+1.00 USDT` => target protection about `+0.50 USDT`;
- peak reaches `+1.50 USDT` => target protection about `+1.00 USDT`;
- peak reaches `+2.00 USDT` => target protection about `+1.50 USDT`;
- continue indefinitely in `0.50 USDT` steps with constant `0.50 USDT` lag;
- stop is quantized to exchange tick size;
- stop may never move backward;
- if a desired stop would already be beyond the current mark, do not place an invalid stop;
- Android also persists an estimated `protectedProfitUsdt` derived from the actually accepted exchange stop, and exposes `Peak / Protected` in the dashboard.

The current Android implementation estimates per-unit costs as:
`entryPrice * (2 * takerFee) + spreadAtEntry + 2*tickSize`
and adds that cost allowance to the protected dollar floor when converting protected USDT to stop price.

Important execution limitation of the Android runtime:
- it is still REST polling, not a tick-by-tick stream;
- a very fast favorable spike and retrace between observations can still be missed;
- server runtime should eventually use a more continuous market-data/execution stream, while preserving the same economic dollar-lock rule.

## Additional high-R protection
The previous high-R ATR/R-floor layer remains as an additional protection layer only. Whichever rule yields the more protective valid stop wins because the stop never loosens.

- from `+2R` ATR/R-floor trailing may activate;
- `>= +2R` R-floor `+1R`;
- `>= +2.5R` R-floor `+2R` and trail `1.6 ATR`;
- `>= +3R` R-floor `+2.25R`;
- before +2.5R ATR trail uses 2.2 ATR;
- no fixed TP.

Do not alter these rules in server-v1 without a separate explicit strategy change.
