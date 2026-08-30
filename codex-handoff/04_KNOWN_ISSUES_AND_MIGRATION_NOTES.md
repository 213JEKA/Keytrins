# Known Issues / Migration Notes

## Android lifecycle
Current foreground service is `START_NOT_STICKY`; process death/reboot can stop management. Server migration must eliminate phone-runtime dependency.

## Exchange-side stops
Preserve the current good property: initial SL and moved stops live on Bybit. Do not replace them with memory-only server stops.

## Closed PnL vs completed trade
Bybit `/v5/position/closed-pnl` can emit separate records for partial closes. Android UI had to group them heuristically. Server accounting must not use that heuristic as canonical state. Own `trade_cycle` + `trade_leg` state from entry until final flat.

## Manual trading collisions
Transaction-based symbol aggregation can mix bot and manual activity. Use unique client/order IDs and reconcile by identifiers wherever possible.

## Reduce-only race
Manual closing while the bot manages the same symbol previously caused reduce-only conflicts. Serialize per-symbol execution and re-read current position size immediately before every reduce-only action.

## UI is not trading core
Do not port Android display workarounds into the server engine.

## Strategy experiment discipline
Entry-analysis rules are intentionally locked for parity. Recent live changes were focused on cutting losses harder, so do not silently alter analysis/opening behavior while evaluating that change.

## Current live observation
Old 75% reduction could leave a tail that produced much larger full-cycle losses. Current agreed rule is 85% reduction at -0.20R with fresh structural break and full exit at -0.35R. This is an observation-driven risk change, not proof of profitability.