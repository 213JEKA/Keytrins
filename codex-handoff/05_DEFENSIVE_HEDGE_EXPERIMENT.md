# Defensive Hedge Experiment — Server Shadow Specification

## Status
EXPERIMENTAL. OFF by default. OBSERVE / shadow simulation first.

This document does **not** change the locked Android/server-v1 strategy in `01_TRADING_RULES_LOCK.md`.
The current production/control policy remains:
- `-0.20R + fresh structural break` => reduce primary position by 85%;
- `-0.35R` => close all remaining primary size.

The purpose of this experiment is to compare that control policy with a cross-exchange defensive hedge that may preserve the full primary position during a temporary adverse move.

## Core idea
Primary trade is opened by the existing strategy on Exchange A (initially Bybit).
If price moves against the primary trade by a configured fraction of R, open an opposite position on Exchange B.

The hedge is not meant to stay permanently delta-neutral. It is a temporary defensive position.
If the adverse move continues, the hedge gains value and its stop is progressively moved into profit.
If price reverses back toward the original thesis, the hedge should be stopped out with protected profit when possible, while the original primary trade remains open and can recover.

Example:
- primary LONG on Bybit;
- price moves down => open SHORT hedge on Exchange B;
- price keeps falling => SHORT hedge earns, its stop moves down into protected profit;
- price reverses up => SHORT hedge stop closes it, ideally with realized profit;
- primary LONG remains and begins recovering.

## Non-negotiable experiment rule
Do NOT combine the candidate hedge policy with the current 85% primary reduction in the same candidate simulation.
We need an apples-to-apples comparison:

A. CONTROL = current production risk policy.
B. HEDGE_CANDIDATE = preserve primary, use defensive hedge tiers, then hard kill at the configured primary loss boundary.

Both must run from the same primary entry and the same market path.

## Initial shadow parameters
All parameters must be typed/configurable and strategy-versioned. These are initial research defaults, not permanent values.

### Primary thresholds
- `hedge_stage1_trigger_r = -0.15`
- `hedge_stage1_target_fraction = 0.35` of primary initial quantity
- `hedge_stage2_trigger_r = -0.20`
- Stage 2 requires the same **fresh M15 structural break** already defined by the Android strategy.
- `hedge_stage2_target_fraction = 0.65` of primary initial quantity total, not +65% additional.
- `primary_hard_kill_r = -0.35`

At Stage 1, no primary quantity is closed in HEDGE_CANDIDATE.
At Stage 2, no primary quantity is closed in HEDGE_CANDIDATE.
At hard kill, close the entire remaining primary and close any remaining hedge so the system cannot accidentally remain with a naked reverse position.

### Hedge direction
- primary LONG => hedge SHORT
- primary SHORT => hedge LONG
- same underlying asset where possible;
- linear USDT perpetual preferred;
- if Exchange B cannot hedge the exact symbol, mark candidate as `HEDGE_UNAVAILABLE` and do not fabricate a substitute asset.

### Hedge sizing
Use target hedge quantity as a fraction of the **primary initial base-asset quantity**.
Convert/quantize to Exchange B's qty step/minimums.
Persist both requested and executable hedge fraction.
Do not silently oversize.

Stage 2 should increase hedge only by the amount needed to reach the total target fraction.
Example: initial primary qty 100; Stage 1 hedge 35; Stage 2 target 65 => add 30, not 65.

## Hedge profit protection
The hedge has its own R unit, `R_h`, defined at hedge entry and stored immutably for that hedge leg.
For shadow-v1 use a configurable hedge emergency stop distance expressed in market volatility / ATR; do not infer or overwrite the primary R.

Initial profit-protection research defaults:
- when hedge reaches `+0.50 R_h` => move hedge stop to BE + estimated hedge costs;
- when hedge reaches `+0.80 R_h` => protect at least `+0.30 R_h`;
- when hedge reaches `+1.00 R_h` => enable trailing;
- after trailing is enabled, hedge stop may only move in the profit-protecting direction;
- on market reversal, the hedge should exit through its protected stop while the primary remains open unless the primary hard-kill rule has already fired.

The exact hedge ATR/trailing multiplier is a research parameter. Do not hard-code an unexplained value. Start with a clearly named config field and report sensitivity across reasonable values before any LIVE decision.

## Candidate state machine
Recommended states:

`NO_HEDGE`
-> `HEDGE_STAGE1_INTENT`
-> `HEDGE_STAGE1_OPEN`
-> `HEDGE_STAGE2_INTENT`
-> `HEDGE_STAGE2_OPEN`
-> `HEDGE_BE`
-> `HEDGE_PROFIT_LOCK`
-> `HEDGE_TRAILING`
-> `HEDGE_CLOSED_RECOVERY`

Hard-kill path:
`ANY_OPEN_HEDGE_STATE -> PRIMARY_HARD_KILL -> FLATTEN_BOTH -> CYCLE_CLOSED`

Failure states must be explicit:
- `HEDGE_UNAVAILABLE`
- `HEDGE_REJECTED`
- `HEDGE_SUBMIT_UNKNOWN`
- `HEDGE_RECONCILE_REQUIRED`
- `HEDGE_STALE_DATA_BLOCK`

No silent fallback.

## Shadow-mode implementation
Before any Exchange B live credentials or order path exist, implement a deterministic shadow engine that consumes:
- the real primary trade state;
- primary mark-price path;
- Exchange B market-price path if available read-only;
- Exchange B instrument metadata, fees and funding if available read-only.

If Exchange B market data is unavailable, shadow results must be marked incomplete. Do not use Exchange A price as if it were exact Exchange B execution without labeling the approximation.

For each real primary cycle, run both policies in parallel:

### CONTROL
Current locked Android risk behavior.

### HEDGE_CANDIDATE
Stage-1/Stage-2 defensive hedge behavior from this document.

No shadow calculation may submit orders.

## Accounting
A hedge is part of the same economic cycle as its primary trade.
Never report hedge fills as separate completed strategy trades.

Track at minimum:
- primary realized PnL;
- primary unrealized PnL while open;
- hedge realized PnL;
- hedge unrealized PnL while open;
- primary fees;
- hedge fees;
- primary funding;
- hedge funding;
- estimated/observed slippage on both venues.

Canonical metric:

`effective_cycle_pnl = primary_realized + hedge_realized + remaining_unrealized - primary_fees - hedge_fees + primary_funding + hedge_funding`

Use the exchanges' sign conventions carefully and test them. Do not double-subtract fees if an exchange endpoint already returns net PnL.

Also persist:
- max adverse excursion of primary in R;
- max favorable excursion of primary in R;
- hedge activation time and primary R at activation;
- hedge maximum R_h;
- hedge protected/realized R_h;
- time hedge was open;
- recovery outcome of primary after hedge close.

## Required comparison metrics
For CONTROL vs HEDGE_CANDIDATE over matched cycles report:
- mean and median net cycle PnL;
- expectancy in USDT and R;
- win rate;
- mean losing-cycle PnL;
- 90th/95th percentile loss;
- max drawdown contribution;
- fee + funding drag;
- percentage of hedges that close positive;
- percentage of hedges that close negative before meaningful protection;
- percentage of primary trades that recover after hedge closes;
- percentage of cases where CONTROL cut 85% but HEDGE_CANDIDATE later recovered materially;
- incremental PnL from hedge candidate vs control per matched cycle.

Do not conclude the hedge is better from win rate alone.

## Cross-exchange execution requirements for a later LIVE phase
Do not implement LIVE until explicitly approved after shadow evidence.

When LIVE is eventually introduced:
- Exchange A and Exchange B adapters must have independent credentials;
- no Withdraw permission on either venue;
- order submission must be idempotent;
- ambiguous POST outcomes must reconcile before retry;
- serialize actions per economic cycle/symbol;
- persist every intent/order/fill before/after network boundaries as appropriate;
- hard kill must flatten both venues and verify flat state;
- if one venue is unavailable during hard kill, raise a critical state and continue reconciliation rather than assuming flat;
- never leave a hedge open after primary is flat unless an explicit recovery state documents why;
- server restart must recover both primary and hedge from exchange truth.

## Exchange B abstraction
Do not couple the strategy to a specific second exchange yet.
Create an adapter interface capable of at least:
- instrument metadata;
- mark/bid/ask;
- fee rate;
- funding rate/history;
- position state;
- create reduce-only/normal market order;
- create/update stop;
- order/fill lookup;
- reconciliation.

The concrete Exchange B will be selected/configured later.

## Acceptance tests for shadow-v1
At minimum:
1. Primary at -0.14R => no hedge.
2. Primary reaches -0.15R => Stage 1 target ~35%.
3. Stage 1 cannot duplicate on repeated ticks/restarts.
4. Primary reaches -0.20R without fresh break => no Stage 2 increase.
5. Primary reaches -0.20R with fresh break => total hedge target ~65%.
6. Stage 2 adds only the delta required to reach 65%.
7. Hedge +0.50R_h => BE+cost stop.
8. Hedge +0.80R_h => protected floor >= +0.30R_h.
9. Hedge +1.00R_h => trailing enabled.
10. Hedge stop never loosens.
11. Reversal closes hedge while primary remains open.
12. Primary recovery after hedge close is tracked.
13. Primary reaches -0.35R => candidate flattens primary and hedge.
14. No naked hedge remains after confirmed primary flat.
15. Partial fills are reconciled and quantities remain bounded.
16. Restart after Stage 1/Stage 2/profit-lock reconstructs state without duplicate action.
17. Fees/funding/slippage are included once, not twice.
18. CONTROL and HEDGE_CANDIDATE are computed from the same matched primary cycle/path.

## Deliverable requested from Codex
After base server parity is established, implement this as a separate module, suggested name:

`server/app/risk/defensive_hedge.py`

with:
- typed config;
- pure decision/state-transition core where possible;
- shadow executor only;
- persistence schema for hedge shadow states/actions;
- matched CONTROL vs HEDGE_CANDIDATE report;
- tests above;
- feature flag `DEFENSIVE_HEDGE_MODE=off|shadow|live`, with `off` default and `live` rejected/not implemented until separately approved.

At the end, Codex must explicitly state whether any code path can send an Exchange B order. For the first implementation the answer must be **NO**.
