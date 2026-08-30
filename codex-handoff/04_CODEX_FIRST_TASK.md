# Codex First Task — Server Migration Phase 1

You are migrating the existing Android Bybit trading runtime to a server.

## Source of truth
Repository: `213JEKA/Keytrins`
Branch: `live-research-android-v01`
Read all files under `codex-handoff/` before editing code.

The Android implementation is the behavioral reference. Do **not** redesign the strategy in Phase 1.

## Immediate objective
Create a new `server/` project that can run in OBSERVE mode and prove strategy parity before any live server order is allowed.

Recommended stack:
- Python 3.12
- asyncio
- FastAPI
- PostgreSQL
- SQLAlchemy 2 / asyncpg or equivalent
- Alembic
- pytest
- Docker / docker-compose

Equivalent technology is acceptable only if it improves reliability without changing semantics.

## Phase 1 deliverables
1. Scaffold `server/` with clear module boundaries for Bybit, market data, strategy, execution, risk, storage, API and telemetry.
2. Port indicators and `StrategyEngine` semantics exactly.
3. Port universe eligibility and ranking exactly.
4. Port sizing, initial-stop calculation and cost/R calculation exactly.
5. Implement persistent strategy config versions.
6. Implement schema/migrations for at least:
   - strategy_config_versions
   - universe_snapshots
   - scan_runs
   - scan_decisions
   - signals
   - entry_intents
   - orders
   - fills
   - trade_cycles
   - trade_legs
   - position_snapshots
   - risk_actions
   - account_snapshots
   - events
7. Build fixture-driven parity tests comparing the documented Android semantics with the server result.
8. Implement health/readiness endpoints.
9. Implement OBSERVE runtime that scans only fully closed H1/M15 candles and persists decisions but cannot submit a live order.
10. Dockerize it and document local startup.

## Do not do yet
- Do not enable LIVE server trading.
- Do not alter H1/M15 entry conditions.
- Do not tune thresholds based on recent PnL.
- Do not replace REST with WebSockets before parity tests exist.
- Do not delete/refactor the Android trading code yet.
- Do not put API secrets in repo files or tests.

## After Phase 1 passes
Proceed to Phase 2 only after showing the parity test report. Phase 2 is idempotent execution, reconciliation and restart recovery. Phase 3 is Android thin-client cutover.

## Required report at the end of each Codex iteration
State explicitly:
- files changed;
- tests run and results;
- whether entry parity is exact;
- any known semantic differences from Android;
- whether any code path can place a real order;
- next smallest safe step.

If any requirement is ambiguous, prefer preserving the Android behavior and document the ambiguity rather than inventing new trading behavior.