# Live Research — Codex Handoff

## Goal
Move the trading runtime from Android to a continuously running server **without changing the entry-analysis behavior in server-v1**.

Repository: `213JEKA/Keytrins`
Branch: `live-research-android-v01`
Baseline handoff commit: `65149f73a3b0ca11b871e0cbaddcdd39dbbd3a88`
Android package: `com.keytrins.liveresearch`
Current line: `v0.1.3.3`

Read first:
- `live-research-android/app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java`
- `.../strategy/StrategyEngine.java`
- `.../strategy/Indicators.java`
- `.../net/BybitClient.java`
- `.../net/BybitHistoryClient.java`
- `.../storage/Db.java`
- `.../SettingsStore.java`
- `.../model/*`

## Hard rule: PARITY FIRST
Before server-v1 is accepted, do not change:
- H1/M15 signal logic;
- universe filters;
- indicator parameters;
- stop construction;
- sizing;
- cost/R gate;
- LONG/SHORT confirmation rules.

The first server version must produce the same decisions on the same closed candles as the Android engine.

## Desired cutover
Server becomes the only Bybit trading runtime and stores Bybit secrets. Android becomes a thin control/dashboard client and must stop placing orders directly after cutover.

## Required deliverables
1. Server runtime with crash/restart recovery.
2. Canonical server DB for signals, orders, fills and trade cycles.
3. Idempotent execution/reconciliation.
4. Docker deployment.
5. REST + WebSocket/SSE API for Android.
6. Strategy parity tests and fault-injection tests.
7. Completed trade cycles that correctly aggregate partial exits.

See the other files in `codex-handoff/`.