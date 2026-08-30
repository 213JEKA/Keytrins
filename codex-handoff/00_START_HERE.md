# Live Research — Codex Handoff

## Goal
Move the trading runtime from Android to a continuously running server **without changing the entry-analysis behavior in server-v1**.

Repository: `213JEKA/Keytrins`
Branch: `live-research-android-v01`
Handoff head commit: `5b51dcc9520be7d809bb14d68284eb20ef65879b`
Android package: `com.keytrins.liveresearch`
Current Android line: `v0.1.3.3`

## Read in this order
1. `codex-handoff/00_START_HERE.md`
2. `codex-handoff/01_TRADING_RULES_LOCK.md`
3. `codex-handoff/02_SERVER_ARCHITECTURE_TARGET.md`
4. `codex-handoff/03_PARITY_AND_ACCEPTANCE_TESTS.md`
5. `codex-handoff/04_CODEX_FIRST_TASK.md`
6. `live-research-android/app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java`
7. `live-research-android/app/src/main/java/com/keytrins/liveresearch/strategy/StrategyEngine.java`
8. `live-research-android/app/src/main/java/com/keytrins/liveresearch/strategy/Indicators.java`
9. `live-research-android/app/src/main/java/com/keytrins/liveresearch/net/BybitClient.java`
10. `live-research-android/app/src/main/java/com/keytrins/liveresearch/net/BybitHistoryClient.java`
11. `live-research-android/app/src/main/java/com/keytrins/liveresearch/storage/Db.java`
12. `live-research-android/app/src/main/java/com/keytrins/liveresearch/SettingsStore.java`
13. `live-research-android/app/src/main/java/com/keytrins/liveresearch/model/*`

## Hard rule: PARITY FIRST
Before server-v1 is accepted, do not change:
- H1/M15 signal logic;
- universe filters;
- indicator parameters;
- stop construction;
- position sizing;
- cost/R gate;
- LONG/SHORT confirmation rules;
- loss-control thresholds currently locked in `01_TRADING_RULES_LOCK.md`;
- positive-side BE/profit-lock/trailing behavior.

The first server version must produce the same decisions on the same closed candles as the Android engine.

## Desired cutover
Server becomes the only Bybit trading runtime and stores Bybit secrets. Android becomes a thin control/dashboard client and must stop placing orders directly after cutover.

## Current Android runtime limitations to remove on server
- foreground Android service is `START_NOT_STICKY`;
- phone/process loss interrupts active management;
- REST polling is tied to a mobile process;
- SQLite is local and not a durable multi-process source of truth;
- partial-exit Closed PnL rows can be confused with complete trade cycles;
- local/exchange state needs stronger idempotent reconciliation.

## Required deliverables
1. Server runtime with crash/restart recovery.
2. Canonical server DB for signals, orders, fills and trade cycles.
3. Idempotent execution/reconciliation.
4. Docker deployment.
5. REST + WebSocket/SSE API for Android.
6. Strategy parity tests and fault-injection tests.
7. Completed trade cycles that correctly aggregate partial exits.
8. Android thin-client cutover only after server parity is proven.

## Security constraints
- Never commit Bybit API key/secret.
- Server key permissions: Read + derivatives trading only; Withdraw must be disabled.
- Do not copy Android Keystore secrets into source control.
- Use environment/secret manager on the server.

See the remaining files in `codex-handoff/`.