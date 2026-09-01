# KuCoin Adaptive Grid 0.1.0

Native Android prototype for KuCoin Futures (Classic API).

## Trading mechanics

- Default mode: TEST (`POST /api/v1/orders/test`), so generated orders do not enter the matching engine.
- LIVE must be explicitly enabled in the app.
- Isolated margin, default leverage 2x.
- v0.1 requires KuCoin One-Way Position Mode (`positionMode=0`). Hedge mode is intentionally rejected.
- Market regime is recalculated from 1-minute candles:
  - SHOCK: current true range > 2.2 × ATR(14), or close-to-close move > 1.6 × ATR(14).
  - TREND_UP: ADX approximation >= 23, EMA20 above EMA50, EMA separation >= 0.35 ATR and positive EMA20 slope.
  - TREND_DOWN: mirrored conditions.
  - RANGE: everything else.
- Grid behavior:
  - RANGE: buy levels below and sell levels above the anchor.
  - TREND_UP: buy grid only; no new counter-trend shorts.
  - TREND_DOWN: sell grid only; no new counter-trend longs.
  - SHOCK: no new grid; in LIVE existing open grid orders are cancelled.
- Grid step = max(fee floor + 0.08%, 0.55 × ATR%), clamped to 0.30%..1.50%.
- Rebuild when regime changes, price moves >= 0.75 grid step from anchor, or 3 minutes pass.
- Contract metadata (`tickSize`, `lotSize`, `multiplier`, maker/taker fees) is fetched from KuCoin and used for price rounding and sizing.
- Capital allocation: 80% of configured capital, with maximum grid depth automatically reduced if minimum contract size is too large.
- Default symbol is DOGEUSDTM because small accounts may not be able to create a practical multi-level grid on XBTUSDTM due to the minimum contract notional.

## Security

- API key/secret/passphrase are not persisted by this prototype; they are passed to the foreground service in memory.
- Create a KuCoin API key with Futures trading permission only and WITHOUT withdrawal permission.
- TEST mode never calls the live cancel-all endpoint, so testing cannot cancel unrelated live orders.
- Android release signing key is intentionally NOT stored in GitHub. CI builds an unsigned release APK; final signing is performed offline with the permanent project keystore.

## Known v0.1 limitation

SHOCK currently cancels unfilled grid orders but does not force-close an already accumulated net futures position. This is intentional for the first test build; automatic position liquidation/hedge and session PnL trailing lock should be added only after validating account position mode and order behavior on the target KuCoin account.
