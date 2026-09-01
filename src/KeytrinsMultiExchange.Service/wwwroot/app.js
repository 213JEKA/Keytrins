const $ = selector => document.querySelector(selector);
const esc = value => String(value ?? '—').replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
})[character]);
let token = localStorage.getItem('keytrins-token') || '';
let settingsLoaded = false;
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

document.querySelectorAll('.tab').forEach(button => button.onclick = () => {
  document.querySelectorAll('.tab,.pane').forEach(element => element.classList.remove('active'));
  button.classList.add('active');
  $('#' + button.dataset.tab).classList.add('active');
  loadTab(button.dataset.tab);
});

const setPill = (element, mode) => {
  element.className = 'mode pill ' + (mode === 'Active' ? 'ok' : mode === 'Error' ? 'bad' : 'warn');
};

function exchangeCard(exchange, lastAttempt) {
  const node = $('#exchangeTemplate').content.cloneNode(true);
  const card = node.querySelector('.card');
  card.querySelector('h3').textContent = exchange.exchange;
  const check = card.querySelector('input[type=checkbox]');
  check.checked = selectedExchanges.has(exchange.exchange);
  check.onchange = () => check.checked
    ? selectedExchanges.add(exchange.exchange)
    : selectedExchanges.delete(exchange.exchange);
  const mode = card.querySelector('.mode');
  mode.textContent = exchange.mode === 'Disabling' ? 'ЗАКРЫТИЕ…' : exchange.mode;
  setPill(mode, exchange.mode);
  const metrics = [
    ['Public', exchange.publicConnected ? 'OK' : '—'],
    ['Private', exchange.privateAuthenticated ? 'OK' : '—'],
    ['Trade', exchange.tradingPermission ? 'YES' : 'NO'],
    ['Withdraw', exchange.withdrawPermission ? 'BLOCK!' : 'NO'],
    ['Balance', exchange.balance ?? '—'],
    ['Open', exchange.openPositionCount]
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
  card.querySelector('.lastResult').textContent = `Последний значимый результат: ${lastAttempt?.result ?? '—'}`;
  card.querySelector('.lastReason').textContent = lastAttempt?.reason ?? 'Сигналов для этой биржи ещё не было.';
  card.querySelector('.detail').textContent = `Проверка подключения: ${exchange.detail}`;
  return node;
}

function positionRow(position) {
  return `<div class="row"><b>${esc(position.exchange)} ${esc(position.symbol)}</b><span>${esc(position.direction)}</span><span>signal ${esc(position.signalId)}</span><span>entry ${esc(position.entryPrice)}</span><span>mark ${esc(position.markPrice)}</span><span>qty ${esc(position.remainingQuantity)}</span><span>peak ${esc(position.peakNetProfitUsdt)}</span><span>protected ${esc(position.protectedNetProfitUsdt)}</span><span>stop ${esc(position.currentStop)}</span></div>`;
}

async function refresh() {
  try {
    const status = await api('/api/status');
    $('#connection').textContent = 'ONLINE';
    $('#connection').className = 'pill ok';
    $('#masterHealth').textContent = status.masterHealth;
    const gate = $('#mutationGate');
    gate.textContent = `LIVE: ${status.mutationGate}`;
    gate.className = 'pill ' + (status.mutationGate === 'ARMED' ? 'bad' : 'warn');
    $('#universe').textContent = `Universe ${status.universeCount}`;
    $('#lastScan').textContent = `last scan ${status.lastScanAt ?? '—'}`;
    $('#lastSignal').textContent = `last signal ${status.lastSignalId ?? '—'}`;
    const attempts = new Map((status.lastRouteAttempts || []).map(attempt => [attempt.exchange, attempt]));
    const grid = $('#exchangeGrid');
    grid.replaceChildren();
    status.exchanges.forEach(exchange => grid.append(exchangeCard(exchange, attempts.get(exchange.exchange))));
    $('#positions').innerHTML = status.positions.length ? status.positions.map(positionRow).join('') : 'Открытых позиций нет';
    $('#serverInfo').textContent = JSON.stringify(status, null, 2);
  } catch (error) {
    $('#connection').textContent = error.message;
    $('#connection').className = 'pill bad';
  }
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
      $('#maxNetLossUsdt').value = settings.runtime.maxNetLossUsdt;
      $('#universeSize').value = settings.runtime.universeSize;
      $('#leverage').value = settings.runtime.leverage;
      $('#maxNotionalUsdt').value = settings.runtime.maxNotionalUsdt;
      $('#maxCostR').value = settings.runtime.maxCostR;
      settingsLoaded = true;
    }
    showCredentialStatus(settings.credentials);
  } catch (error) {
    $('#runtimeResult').textContent = error.message;
  }
}

$('#saveRuntime').onclick = async () => {
  const body = {
    riskUsdt: Number($('#riskUsdt').value),
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
