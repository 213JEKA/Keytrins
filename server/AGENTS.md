# Live Research Server — Codex Instructions

This directory is the server migration target for the Android Live Research Bybit trading system.

Before making changes, read:
- `../codex-handoff/00_START_HERE.md`
- `../codex-handoff/01_TRADING_RULES_LOCK.md`
- `../codex-handoff/02_SERVER_ARCHITECTURE_TARGET.md`
- `../codex-handoff/03_PARITY_AND_ACCEPTANCE_TESTS.md`
- `../codex-handoff/04_CODEX_FIRST_TASK.md`
- `../codex-handoff/05_DEFENSIVE_HEDGE_EXPERIMENT.md`

## Non-negotiable rules
- Parity before optimization.
- Phase 1 must be OBSERVE-only and incapable of placing real orders.
- Preserve Android entry-analysis semantics exactly.
- Use fully closed H1/M15 candles only.
- Never commit Bybit credentials or private keys.
- No Withdraw permission.
- All execution introduced later must be idempotent and reconciled against Bybit.
- A trade cycle is complete only when the position is flat; partial exits are legs, not separate completed trades.
- Every strategy behavior change must be explicit, versioned and tested.
- The defensive cross-exchange hedge is an EXPERIMENT, not part of the locked production strategy.
- Defensive hedge must default to `off`; first implementation is `shadow` only and must have no Exchange B live-order path.
- CONTROL and HEDGE_CANDIDATE must be evaluated in parallel on matched primary cycles. Do not silently replace the current `-0.20R/-0.35R` production loss policy.

## Engineering expectations
- UTC internally.
- Structured logs.
- Deterministic strategy functions where possible.
- Typed config and models.
- Database migrations committed.
- Unit + parity + integration + fault-injection tests.
- Dockerized reproducible startup.
- No silent error fallback.

At the end of each task, report files changed, tests run, parity status, live-order capability, known deviations, and defensive-hedge mode/capability if that module was touched.
