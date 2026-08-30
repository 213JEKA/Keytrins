# Target Server Architecture

## Priority
Behavioral parity, idempotency and recovery are more important than language choice. Python 3.12 + asyncio/FastAPI/PostgreSQL is a reasonable default, but Codex may choose another server stack if it preserves semantics and operations.

## Suggested modules
```text
server/
  app/
    api/
    bybit/
    market/
    strategy/
    execution/
    risk/
    state/
    storage/
    telemetry/
  tests/
    fixtures/
    parity/
    integration/
    fault_injection/
  migrations/
  Dockerfile
  docker-compose.yml
  .env.example
  README.md
```

## Runtime ownership
The server must become the sole owner of:
- universe refresh;
- H1/M15 scans;
- signals;
- entry intents;
- orders/fills;
- open trade state;
- reductions;
- BE / profit lock / trailing;
- account reconciliation;
- completed trade cycles.

Android must not place Bybit orders after cutover.

## Market data
Phase 1 may keep REST polling for strict parity. After parity, public/private WebSockets may be added for reliability/latency, but signal decisions must remain tied to fully closed candles.

## Execution contract
Implement explicit states such as `INTENT -> SUBMITTED -> ACK/FILLED/UNKNOWN -> RECONCILED`.

Requirements:
- unique idempotent client/order IDs;
- serialize execution per symbol;
- never retry a possibly submitted order blindly after timeout;
- reconcile with Bybit before resubmission;
- reduceOnly exits;
- validate One-Way mode;
- no silent fallback.

## Canonical storage
Use PostgreSQL as source of truth.

Minimum entities:
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

A completed trade must be a canonical `trade_cycle`, not a raw Bybit Closed PnL row.

## Restart recovery
On process restart:
1. load non-final trade cycles from DB;
2. query Bybit open positions;
3. query active orders/stops;
4. reconcile local/exchange state;
5. if Bybit has a position but runtime state is incomplete, do not open another position;
6. restore management from the last confirmed exchange-side stop;
7. prevent duplicate reduce/exit actions;
8. eventually persist exchange-closed cycles.

## Secrets
Bybit API key/secret must exist only on the server secret/env/secret manager. Do not log them, persist plaintext in DB, or commit them. Withdraw permission is prohibited.

## Android after migration
Android becomes a thin client for:
- server status;
- balance;
- total income;
- metrics;
- open positions;
- last 3 completed cycles;
- START/STOP and OBSERVE/LIVE control;
- allowed settings and diagnostics.

Use HTTPS REST for commands/config and WebSocket/SSE for live dashboard state. Server is the only source of truth.