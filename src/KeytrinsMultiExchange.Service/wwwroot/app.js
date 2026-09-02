const $ = selector => document.querySelector(selector);
const esc = value => String(value ?? '—').replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
})[character]);
let token = localStorage.getItem('keytrins-token') || '';
let settingsLoaded = false;
let strategyPayload = null;
let runtimeSummaryLoaded = false;
let strategyRefreshInFlight = false;
let latestManagedPositions = [];
const selectedExchanges = new Set();
const exchanges = ['Okx', 'Bybit', 'KuCoinFutures'];
$('#token').value = token;

const headers = () => token ? { Authorization: `Bearer ${token}` } : {};
const api = async (path, init = {}) => {
  const response = await fetch(path, { ...init, headers: { ...headers(), ...(init.headers || {}) } });
  if (!response.ok) {
    let message = `HTTP ${response.status}`;
    try {
      const body = await response.json();
      const messages = {
        GLOBAL_TRADING_DISABLED: 'LIVE-исполнение ещё не запущено на сервере. Ключи проверены, но реальные заявки пока заблокированы.',
        EXECUTION_RECOVERY_NOT_READY: 'Сервер ещё не завершил восстановление заявок и позиций. Входы не включены.',
        FOREIGN_OKX_WRITER_ACTIVE: 'Другая программа продолжает отправлять реальные OKX-заявки. Входы автоматически поставлены на паузу.',
        OKX_EXCLUSIVE_WRITER_NOT_CONFIRMED: 'Старый OKX-клиент пока не исключён. Отключите его или ограничьте этот API-ключ IP-адресом 37.252.21.226.',
        EXCHANGE_NOT_FLAT: 'На выбранной бирже уже есть позиция, открытая не этим сервером. Сначала остановите старый клиент и штатно закройте его позицию.',
        PRIVATE_PREFLIGHT_FAILED: 'Проверьте ключи, торговые разрешения и One-Way режим на выбранных биржах.',
        OKX_MASTER_REQUIRED: 'OKX — источник сигнала и обязательный лидер. Отметьте OKX вместе с нужными ведомыми биржами.',
        NO_EXCHANGES_SELECTED: 'Отметьте хотя бы одну биржу.'
      };
      message = messages[body.reason] || body.reason || message;
    } catch {}
    throw new Error(message);
  }
  return response.json();
};

$('#saveToken').onclick = () => {
  token = $('#token').value.trim();
  localStorage.setItem('keytrins-token', token);
  refresh();
};

const updateSelectionCount = () => {
  $('#selectionCount').textContent = `Выбрано: ${selectedExchanges.size}`;
  $('#selectAll').textContent = selectedExchanges.size === exchanges.length ? 'Снять выбор' : 'Выбрать все 3';
};

$('#selectAll').onclick = () => {
  const select = selectedExchanges.size !== exchanges.length;
  selectedExchanges.clear();
  if (select) exchanges.forEach(exchange => selectedExchanges.add(exchange));
  document.querySelectorAll('.exchangeSelect input').forEach(check => { check.checked = select; });
  updateSelectionCount();
};

document.querySelectorAll('.tab').forEach(button => button.onclick = () => {
  document.querySelectorAll('.tab,.pane').forEach(element => element.classList.remove('active'));
  button.classList.add('active');
  $('#' + button.dataset.tab).classList.add('active');
  loadTab(button.dataset.tab);
});

const setPill = (element, mode) => {
  element.className = 'mode pill ' + (mode === 'Active' ? 'ok' : mode === 'Error' ? 'bad' : 'warn');
};

function exchangeCard(exchange, lastAttempt, sessionStartedAt) {
  const node = $('#exchangeTemplate').content.cloneNode(true);
  const card = node.querySelector('.card');
  card.querySelector('h3').textContent = exchange.exchange;
  const check = card.querySelector('input[type=checkbox]');
  check.checked = selectedExchanges.has(exchange.exchange);
  check.onchange = () => {
    check.checked ? selectedExchanges.add(exchange.exchange) : selectedExchanges.delete(exchange.exchange);
    updateSelectionCount();
  };
  const mode = card.querySelector('.mode');
  mode.textContent = exchange.mode === 'Disabling' ? 'ЗАКРЫТИЕ…' : exchange.mode;
  setPill(mode, exchange.mode);
  const metrics = [
    ['Рынок', exchange.publicConnected ? 'ЕСТЬ' : 'НЕТ'],
    ['Ключ', exchange.privateAuthenticated ? 'ГОТОВ' : 'НЕТ'],
    ['Торговля', exchange.tradingPermission ? 'РАЗРЕШЕНА' : 'НЕТ'],
    ['Вывод', exchange.withdrawPermission ? 'ОПАСНО' : 'ЗАПРЕЩЁН'],
    ['Баланс', exchange.balance ?? '—'],
    ['Позиций', exchange.openPositionCount]
  ];
  const metricsNode = card.querySelector('.metrics');
  metrics.forEach(([name, value]) => {
    const span = document.createElement('span');
    span.textContent = `${name}: `;
    const strong = document.createElement('b');
    strong.textContent = value;
    span.append(strong);
    metricsNode.append(span);
  });
  const historicalAttempt = lastAttempt && new Date(lastAttempt.receivedAt) < new Date(sessionStartedAt);
  card.querySelector('.lastResult').textContent = historicalAttempt || !lastAttempt
    ? 'Текущая сессия: ОЖИДАЕТ НОВЫЙ СИГНАЛ'
    : `Последний результат текущей сессии: ${lastAttempt.result}`;
  card.querySelector('.lastReason').textContent = historicalAttempt
    ? `ИСТОРИЯ ДО ТЕКУЩЕГО ЗАПУСКА: ${lastAttempt.reasonExplanation ?? lastAttempt.reason} [код: ${lastAttempt.reason}]`
    : lastAttempt
      ? `${lastAttempt.reasonExplanation ?? lastAttempt.reason} [код: ${lastAttempt.reason}]`
      : 'Новых попыток после запуска ещё не было.';
  card.querySelector('.detail').textContent = `Проверка подключения: ${exchange.detail}`;
  return node;
}

function positionRow(position) {
  return `<article class="positionCard"><div class="positionHead"><b>${esc(position.exchange)} · ${esc(position.symbol)}</b><span class="direction ${String(position.direction).toLowerCase()}">${esc(position.direction)}</span></div><div class="positionMetrics"><span>Вход<b>${esc(position.entryPrice)}</b></span><span>Сейчас<b>${esc(position.markPrice)}</b></span><span>Количество<b>${esc(position.remainingQuantity)}</b></span><span>Пик NET<b>${esc(position.peakNetProfitUsdt)} USDT</b></span><span>Защищено NET<b>${esc(position.protectedNetProfitUsdt)} USDT</b></span><span>Стоп<b>${esc(position.currentStop)}</b></span></div><small>Сигнал: ${esc(position.signalId)}</small></article>`;
}

function externalPositionRow(position) {
  return `<article class="positionCard external"><div class="positionHead"><b>${esc(position.exchange)} · ${esc(position.symbol)}</b><span class="pill bad">ВНЕШНЯЯ</span></div><div class="positionMetrics"><span>Направление<b>${esc(position.direction)}</b></span><span>Вход<b>${esc(position.entryPrice)}</b></span><span>Сейчас<b>${esc(position.markPrice)}</b></span><span>Количество<b>${esc(position.quantity)}</b></span><span>Стоп<b>${esc(position.stopPrice)}</b></span><span>Плечо<b>${esc(position.leverage)}</b></span></div></article>`;
}

function renderLiveStrategyPositions(positions) {
  const node = $('#strategyLivePositions');
  if (!positions.length) {
    node.className = 'strategyLivePositions empty';
    node.textContent = 'Живых сделок терминала сейчас нет.';
    return;
  }
  node.className = 'strategyLivePositions';
  node.innerHTML = positions.map(position => `<button class="livePosition" data-symbol="${esc(position.symbol)}"><span><b>${esc(position.exchange)}</b> · ${esc(position.symbol)}</span><span class="direction ${String(position.direction).toLowerCase()}">${esc(position.direction)}</span><span>Вход <b>${esc(position.entryPrice)}</b></span><span>Сейчас <b>${esc(position.markPrice)}</b></span><span>Стоп <b>${esc(position.currentStop)}</b></span><span>Пик NET <b>${esc(position.peakNetProfitUsdt)} USDT</b></span><span>Защита NET <b>${esc(position.protectedNetProfitUsdt)} USDT</b></span></button>`).join('');
  node.querySelectorAll('.livePosition').forEach(button => button.onclick = async () => {
    const symbol = button.dataset.symbol;
    const picker = $('#strategySymbol');
    if ([...picker.options].some(option => option.value === symbol)) picker.value = symbol;
    await refreshSelectedStrategy();
  });
}

function renderExchangePnl(rows) {
  const byExchange = new Map(rows.map(row => [row.exchange, row]));
  $('#exchangePnl').innerHTML = exchanges.map(exchange => {
    const row = byExchange.get(exchange);
    const pnl = Number(row?.unrealizedPnl ?? 0);
    const state = pnl > 0 ? 'profit' : pnl < 0 ? 'loss' : 'flat';
    const sign = pnl > 0 ? '+' : '';
    return `<article class="pnlTile ${state}"><span>${esc(exchange)}</span><b>${sign}${esc(pnl.toFixed(4))} USDT</b><small>текущий P/L · позиций ${esc(row?.openPositionCount ?? 0)}</small></article>`;
  }).join('');
}

async function refreshSelectedStrategy() {
  if (strategyRefreshInFlight || !$('#strategySymbol').value) return;
  strategyRefreshInFlight = true;
  try {
    strategyPayload = await api(`/api/strategy/chart?symbol=${encodeURIComponent($('#strategySymbol').value)}`);
    strategyPayload.positions = latestManagedPositions.filter(position => position.symbol === $('#strategySymbol').value);
    renderStrategy(strategyPayload);
  } finally {
    strategyRefreshInFlight = false;
  }
}

const decisionText = reason => ({
  SIGNAL: 'СИГНАЛ СОЗДАН',
  NO_H1_TREND: 'Нет подтверждённого тренда H1',
  NO_M15_PULLBACK: 'Не было возврата M15 в зону EMA 20/50',
  NO_M15_CONFIRMATION: 'Нет подтверждающей свечи M15',
  NOT_ENOUGH_BARS: 'Недостаточно закрытых свечей',
  INDICATOR_NAN: 'Индикаторы ещё не рассчитаны',
  INVALID_STOP: 'Получен недопустимый структурный стоп'
})[reason] || reason;

async function loadStrategy() {
  const overview = await api('/api/strategy/overview');
  const picker = $('#strategySymbol');
  const previous = picker.value;
  picker.innerHTML = overview.map(row => `<option value="${esc(row.symbol)}">${esc(row.symbol)} — ${esc(decisionText(row.decision))}</option>`).join('');
  if (!overview.length) return;
  picker.value = overview.some(row => row.symbol === previous) ? previous : overview[0].symbol;
  strategyPayload = await api(`/api/strategy/chart?symbol=${encodeURIComponent(picker.value)}`);
  renderStrategy(strategyPayload);
}

$('#strategySymbol').onchange = async () => {
  await refreshSelectedStrategy();
};

function renderStrategy(payload) {
  const chart = payload.chart;
  const signal = chart.signal;
  const pass = value => value ? 'ДА' : 'НЕТ';
  $('#strategyEvaluated').textContent = `Последний расчёт: ${chart.evaluatedAt}`;
  $('#strategyDecision').innerHTML = `<b>${esc(chart.symbol)}: ${esc(decisionText(chart.decision))}</b> · H1 тренд: ${pass(chart.h1TrendPassed)} · M15 возврат: ${pass(chart.pullbackPassed)} · M15 подтверждение: ${pass(chart.confirmationPassed)} · ADX ${esc(chart.adx?.toFixed?.(2))} / минимум ${esc(chart.adxMinimum)}${signal ? ` · базовый сигнал ${esc(signal.baseSignalDirection)} · фактический вход ${esc(signal.actualDirection)} · score ${esc(signal.score.toFixed(2))}` : ''}`;
  const positions = payload.positions || [];
  const current = positions.length ? positions.map(position => `${position.exchange}: entry ${position.entryPrice}, mark ${position.markPrice}, hard stop ${position.hardLossStop}, current stop ${position.currentStop}, peak NET ${position.peakNetProfitUsdt}, protected NET ${position.protectedNetProfitUsdt}`).join('<br>') : 'Активной позиции терминала по этому сигналу сейчас нет.';
  const locks = payload.management.dollarLock.map(level => `<span class="lockStep">+${esc(level.peakNet)} → ${Number(level.protectedNet) === 0 ? 'NET-безубыток' : `защита +${esc(level.protectedNet)}`}</span>`).join('');
  $('#strategyManagement').innerHTML = `<b>Сопровождение позиции</b><p>Удержание до аварийного NET-убытка −${esc(payload.management.maxNetLossUsdt)} USDT или до подтверждённого защитного стопа.</p><div class="lockSteps">${locks}</div><div class="managementPositions">${current}</div>`;
  drawStrategyChart(payload);
}

function drawStrategyChart(payload) {
  const canvas = $('#strategyChart');
  const box = canvas.getBoundingClientRect();
  const ratio = window.devicePixelRatio || 1;
  canvas.width = Math.max(1, Math.round(box.width * ratio));
  canvas.height = Math.max(1, Math.round(box.height * ratio));
  const ctx = canvas.getContext('2d');
  ctx.setTransform(ratio, 0, 0, ratio, 0, 0);
  const width = box.width, height = box.height;
  ctx.clearRect(0, 0, width, height);
  const points = payload.chart.points || [];
  if (!points.length) return;
  const compact = width < 600;
  const left = compact ? 48 : 64, right = compact ? 14 : 112, top = 20, bottom = compact ? 28 : 34;
  const plotW = Math.max(10, width - left - right), plotH = Math.max(10, height - top - bottom);
  const levels = [];
  const signal = payload.chart.signal;
  if (signal) {
    levels.push({ value: signal.okxEntryRef, label: 'СИГНАЛ', color: '#ffe16a' });
    levels.push({ value: signal.okxStopRef, label: 'СТОП', color: '#ff6f80' });
  }
  const exchangeColors = { Okx: '#67d8ff', Bybit: '#ffd46a', KuCoinFutures: '#c995ff' };
  (payload.positions || []).forEach(position => {
    const color = exchangeColors[position.exchange] || '#e8f1ff';
    levels.push({ value: position.entryPrice, label: `${position.exchange} ВХОД`, color, style: 'dot' });
    levels.push({ value: position.markPrice, label: `${position.exchange} СЕЙЧАС`, color, style: 'solid' });
    if (position.currentStop > 0) levels.push({ value: position.currentStop, label: `${position.exchange} СТОП`, color: '#73f0aa', style: 'dash' });
  });
  const prices = points.flatMap(point => [point.low, point.high]).concat(levels.map(level => level.value));
  let min = Math.min(...prices), max = Math.max(...prices);
  const pad = Math.max((max - min) * 0.08, Math.abs(max || 1) * 0.001);
  min -= pad; max += pad;
  const x = index => left + (index + 0.5) * plotW / points.length;
  const y = value => top + (max - value) / (max - min) * plotH;
  ctx.strokeStyle = '#17364b'; ctx.fillStyle = '#91a9bf'; ctx.font = '12px Segoe UI'; ctx.lineWidth = 1;
  for (let i = 0; i <= 5; i++) {
    const price = max - (max - min) * i / 5, yy = y(price);
    ctx.beginPath(); ctx.moveTo(left, yy); ctx.lineTo(width - right, yy); ctx.stroke();
    ctx.fillText(price.toPrecision(compact ? 5 : 7), 3, yy + 4);
  }
  const candleWidth = Math.max(2, Math.min(8, plotW / points.length * 0.62));
  points.forEach((point, index) => {
    const rising = point.close >= point.open;
    ctx.strokeStyle = rising ? '#50d99a' : '#ff6f80'; ctx.fillStyle = ctx.strokeStyle;
    ctx.beginPath(); ctx.moveTo(x(index), y(point.high)); ctx.lineTo(x(index), y(point.low)); ctx.stroke();
    const yy = Math.min(y(point.open), y(point.close));
    ctx.fillRect(x(index) - candleWidth / 2, yy, candleWidth, Math.max(1, Math.abs(y(point.open) - y(point.close))));
  });
  const drawLine = (property, color) => {
    ctx.strokeStyle = color; ctx.lineWidth = 1.6; ctx.beginPath(); let started = false;
    points.forEach((point, index) => { const value = point[property]; if (value == null) return; started ? ctx.lineTo(x(index), y(value)) : ctx.moveTo(x(index), y(value)); started = true; });
    if (started) ctx.stroke();
  };
  drawLine('emaFast', '#67d8ff'); drawLine('emaSlow', '#ffb45c');
  levels.forEach(level => {
    const yy = y(level.value); ctx.strokeStyle = level.color; ctx.fillStyle = level.color; ctx.lineWidth = 1.5;
    ctx.setLineDash(level.style === 'solid' ? [] : level.style === 'dot' ? [2, 4] : [6, 4]); ctx.beginPath(); ctx.moveTo(left, yy); ctx.lineTo(width - right, yy); ctx.stroke(); ctx.setLineDash([]);
    const label = `${level.label} ${level.value}`;
    if (compact) {
      const labelWidth = ctx.measureText(label).width;
      ctx.fillText(label, Math.max(left + 4, width - right - labelWidth - 4), yy - 4);
    } else {
      ctx.fillText(label, width - right + 7, yy + 4);
    }
  });
  ctx.strokeStyle = '#2b5671'; ctx.strokeRect(left, top, plotW, plotH);
  const labels = [0, Math.floor((points.length - 1) / 2), points.length - 1];
  labels.forEach(index => ctx.fillText(new Date(points[index].startMs).toLocaleString('ru-RU', { day:'2-digit', month:'2-digit', hour:'2-digit', minute:'2-digit' }), Math.max(left, Math.min(width - right - 95, x(index) - 40)), height - 10));
}

window.addEventListener('resize', () => { if (strategyPayload) drawStrategyChart(strategyPayload); });

async function refresh() {
  try {
    const status = await api('/api/status');
    latestManagedPositions = status.positions || [];
    $('#connection').textContent = 'ONLINE';
    $('#connection').className = 'pill ok';
    document.body.classList.add('connected');
    $('#authPanel').open = false;
    $('#masterHealth').textContent = status.masterHealth;
    const gate = $('#mutationGate');
    gate.textContent = `LIVE: ${status.mutationGate}`;
    gate.className = 'pill ' + (status.mutationGate === 'ARMED' ? 'bad' : 'warn');
    $('#universe').textContent = `Пар: ${status.universeCount}`;
    $('#lastScan').textContent = `Сканирование: ${status.lastScanAt ?? '—'}`;
    $('#lastSignal').textContent = `Сигнал: ${status.lastSignalId ?? '—'}`;
    const attempts = new Map((status.lastRouteAttempts || []).map(attempt => [attempt.exchange, attempt]));
    const grid = $('#exchangeGrid');
    grid.replaceChildren();
    status.exchanges.forEach(exchange => grid.append(exchangeCard(exchange, attempts.get(exchange.exchange), status.startedAt)));
    updateSelectionCount();
    renderExchangePnl(status.exchanges);
    renderLiveStrategyPositions(status.positions);
    $('#positions').innerHTML = status.positions.length ? status.positions.map(positionRow).join('') : 'Открытых позиций терминала нет';
    $('#externalPositions').innerHTML = status.externalPositions.length
      ? status.externalPositions.map(externalPositionRow).join('')
      : 'Внешних позиций нет';
    $('#serverInfo').textContent = JSON.stringify(status, null, 2);
    if (!runtimeSummaryLoaded) await loadRuntimeSummary();
    if ($('#strategy').classList.contains('active') && $('#strategySymbol').value) await refreshSelectedStrategy();
  } catch (error) {
    $('#connection').textContent = error.message;
    $('#connection').className = 'pill bad';
    document.body.classList.remove('connected');
    $('#authPanel').open = true;
  }
}

async function loadRuntimeSummary() {
  const settings = await api('/api/settings');
  $('#runtimeSummary').innerHTML = `<span>На биржу <b>${esc(settings.runtime.positionNotionalUsdt)} USDT</b></span><span>Аварийный NET-стоп <b>−${esc(settings.runtime.maxNetLossUsdt)} USDT</b></span><span>Одновременно <b>до 3 сигналов</b></span><span>Пар <b>${esc(settings.runtime.universeSize)}</b></span>`;
  runtimeSummaryLoaded = true;
}

async function loadTab(tab) {
  if (tab === 'history') {
    try {
      const rows = await api('/api/history?limit=100');
      const header = '<div class="row tableHeader"><span>Время</span><span>Биржа</span><span>Сигнал</span><span>Результат</span><span>Причина</span><span>Цена</span><span>Количество</span></div>';
      $('#historyTable').innerHTML = header + rows.map(row => `<div class="row"><span>${esc(row.received_at)}</span><b>${esc(row.exchange)}</b><span>${esc(row.signal_id)}</span><span>${esc(row.result)}</span><span>${esc(row.reason)}</span><span>${esc(row.entry_price)}</span><span>${esc(row.quantity)}</span></div>`).join('');
    } catch {}
  }
  if (tab === 'logs') {
    try {
      const rows = await api('/api/logs?limit=200');
      $('#logTable').innerHTML = rows.map(row => `<div class="row"><span>${esc(row.at)}</span><b>${esc(row.category)}</b><span>${esc(row.exchange)}</span><span>${esc(row.message)}</span></div>`).join('');
    } catch {}
  }
  if (tab === 'strategy') {
    try { await loadStrategy(); } catch (error) { $('#strategyDecision').textContent = error.message; }
  }
  if (tab === 'settings') await loadSettings();
}

$('#credentialExchange').innerHTML = exchanges.map(exchange => `<option value="${exchange}">${exchange}</option>`).join('');

function showCredentialStatus(rows) {
  $('#credentialStatus').innerHTML = exchanges.map(exchange => `<span class="${rows[exchange] ? 'configured' : ''}">${exchange}: ${rows[exchange] ? 'настроен' : 'нет ключа'}</span>`).join('');
}

async function loadSettings(force = false) {
  try {
    const settings = await api('/api/settings');
    if (!settingsLoaded || force) {
      $('#riskUsdt').value = settings.runtime.riskUsdt;
      $('#positionNotionalUsdt').value = settings.runtime.positionNotionalUsdt;
      $('#maxNetLossUsdt').value = settings.runtime.maxNetLossUsdt;
      $('#universeSize').value = settings.runtime.universeSize;
      $('#leverage').value = settings.runtime.leverage;
      $('#maxNotionalUsdt').value = settings.runtime.maxNotionalUsdt;
      $('#maxCostR').value = settings.runtime.maxCostR;
      settingsLoaded = true;
    }
    showCredentialStatus(settings.credentials);
    $('#runtimeSummary').innerHTML = `<span>На биржу <b>${esc(settings.runtime.positionNotionalUsdt)} USDT</b></span><span>Аварийный NET-стоп <b>−${esc(settings.runtime.maxNetLossUsdt)} USDT</b></span><span>Одновременно <b>до 3 сигналов</b></span><span>Пар <b>${esc(settings.runtime.universeSize)}</b></span>`;
    runtimeSummaryLoaded = true;
  } catch (error) {
    $('#runtimeResult').textContent = error.message;
  }
}

$('#saveRuntime').onclick = async () => {
  const body = {
    riskUsdt: Number($('#riskUsdt').value),
    positionNotionalUsdt: Number($('#positionNotionalUsdt').value),
    maxNetLossUsdt: Number($('#maxNetLossUsdt').value),
    universeSize: Number($('#universeSize').value),
    leverage: Number($('#leverage').value),
    maxNotionalUsdt: Number($('#maxNotionalUsdt').value),
    maxCostR: Number($('#maxCostR').value)
  };
  try {
    await api('/api/settings/runtime', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    $('#runtimeResult').textContent = 'Сохранено на сервере';
    await loadSettings(true);
  } catch (error) {
    $('#runtimeResult').textContent = error.message;
  }
};

$('#saveCredentials').onclick = async () => {
  const exchange = $('#credentialExchange').value;
  const body = { apiKey: $('#apiKey').value, apiSecret: $('#apiSecret').value, passphrase: $('#passphrase').value };
  try {
    const saved = await api(`/api/settings/credentials/${exchange}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    $('#apiKey').value = $('#apiSecret').value = $('#passphrase').value = '';
    $('#credentialResult').textContent = saved.verified ? `${exchange}: ключ проверен и готов` : `${exchange}: сохранён, но не проверен — ${saved.detail}`;
    await loadSettings();
    await refresh();
  } catch (error) {
    $('#credentialResult').textContent = error.message;
  }
};

$('#clearCredentials').onclick = async () => {
  const exchange = $('#credentialExchange').value;
  if (!confirm(`${exchange}: удалить сохранённый ключ?`)) return;
  try {
    await api(`/api/settings/credentials/${exchange}`, { method: 'DELETE' });
    $('#credentialResult').textContent = `${exchange}: сохранённый ключ удалён`;
    await loadSettings();
  } catch (error) {
    $('#credentialResult').textContent = error.message;
  }
};

async function batch(action) {
  const selected = [...selectedExchanges];
  if (!selected.length) {
    alert('Отметьте галочками нужные биржи.');
    return;
  }
  if (action === 'close-all-disable' && !confirm(`Закрыть и отключить: ${selected.join(', ')}?`)) return;
  try {
    if (action === 'enable') {
      await api('/api/exchanges/enable-selected', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ exchanges: selected }) });
    } else {
      for (const exchange of selected) await api(`/api/exchanges/${exchange}/${action}`, { method: 'POST' });
    }
    await refresh();
  } catch (error) {
    alert(error.message);
  }
}

$('#pauseSelected').onclick = () => batch('pause');
$('#enableSelected').onclick = () => batch('enable');
$('#closeSelected').onclick = () => batch('close-all-disable');
if ('serviceWorker' in navigator) navigator.serviceWorker.register('/service-worker.js');
refresh();
setInterval(refresh, 5000);
