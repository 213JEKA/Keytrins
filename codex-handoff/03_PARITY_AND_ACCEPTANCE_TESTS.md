# Parity & Acceptance Tests

Server-v1 is not done when it merely starts. It is done after behavioral parity and recovery tests pass.

## Strategy parity fixtures
For saved H1 + M15 OHLCV fixtures compare Android semantics vs server semantics:
- H1 trend direction;
- NO_H1_TREND;
- NO_M15_PULLBACK;
- NO_M15_CONFIRMATION;
- LONG/SHORT signal;
- ATR;
- stop reference;
- signal timestamp.

## Universe parity
On saved instruments/tickers fixtures verify the same:
- eligible symbols;
- crypto whitelist/USDT/linear/age filters;
- turnover ordering;
- top-N.

## Sizing/cost parity
Test qtyStep/minQty/maxMarketQty/minNotional/maxNotional and cost/R rejection.

## Risk tests
1. -0.19R + break => no reduction.
2. -0.20R + fresh break => close about 85%.
3. -0.20R without break => no early reduction.
4. -0.35R => full close even without break.
5. after 85% reduction, later -0.35R => close all remainder.
6. no re-add.
7. +1.5R => BE.
8. +2R => floor +1R.
9. +2.5R => floor +2R and 1.6 ATR.
10. +3R => floor +2.25R.
11. stop never moves backward.

## Restart/fault injection
Inject restart:
- after signal;
- after order POST before response;
- after fill;
- after 85% reduction;
- after BE;
- during trailing;
- after exchange close before local persistence.

Requirements:
- no duplicate entry;
- no duplicate reduction;
- no accidental reversal/increase;
- recovered position resumes management;
- closed cycle eventually persists.

Simulate DNS errors, connection timeouts, post-submit timeout/unknown outcome, 5xx, Bybit retCode errors and stale market data. No silent fallback.

## Trade-cycle accounting
- partial exits remain legs of the current open cycle;
- only the final flat state completes the cycle;
- aggregate all legs into one cycle;
- persist realized PnL, fees and funding separately;
- `last 3` means 3 completed cycles, not 3 fills/closed-pnl rows.

## Deployment readiness
Must pass Docker build, fresh migrations, restart recovery, health/readiness endpoints, structured logs with trade/order/symbol IDs, UTC timestamps, secret scanning and zero dependency on a phone process.