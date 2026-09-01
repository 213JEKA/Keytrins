# Keytrins Multi-Exchange v1.1

The immutable signal path is:

`OKX public closed candles -> OkxStrategyCore -> CanonicalSignal -> OKX protected entry -> follower fan-out -> inverse direction`

`OkxStrategyCore` is the only strategy implementation. Its formulas are a direct C# port of
`StrategyEngine.java` blob `67efa3610582267ed6e12fa46be376768f4c76fe` from branch
`okx-inverse-android-v01`. Target exchange adapters contain no candle or indicator inputs.

OKX is the execution leader as well as the signal source. Bybit and KuCoin Futures are allowed to receive the
canonical signal only after OKX exchange truth confirms both the position and its attached initial stop. A follower
rejection is isolated to that follower and never closes or invalidates an already protected OKX position.

The server owns state and control. Windows Terminal and the PWA call the server API and never store exchange
credentials or communicate with exchanges. SQLite uses WAL and persists signals, one-per-exchange route attempts,
managed positions, monotonic peak/protected values and the audit log.

The deployment default is fail-closed: `Runtime:TradingEnabled=false`. Public and authenticated read-only preflight
remain available. A configured credential is rejected for execution if it lacks trade permission or includes Withdraw.
Mutation requests are not reachable in the current read-only installation gate.

The selected server `37.252.21.226` runs Ubuntu 24.04, so its 24/7 runtime is registered as the native `systemd`
equivalent of the requested Windows Service. Windows self-contained Service and Terminal binaries are also produced;
the desktop installation uses only the Terminal. This platform adaptation does not alter strategy or routing semantics.

The IP-only HTTPS endpoint uses a dedicated Caddy internal CA. Its root is installed only in the Windows current-user
trust store; the same root will be installed on the user's phone during PWA setup. Caddy runs with host networking so
the application remains bound exclusively to `127.0.0.1:8080` and is reachable externally only through HTTPS.
