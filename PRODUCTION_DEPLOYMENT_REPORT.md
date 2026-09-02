# Multi-Exchange v1.1 deployment report

Date: 2026-09-01
Source branch: `okx-inverse-android-v01`
Source HEAD: `2dd1a89d7d74d482232c489e3e70c6a416bdcf9e`
Strategy reference blob: `67efa3610582267ed6e12fa46be376768f4c76fe`

## Cleanup

- Removed the previous Live Research repository contents, chat-created reports/runtime logs and the exact local Docker
  Compose project `server` (four containers, its network and its two PostgreSQL volumes).
- Preserved `C:\Users\x213x\.keytrins\phase2c.env` and `phase3l0.env` unchanged.
- Did not touch the other local KeyTRINS projects, `keytrins-bitgo-express`, `rpg_postgres` or the running
  Inefficiency Trader processes.
- On `37.252.21.226`, removed the old KeyTRINS arbitrage/P2P deployments, their containers, volumes, images and backup
  timers after preserving credential files at `/root/keytrins-preserved-secrets-20260901` with mode `700/600`.
- Preserved Ubuntu, SSH access, Docker, Zabbix and system infrastructure.

## Implemented and installed

- One `OkxStrategyCore`, a direct formula port of the reference Java StrategyEngine and Indicators.
- OKX-only top-30 master universe, stable-base exclusions, 5,000,000 USDT turnover floor and deterministic turnover
  ordering.
- Closed H1/M15 scan at the M15 boundary plus eight seconds, immutable `CanonicalSignal`, inverse direction and
  parallel one-signal/eight-adapter routing records.
- Eight isolated adapter types: OKX, Bybit, KuCoin Futures, Bitget, MEXC Futures, Gate Futures, BingX and CoinEx
  Futures. Target adapters have no candles, indicators or StrategyCore.
- SQLite WAL schema for canonical signals, per-exchange route attempts, managed positions with peak/protected state,
  and an audit log.
- NET hard-loss/profit-lock price math, tick rounding and monotonic stop helpers in Core.
- Server-owned API and PWA; clients do not receive exchange credentials.
- A dedicated `Настройки` view in both the Windows Terminal and PWA for `riskUsdt`, OKX universe size,
  leverage, maximum notional, `maxCostR`, and per-exchange API key/secret/passphrase entry.
- The Windows dark theme explicitly styles all field labels, tabs and list items with light foreground colors. The
  system-white exchange ComboBox was replaced by a dark, high-contrast exchange selector.
- JSON table rows are projected through a `DataView`, so the main exchange grid, positions, history and logs expose
  their actual columns instead of .NET Dictionary internals (`Comparer`, `Keys`, `Values`). The main grid includes an
  explicit single-row selection hint for per-exchange commands.
- Per-exchange row commands were replaced by checkboxes and one batch action. Both desktop and PWA now let the user
  select all required exchanges and press `ВКЛЮЧИТЬ ВЫБРАННЫЕ` once. Settings expose only risk and universe size;
  leverage, max notional and max Cost/R remain fixed server-side safety values.
- Credential save now triggers immediate read-only preflight and distinguishes `stored` from `verified`. Batch enable
  is all-or-nothing and refuses missing/unverified private authentication, absent trading permission, Withdraw
  permission, or a disabled global mutation gate. A manual per-exchange enable is subject to the same gates.
- Runtime settings are validated, persisted atomically on the server, and applied to later scans/signals. The settings
  API cannot enable the global LIVE gate, and persisted client settings cannot restore `TradingEnabled=true`.
- Per-exchange credentials are write-only from clients. The Linux vault uses AES-256-GCM with a separate root-owned
  32-byte key; Windows service builds use machine-scoped DPAPI. API responses expose only configured/not-configured.
- Windows x64 self-contained Service and Terminal publishes plus Linux x64 self-contained Service publish.

The selected server is Ubuntu 24.04 rather than Windows. Its runtime is therefore installed as the native equivalent:

- unit: `keytrins-multi-exchange.service`;
- state: enabled and active;
- user/group: `keytrins:keytrins`;
- executable: `/opt/keytrins-multi-exchange/KeytrinsMultiExchange.Service`;
- data: `/var/lib/keytrins-multi-exchange`;
- secrets: `/etc/keytrins-multi-exchange/runtime.env` (`640 root:keytrins`);
- HTTPS/PWA: `https://37.252.21.226` through Caddy internal PKI;
- Caddy: restart policy `unless-stopped`, host network, application still bound only to `127.0.0.1:8080`.

The desktop Terminal is installed at
`C:\Users\x213x\AppData\Local\Programs\Keytrins\MultiExchangeTerminal` and a desktop shortcut was created. The
requested `Program Files` path was not writable because the Codex host process is not elevated; the installed client is
functionally identical and runs as the current user.

## Verification

- Release solution build: `0 warnings / 0 errors`.
- Local read-only runtime: service start, SQLite WAL and complete real OKX top-30 scan passed.
- The first unthrottled scan exposed OKX HTTP 429; the final implementation uses an explicit 150 ms public-data
  limiter plus bounded retries only for idempotent public reads. Final top-30 scan completed without errors.
- Server restart/recovery smoke passed from confirmed flat state.
- `/api/health`: `status=ready`, `masterSignalSource=OKX`, `masterHealth=ONLINE`,
  `tradingEnabled=false`, `mutationGate=DISARMED`.
- Universe: `30`.
- Workers/public preflight: `8/8 ONLINE`.
- Bybit private read-only credential audit: authenticated, `readOnly=0`, trading permission present,
  `Withdraw=false`.
- Positions before and after restart: empty.
- SQLite: `journal_mode=wal`, `unknown_routes=0`, `managed_positions=0`.
- systemd recent priority-error log: empty.
- HTTPS validates from Windows with the dedicated CA; HTTP redirects to HTTPS.
- No testnet or real order was submitted.
- Settings API smoke: authorized read/write passed, unauthorized read returned `401`, invalid universe returned `400`
  without changing the saved value, and settings responses contained neither API keys nor API secrets.
- Settings persistence passed across a systemd restart: risk `3 USDT`, universe `30`, leverage `5`, max notional
  `1000 USDT`, `maxCostR=0.25`.
- Three credential sets are currently stored: OKX, Bybit and KuCoin Futures. Bybit private preflight is verified;
  OKX and KuCoin currently report `PRIVATE_PREFLIGHT_NOT_AVAILABLE`, so the batch action correctly returns `409
  PRIVATE_PREFLIGHT_FAILED` and does not activate any exchange.
- The preserved Bybit credential was moved into `/var/lib/keytrins-multi-exchange/credentials.vault`
  (`600 keytrins:keytrins`, encrypted format v2). Its two plaintext environment entries were removed. After restart,
  read-only private preflight still reported authenticated, trading permission present, `Withdraw=false`, and mode
  `Paused`.
- One rollout restart initially failed closed because a missing newline joined the new vault-key setting to the final
  environment line. The line was repaired without exposing or changing the credential; the current service is active
  and its post-restart error journal is empty.

Hashes:

- Linux service executable: `3f34b013c212ee2c9974fd254cc51aaae632433186cdfcc841208caab4171dd4`.
- Linux service DLL: `acc888c1d3f127860888d3afef740fd0289007512f2653c0e0c5c9373302e1c3`.
- Linux Core DLL: `9f32baf54e1dee14e4d09378b26d6832a1ff8386998fe348c668783e4fc4726c`.
- Linux deployment archive: `2a02256d5eba3711e631a996a18ad088cd5f303fdcb93e1d2ef5c9f2825c3254`.
- Installed Windows Terminal launcher: `1a2758ecd2b33ed2b9de617412cacb203ebb4161cd3ab4ffb87d2145be6cc661`.
- Installed Windows Terminal application DLL: `dfd532f535e940d24bc1e11c9be6f869bfaa38b2e8144524dd0951fd9785b9e1`.
- Caddy client CA: `a26ba2203a1f324fb0e47338769f3a3db019bc18c49339b8b7df13f3f5bc61c4`.

## Safety boundary and remaining Definition-of-Done gaps

This installation is operational for master-data scanning, persistence, monitoring and read-only preflight, but it is
not represented as a completed LIVE trading runtime:

- `Runtime:TradingEnabled=false` and every mutation path is fail-closed.
- Bybit is `Paused`; the other seven exchanges are `NotConfigured` because their credentials are absent.
- The current adapter deployment performs public/read-only credential preflight and technical skip recording. Private
  order/stop/reconciliation implementations for all eight exchanges have not been production-validated or armed.
- The selected Linux host cannot satisfy the literal `Windows Service`/DPAPI requirement. It uses systemd and a
  root-owned server secret file. Windows self-contained service bits are built for a future Windows host.
- Phone CA trust and PWA installation remain pending until the phone is connected.

Accordingly, no LIVE enablement is permitted from this report. Completing the remaining adapter mutation/recovery work,
providing the seven absent credential sets, running read-only permission/account-mode preflight, and a separate explicit
LIVE admission decision are still required before real orders.

## 2026-09-01 production-readiness update

The implementation subsequently narrowed the execution fan-out to the three requested exchanges only: OKX as the sole
`CanonicalSignal` source, with OKX, Bybit and KuCoin Futures as parallel execution targets. All three private preflights
now pass trading-permission, no-Withdraw and One-Way checks. Persistent execution commands/transitions, risk actions,
position reconciliation/recovery, mandatory exchange-side initial stops, monotonic profit protection and the configured
maximum NET-loss close were implemented without changing `OkxStrategyCore`.

Latest local verification is `33 PASS / 0 FAIL`. The installed desktop executable matches the latest build at SHA-256
`EB700C91C0278DD94DCDDFE7B10A35E957024B2FEE8B9982E28E0C44C37FC1EB`.

LIVE remains deliberately blocked because the same OKX credential is still being used by a separate legacy writer.
While the new server had `TradingEnabled=false`, `mutationGate=DISARMED`, zero managed positions and zero unresolved
commands, OKX reported a new filled non-reduce-only entry:

- instrument: `MU-USDT-SWAP`;
- direction: SHORT;
- quantity: `0.16`;
- fill price: `966.8`;
- exchange order ID: `3884768552891551744`;
- client order ID: `OXMU1788277523` (the new runtime reserves the `KX` prefix);
- exchange creation time: `2026-09-01T15:45:24.814Z`;
- initial exchange stop observed by the new runtime: absent.

The new runtime did not create, adopt, modify or close this position. Admission returned `409 EXCHANGE_NOT_FLAT`, all
three exchanges remained `Paused`, and health correctly reported `FOREIGN_WRITER_ACTIVE`, one unmanaged exchange
position and `status=degraded`.

Writer admission is now fail-closed in two distinct ways. A recent foreign OKX entry produces
`FOREIGN_WRITER_ACTIVE`; a quiet 20-minute order-history window without independent operator confirmation produces
`QUIET_WINDOW_NOT_EXCLUSIVE`, not the former overclaim of `EXCLUSIVE`. Environment-only
`Runtime:OkxExclusiveWriterConfirmed` and `Runtime:TradingEnabled` both default to false and cannot be restored from
client-editable settings. The batch-enable UI now gives separate Russian explanations for an existing unmanaged
exchange position, an active foreign writer, and a quiet-but-unconfirmed writer state.

The remaining blocker is external to this deployment: stop the legacy OKX trading client (very likely the previous
Android/Live Research runtime) or replace/restrict the OKX API key so only `37.252.21.226` can use it, then bring OKX
flat. Until that is done, the three-exchange LIVE gate must remain disarmed.

## 2026-09-02 v1.2.0 independent exchange execution update

The runtime now converts each accepted OKX strategy signal into one immutable canonical intent and three explicit,
independently executed exchange tasks.  OKX remains the only signal source; Bybit and KuCoin do not generate signals.
Each exchange task obtains its own current quote, instrument rules, account fee and available margin before submitting
the same asset and direction.  Symbol conversion is deterministic, including `BTC-USDT-SWAP` to `BTCUSDT` on Bybit
and `XBTUSDTM` on KuCoin Futures.  A rejection or technical precheck failure on one exchange does not suppress the
other two tasks.

Position sizing is now a user-visible fixed notional per exchange (`PositionNotionalUsdt`, initially `100 USDT`) and
is independent of OKX's structural stop distance.  The initial exchange stop and subsequent per-exchange position
management retain the existing fee-aware NET math, current remaining quantity, monotonic Dollar Lock and reduce-only
exit behavior.  The configured maximum expected NET loss is `1.50 USDT` per position.  If tick size, fees, local price,
instrument limits or available margin cannot support that protection, only that exchange's entry is rejected before
mutation.  `OkxStrategyCore`, signal generation and the Dollar Lock staircase were not changed.

Verification and rollout:

- Release build: zero warnings and zero errors.
- Automated tests: `86 PASS / 0 FAIL`.
- JavaScript syntax check and `git diff --check`: pass.
- Server installed as `/opt/keytrins-multi-exchange-release25`; runtime version `1.2.0`.
- Installed Core DLL SHA-256: `47585a102082a628133e179543d0ab2622f1120c6c48be800da53493d4ec898c`.
- Installed Service DLL SHA-256: `cfb580f96bdb813e96b07bf73830ebf53b140b2e49a9a742ef430e3fa316b090`.
- Windows Terminal release 25 was installed and its launcher hash matched the published artifact.
- The first service start attempt failed with systemd `203/EXEC` because the Windows-created archive did not retain
  the Linux executable bit.  Setting the intended service file to mode `750` resolved it; application code had not
  run during those failed attempts.
- Post-restart health: `status=ready`, `executionRecovery=READY`, unresolved execution/risk actions `0/0`.
- Post-restart exchange truth: OKX, Bybit and KuCoin Futures all flat.
- All three exchange modes remained `Off`; the deployment did not enable entries or submit an order.

## 2026-09-02 v1.2.1 fee-aware break-even staircase

The per-exchange profit protection staircase was changed at the operator's request.  It is based on each position's
peak estimated NET profit, not the asset price:

- below `+1.00 USDT`: no Dollar Lock step;
- at `+1.00 USDT`: true fee-aware break-even, protected NET `0.00 USDT`;
- at `+1.50 USDT`: protected NET `+1.00 USDT`;
- at `+2.00 USDT`: protected NET `+1.50 USDT`;
- every subsequent `+0.50 USDT` of peak advances protected NET by `+0.50 USDT`.

The break-even stop is calculated independently from each exchange's persisted entry fee and current taker fee,
including spread, configured slippage allowance and exchange tick rounding.  Remaining quantity is still used after a
partial reduction, and an existing exchange stop is never loosened.  Signal generation, entry selection, fixed
position notional, the `1.50 USDT` maximum NET-loss limit and reduce-only close behavior were not changed.

New entries were paused while the existing three-exchange FIL cycle continued under v1.2.0.  The operator then tested
the batch close-and-disable command; all three positions became exchange-flat and all modes became `Off`, with zero
unresolved execution and risk actions.  Version 1.2.1 was deployed only after that flat boundary.

Verification:

- automated tests: `100 PASS / 0 FAIL`;
- runtime: `status=ready`, version `1.2.1`, recovery `READY`;
- PostgreSQL/runtime projection and exchange truth: zero positions on all three exchanges;
- unresolved execution/risk actions: `0/0`;
- exchange modes after deployment: OKX, Bybit and KuCoin Futures all `Off`;
- server API exposes the new `1.00→0.00`, `1.50→1.00`, `2.00→1.50`, `2.50→2.00` ladder;
- installed Core DLL SHA-256: `f01dc14f15d9c76b9a9da7f6c0899bf4def3adeae9a99f42d7510d790c0d8220`;
- installed Service DLL SHA-256: `b5d0a5f9ea4982d856d67a134c2ab1a79965271495d83437ef9d7a299f60ff6f`;
- post-deployment systemd warning journal: empty.
