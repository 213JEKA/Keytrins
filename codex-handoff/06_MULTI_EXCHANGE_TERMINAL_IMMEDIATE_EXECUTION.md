# ТЗ Codex — Multi-Exchange Inverse Terminal v1.0

## Режим выполнения

Это НЕ исследование и НЕ новая стратегия. Выполнять немедленно как production/LIVE-проект для отдельного реального Windows-сервера.

Запрещено тратить цикл разработки на backtest, paper/shadow, testnet/demo, A/B, Phase 3L, 10-cycle gate, подбор параметров или оптимизацию стратегии. Не менять торговую формулу под видом рефакторинга. Разрешены только build/compile checks, read-only API preflight и проверка запуска сервиса; никаких тестовых ордеров.

После сборки установить на реальный сервер как Windows Service с автозапуском и выдать Windows .exe клиент. Сервер продолжает торговать при закрытом GUI, выключенном ПК пользователя или отсутствии телефона.

## 1. Источник истины торговой логики

Входная логика должна быть прямым портом ПЕРВОЙ версии Android OKX Inverse, а не текущей Phase 3L и не поздней regime/MarketStructure версии.

Эталон:
- branch: `okx-inverse-android-v01`
- `live-research-android/app/src/main/java/com/keytrins/liveresearch/strategy/StrategyEngine.java`
- blob SHA: `67efa3610582267ed6e12fa46be376768f4c76fe`
- `SettingsStore.java` blob SHA: `0d6f1fa486f87473faae7cb8df1b1633f98a55b9`
- `LiveResearchEngine.java` blob SHA: `2a17dab3688fdb15e4da78a3f605d49e1d8d6af8`

НЕЛЬЗЯ переносить в StrategyCore:
- `MarketStructure`;
- regime classifier;
- range/retest/rejection logic;
- channel/location filters;
- `NO_SPACE_TO_LEVEL` / `MIN_ROOM_R`;
- любые Phase 3L admission/entry rules;
- новые фильтры, которых нет в первой OKX версии.

Один общий `StrategyCore` используется всеми биржами. Биржевые адаптеры не имеют права менять сигнал, пороги или добавлять собственные фильтры стратегии. Различаться могут только данные конкретной биржи, спецификация контракта, комиссии и техническое исполнение.

## 2. Биржи v1

Подключить все биржи, уже использовавшиеся в наших контурах:
1. Bybit
2. OKX
3. KuCoin Futures
4. Bitget
5. MEXC Futures
6. Gate Futures
7. BingX
8. CoinEx Futures

Торговый рынок: USDT linear perpetual/futures, где он поддерживается конкретной биржей.

Архитектура должна позволять добавить следующий `ExchangeAdapter` без изменения `StrategyCore`.

Если API-ключ конкретной биржи отсутствует, биржа показывает `НЕ НАСТРОЕНА`; это не должно останавливать другие биржи.

## 3. Точная входная стратегия первой OKX версии

### Universe
Для КАЖДОЙ биржи отдельно:
- исключить stablecoin bases: `USDT, USDC, USDE, FDUSD, TUSD, DAI, USDD, PYUSD, USD1, USDP`;
- минимальный 24h turnover: `5,000,000 USDT`;
- сортировка по 24h turnover descending;
- top N, default `30`;
- refresh примерно раз в час.

### Время решения
- сигнал рассчитывать по закрытым H1/M15 свечам;
- один scan после закрытия M15, примерно через 8 секунд;
- stale signal > 180 секунд не исполнять;
- одна позиция на symbol на одной бирже;
- повторный вход с тем же `signalTime` запрещён.

### H1 trend
Параметры:
- EMA fast = 50
- EMA slow = 200
- EMA50 slope = 3 H1 bars
- ADX(14) >= 22

Base LONG:
- EMA50 > EMA200;
- EMA50 now > EMA50 3 bars ago;
- ADX >= 22;
- H1 close > EMA50.

Base SHORT — точная инверсия условий.

### M15 pullback
- EMA20 / EMA50;
- lookback 4;
- хотя бы одна из предыдущих закрытых M15 свечей пересекает зону EMA20–EMA50.

### M15 confirmation
Base LONG, последняя закрытая M15:
- close > open;
- close > previous high;
- close > EMA20.

Base SHORT:
- close < open;
- close < previous low;
- close < EMA20.

### Strategy reference stop
ATR(14), multiplier 1.2, swing lookback 5.

Base LONG:
`min(entry - 1.2*ATR, min(low last 5 M15))`

Base SHORT:
`max(entry + 1.2*ATR, max(high last 5 M15))`

`riskDistance = abs(entryRef - stopRef)`.

### Inverse execution — ОБЯЗАТЕЛЬНО для всех 8 бирж
StrategyCore direction НЕ менять.

Только после окончательного base signal:
- Base LONG -> actual exchange SHORT
- Base SHORT -> actual exchange LONG

Фактический reference stop distance зеркалится вокруг фактического inverse entry.

Лог обязан хранить отдельно:
`baseSignalDirection`, `actualDirection`, `signalTime`, `entryReason`.

## 4. Sizing и admission

Сохранить параметры первой OKX версии:
- default `riskUsdt = 3.0`;
- leverage `5x`;
- Cross;
- One-Way / net mode / positionIdx=0 или точный эквивалент биржи;
- max notional = `1000 USDT`;
- cost/R gate <= `0.25`;
- market entry;
- учитывать qtyStep, minQty, maxQty, contract value, minNotional;
- estimated round-trip taker fees + current spread входят в cost/R;
- если биржа даёт фактический taker fee через API, использовать его; иначе биржевой configurable fallback.

Никаких дополнительных strategy/admission фильтров.

## 5. НОВОЕ обязательное управление риском — одинаковое на всех биржах

Это единственное намеренное изменение относительно первой Android OKX версии. ВХОД НЕ МЕНЯТЬ.

Все долларовые пороги считать по NET PnL позиции: PnL после оценки комиссии закрытия, spread и tick/slippage buffer. Состояния `peakNetProfitUsdt` и `protectedNetProfitUsdt` монотонные и сохраняются на диск.

### 5.1 Жёсткий максимальный минус

Цель: сделка не должна добровольно допускать убыток глубже `-0.50 USDT NET`.

После подтверждённого fill немедленно установить exchange-side reduce-only protective stop на цену, соответствующую примерно `-0.50 USDT NET`.

Strategy reference stop из раздела 3 сохраняется для исходной формулы и sizing, но ACTIVE loss stop должен быть более защитным из:
- mirrored strategy stop;
- hard NET -0.50 USDT stop.

На каждом обновлении позиции:
- если estimated NET <= `-0.50 USDT`, немедленно отправить reduce-only market close 100% remaining qty;
- не ждать M15;
- не делать partial reduction;
- не усреднять;
- не переворачиваться;
- не открывать hedge.

Из-за latency/slippage абсолютная гарантия точного realized -0.50 невозможна, поэтому trigger и exchange-side stop ставить максимально близко к этому NET уровню с биржевой корректной квантовкой.

### 5.2 Profit Lock

Нет фиксированного TP.

Триггер/защита:
- peak NET >= `+1.30 USDT` -> защитить минимум `+1.00 USDT NET`;
- peak NET >= `+2.00 USDT` -> защитить минимум `+1.50 USDT NET`;
- peak NET >= `+2.50 USDT` -> защитить минимум `+2.00 USDT NET`;
- peak NET >= `+3.00 USDT` -> защитить минимум `+2.50 USDT NET`;
- далее каждые `+0.50 USDT` peak -> поднять protected ещё на `+0.50 USDT` без верхнего лимита.

Если peak перескочил несколько уровней за один update, сразу перейти на максимально достигнутый protected level.

Exchange-side stop:
- переводить цену protected NET level с учётом entry/exit taker fees, spread, tick-size и небольшого configurable execution buffer;
- stop только усиливается, НИКОГДА не ослабляется;
- LONG stop только вверх, SHORT stop только вниз;
- stop quantized по tick size;
- изменение стопа должно быть reduce-only/close-only эквивалентом биржи.

Catch-up:
- если amend stop не подтверждён, API временно недоступен или рынок уже пересёк новый protected level, software controller немедленно закрывает remaining qty reduce-only market;
- после восстановления связи exchange truth сверяется с локальным state.

При restart сервера восстановить peak/protected и продолжить с наиболее защитного известного уровня; никогда не забывать достигнутую прибыль.

## 6. Рыночный/позиционный runtime

Для риска -0.50 и profit lock нельзя полагаться только на 5-секундный Android polling.

Для каждой биржи:
- использовать private/public WebSocket, если доступен;
- mark/last/position/fill события обрабатывать непрерывно;
- REST polling оставить как reconciliation fallback;
- REST reconcile positions/orders/stops минимум раз в 2–5 секунд;
- разрыв WS не должен снимать exchange-side stop;
- worker одной биржи не может остановить worker другой биржи.

Supervisor:
- отдельный worker на exchange;
- автоматический restart crashed worker;
- fail-closed для НОВЫХ entries при stale market data, auth error, account-mode mismatch или неизвестном exchange state;
- уже открытые позиции продолжают management/reconciliation;
- ошибки одной биржи не блокируют остальные.

## 7. Состояние позиции и восстановление

Хранить минимум:
- exchange
- tradeId/clientOrderId
- symbol
- baseSignalDirection
- actualDirection
- signalTime
- entryReason
- entryPrice
- qty / remainingQty
- reference strategy stop
- current exchange stop
- hardLossStop
- riskDistance
- taker fee
- spread at entry
- peakNetProfitUsdt
- protectedNetProfitUsdt
- openedAt/closedAt
- realized gross/fees/funding/net
- exitReason
- last exchange order/stop ids

Persistence: локальная SQLite DB в WAL mode на сервере, migrations, fsync/transactional mutations. Secrets НЕ хранить в этой БД plaintext.

При startup:
1. загрузить DB;
2. запросить exchange truth по всем настроенным биржам;
3. сопоставить свои позиции по exchange+symbol+clientOrderId/state;
4. восстановить управление;
5. если DB говорит OPEN, а exchange flat — финализировать цикл;
6. если exchange position существует, но не принадлежит терминалу — показать как `EXTERNAL`, не включать её автоматически в стратегическое сопровождение; ручное закрытие из UI разрешено.

## 8. Windows server architecture

Production stack: .NET 8 x64 self-contained.

Разделить:
- `KeytrinsMultiExchange.Service.exe` — Windows Service, единственный владелец API keys и trading state;
- `KeytrinsMultiExchange.Terminal.exe` — desktop monitor/control UI;
- встроенный HTTPS control API + responsive Web/PWA monitor для телефона.

GUI никогда не является условием торговли. Закрытие Terminal.exe не останавливает Service.exe.

Windows Service:
- Automatic start;
- restart on failure;
- working directory: `C:\ProgramData\Keytrins\MultiExchangeTerminal`;
- DB: `data\terminal.db`;
- logs: `logs\`;
- encrypted secrets: `secrets\`;
- releases: `C:\Program Files\Keytrins\MultiExchangeTerminal`.

Создать installer/setup script, который:
- устанавливает self-contained binaries;
- регистрирует Windows Service;
- создаёт необходимые каталоги/ACL;
- создаёт desktop shortcut terminal client;
- запускает service;
- включает автозапуск;
- не требует Visual Studio/.NET SDK на production server.

## 9. Главный экран — ТОЛЬКО блоки бирж

Главная вкладка не должна быть захламлена настройками, логами, API ключами и техническими параметрами.

Показать grid карточек 8 бирж. В каждой карточке:
- название биржи;
- connection health indicator;
- trading state: ACTIVE / PAUSED / OFF / ERROR;
- balance USDT;
- equity при наличии;
- realized PnL;
- unrealized PnL;
- open positions count;
- последняя активность/сигнал;
- список открытых позиций внутри карточки: symbol, LONG/SHORT, entry, mark, qty, NET PnL, peak, protected, current stop;
- кнопка `ПАУЗА ВХОДОВ` / `ВКЛЮЧИТЬ`;
- кнопка закрытия конкретной позиции;
- кнопка `ЗАКРЫТЬ ВСЕ И ОТКЛЮЧИТЬ` для этой биржи.

Семантика отключения:
- `PAUSED`: НОВЫЕ entries запрещены, существующие позиции продолжают safety management;
- `OFF`: биржа flat и admission disabled;
- обычная кнопка OFF не должна бросать открытую позицию без management;
- `ЗАКРЫТЬ ВСЕ И ОТКЛЮЧИТЬ`: 100% reduce-only close всех позиций терминала на этой бирже, дождаться flat, затем OFF.

Ручное закрытие внешней/manual позиции из терминала разрешено, но должно быть явно помечено `EXTERNAL`.

## 10. Остальные вкладки

Всё лишнее с Main перенести отдельно:

### Настройки
- API credentials per exchange;
- enable/disable exchange;
- riskUsdt (default 3);
- Universe N (default 30);
- leverage (default 5x);
- fee fallbacks;
- execution buffer;
- remote-control settings;
- auto-start.

### История
- все сделки;
- gross, fee, funding, NET;
- entry/exit reason;
- peak/protected;
- filters по exchange/symbol/date.

### Журнал
- strategy decisions;
- API errors;
- order mutations;
- stop changes;
- reconnect/recovery;
- manual control audit.

### Сервер
- uptime;
- service status;
- workers 8/8;
- WS/REST health и latency;
- last market event;
- DB state;
- time skew;
- version/build.

### Доступ / безопасность
- paired desktop/phone sessions;
- revoke token;
- TLS/VPN endpoint;
- audit.

## 11. ПК и телефон как мониторы/контроллеры

Desktop `.exe` и телефон НЕ торгуют напрямую с биржами. Они работают только через серверный control API.

Телефон: responsive HTTPS PWA/web UI с теми же биржевыми карточками и действиями:
- посмотреть сделки;
- pause/resume конкретную биржу;
- close одну позицию;
- close all + disable конкретную биржу.

API keys никогда не отправлять клиентам после сохранения на сервере.

Remote access:
- TLS обязателен;
- по умолчанию bind control API только на private/VPN interface;
- bearer/session token с rotation;
- write actions подписывать authenticated session + anti-replay nonce;
- журналировать кто/откуда сделал manual close/disable.

## 12. Secrets/security

- API keys/secret/passphrase хранить только server-side;
- Windows DPAPI LocalMachine + ограниченные ACL service account;
- никогда не логировать secret/passphrase/signature;
- Withdraw permission НЕ нужна и должна считаться ошибочной конфигурацией, если биржа позволяет её определить;
- trading key должен иметь только Read + Trade;
- синхронизация времени/clock skew проверяется;
- все mutations имеют idempotency key/clientOrderId;
- unknown result после timeout сначала reconcile, потом решать — не делать blind retry.

## 13. ExchangeAdapter contract

Сделать общий интерфейс примерно:
- `GetServerTime()`
- `GetInstruments()`
- `GetTickers()`
- `GetClosedCandles(symbol, H1/M15)`
- `GetFeeRate(symbol)`
- `GetBalance()`
- `GetPositions()`
- `EnsureOneWayCrossLeverage(symbol, 5)`
- `PlaceInverseMarketEntry(...)`
- `PlaceOrReplaceProtectiveStop(...)`
- `ReduceOnlyClose(...)`
- `GetOpenOrders/Stops()`
- `GetFills/Transactions()`
- `SubscribeMarket/Positions/Orders()`
- `Reconcile()`

Каждый adapter нормализует symbol/contract/qty/tick/fees, но StrategyCore не знает название биржи и не имеет exchange-specific условий.

## 14. Exit reasons

Минимум:
- `HARD_MINUS_050`
- `PROFIT_LOCK`
- `PROFIT_LOCK_CATCHUP`
- `MANUAL_CLOSE`
- `MANUAL_CLOSE_ALL_DISABLE`
- `EXCHANGE_PROTECTIVE_STOP`
- `EXTERNAL_CLOSE`

Никаких Phase 3L `EXIT_MINUS_035`, `REDUCE_85`, defensive hedge и т.п.

## 15. Отчётность

На закрытии каждой позиции вычислить фактический:
- gross price PnL;
- open/close fees;
- funding;
- NET PnL;
- peak NET;
- protected NET;
- reason.

Balance/total income на карточке брать из exchange truth, а не из локальной оценки.

## 16. Немедленная production installation — без strategy tests

Codex должен в этой задаче завершить всё до состояния установленного production сервиса.

Последовательность:
1. создать отдельный project directory/solution для Multi-Exchange Terminal;
2. портировать exact first-OKX StrategyCore по эталонным SHA выше;
3. реализовать 8 adapters;
4. реализовать risk/profit controller раздела 5;
5. реализовать service + desktop .exe + phone PWA;
6. реализовать encrypted secrets/persistence/recovery;
7. собрать self-contained Windows x64 release;
8. установить release на реальный отдельный Windows server;
9. зарегистрировать/запустить Windows Service;
10. провести только read-only API preflight для имеющихся keys: auth, balance, account mode, positions, instruments, server time;
11. НЕ отправлять synthetic/test trades;
12. импортировать имеющиеся реальные credentials безопасно из текущей server environment, если они уже там есть; отсутствующие биржи оставить `НЕ НАСТРОЕНА`;
13. оставить каждую биржу в том LIVE/PAUSED состоянии, которое явно задано в server config; не выдумывать credentials;
14. выдать пути/URL к desktop terminal и phone monitor;
15. выдать финальный production report с version, service status, per-exchange connection status, DB path, remote URL и hash бинарников.

## 17. Критические запреты

- НЕ запускать Phase 3L-2.
- НЕ переносить Phase 3L trading mechanics.
- НЕ включать `MarketStructure`/regime/location filters.
- НЕ менять EMA/ADX/M15 формулу.
- НЕ менять inverse mapping.
- НЕ добавлять fixed TP.
- НЕ делать martingale/grid/averaging/hedge.
- НЕ делать partial loss reduction.
- НЕ допускать blind mutation retry.
- НЕ хранить API secrets в source/repo/logs.
- НЕ зависеть от desktop/phone для управления уже открытой позицией.

## 18. Definition of Done

Задача считается выполненной только когда:
- Windows Service установлен и RUNNING на production server;
- desktop `.exe` собран и открывает 8 exchange cards;
- phone PWA доступен по защищённому endpoint;
- StrategyCore соответствует первой OKX версии;
- все 8 adapters присутствуют;
- hard NET loss controller -0.50 реализован на всех adapters;
- profit lock 1.30->1.00, 2.00->1.50, далее +0.50/+0.50 unlimited реализован на всех adapters;
- exchange-side stops + software catch-up работают в общем controller contract;
- per-exchange pause / close one / close all+disable доступны с ПК и телефона;
- state/peak/protected переживают restart;
- API keys зашифрованы server-side;
- build hashes и production service status зафиксированы в финальном отчёте.
