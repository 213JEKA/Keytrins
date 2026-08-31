package com.keytrins.liveresearch;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.keytrins.liveresearch.model.HedgeState;
import com.keytrins.liveresearch.model.Position;
import com.keytrins.liveresearch.model.TradeState;
import com.keytrins.liveresearch.net.BybitClient;
import com.keytrins.liveresearch.net.BybitHistoryClient;
import com.keytrins.liveresearch.storage.Db;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends android.app.Activity {
    private TextView statusText, balanceText, incomeText, metricsText, positionsText, historyText;
    private SettingsStore store;
    private Db dashboardDb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService dashboardWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean dashboardBusy = new AtomicBoolean(false);
    private int refreshTick = 0;
    private volatile boolean hasBybitHistory = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new SettingsStore(this);
        dashboardDb = new Db(this);

        statusText = findViewById(R.id.statusText);
        balanceText = findViewById(R.id.balanceText);
        incomeText = findViewById(R.id.incomeText);
        metricsText = findViewById(R.id.metricsText);
        positionsText = findViewById(R.id.positionsText);
        historyText = findViewById(R.id.historyText);
        Button settings = findViewById(R.id.settingsButton);
        Button start = findViewById(R.id.startButton);
        Button stop = findViewById(R.id.stopButton);

        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        start.setOnClickListener(v -> startBot());
        stop.setOnClickListener(v -> stopBot());

        historyText.setText(stripBalanceLines(dashboardDb.recentClosedTradesText(3)));
        requestNotificationPermission();
        BotRuntime.log("Live Research v0.1.3.7 HedgeFix готов.");
        refreshUiLoop();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
    }

    private void startBot() {
        SettingsStore.Snapshot s = store.load();
        if (s.live && (s.apiKey.isEmpty() || s.apiSecret.isEmpty())) {
            Toast.makeText(this, "Добавьте API key + secret в Настройках", Toast.LENGTH_LONG).show();
            return;
        }
        if (s.live && !BotRuntime.liveArmed) {
            confirmLiveAndStart();
            return;
        }
        startBotInternal(s);
    }

    private void confirmLiveAndStart() {
        final EditText input = new EditText(this);
        input.setHint("Введите LIVE");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        new AlertDialog.Builder(this)
                .setTitle("Запустить реальную торговлю?")
                .setMessage("Для текущего запуска подтвердите LIVE.")
                .setView(input)
                .setPositiveButton("Запустить", (d, w) -> {
                    if (!"LIVE".equals(input.getText().toString().trim())) {
                        BotRuntime.liveArmed = false;
                        Toast.makeText(this, "LIVE не подтверждён", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    BotRuntime.liveArmed = true;
                    startBotInternal(store.load());
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void startBotInternal(SettingsStore.Snapshot s) {
        Intent i = new Intent(this, BotService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        Toast.makeText(this, s.live ? "LIVE запущен" : "Наблюдение запущено", Toast.LENGTH_SHORT).show();
    }

    private void stopBot() {
        stopService(new Intent(this, BotService.class));
        BotRuntime.running.set(false);
        BotRuntime.status = "Остановлен";
        BotRuntime.log("Остановлен пользователем");
    }

    private void refreshUiLoop() {
        ui.postDelayed(new Runnable() {
            @Override public void run() {
                statusText.setText(BotRuntime.status);
                metricsText.setText(String.format(Locale.US,
                        "Universe  %d     •     Открыто  %d\nСканов  %d     •     Сигналов  %d\nВходов  %d     •     Сокращений  %d",
                        BotRuntime.universe, BotRuntime.openPositions,
                        BotRuntime.scans, BotRuntime.signals,
                        BotRuntime.entries, BotRuntime.reductions));
                positionsText.setText(BotRuntime.positionsText);
                if ((refreshTick++ % 3) == 0) pollRealDashboard();
                ui.postDelayed(this, 1000);
            }
        }, 250);
    }

    private void pollRealDashboard() {
        if (!dashboardBusy.compareAndSet(false, true)) return;
        SettingsStore.Snapshot snap = store.load();
        if (snap.apiKey.isEmpty() || snap.apiSecret.isEmpty()) {
            dashboardBusy.set(false);
            return;
        }
        dashboardWorker.submit(() -> {
            try {
                double balance;
                Map<String, Position> positions;
                try (BybitClient api = new BybitClient(snap)) {
                    balance = api.walletBalanceUsdt();
                    positions = api.openPositions();
                }

                String historyForUi = null;
                try (BybitHistoryClient historyApi = new BybitHistoryClient(snap)) {
                    List<BybitHistoryClient.ClosedTrade> raw = historyApi.recentClosed(12);
                    List<HistoryRow> cycles = collapseHistory(raw, 3);
                    if (!cycles.isEmpty()) historyForUi = renderBybitHistory(cycles);
                } catch (Exception historyError) {
                    BotRuntime.log("BYBIT HISTORY: " + historyError);
                    if (!hasBybitHistory) historyForUi = stripBalanceLines(dashboardDb.recentClosedTradesText(3));
                }

                Map<String, TradeState> tracked = dashboardDb.openTrades();
                Map<String, HedgeState> hedgeStates = dashboardDb.openHedges();
                BotRuntime.balance = balance;
                BotRuntime.openPositions = positions.size();
                BotRuntime.positionsText = renderPositions(positions, tracked, hedgeStates);

                double baseline = store.baselineBalance();
                if (Double.isNaN(baseline)) {
                    double dbBaseline = firstKnownBalance();
                    store.seedBaselineIfAbsent(dbBaseline > 0 ? dbBaseline : balance);
                    baseline = store.baselineBalance();
                }
                double unrealized = 0.0;
                for (Position p : positions.values()) unrealized += p.unrealisedPnl;
                double totalIncome = balance - baseline + unrealized;

                final double b = balance;
                final double inc = totalIncome;
                final String history = historyForUi;
                ui.post(() -> {
                    balanceText.setText(String.format(Locale.US, "%.2f", b));
                    incomeText.setText(String.format(Locale.US, "%+.2f", inc));
                    incomeText.setTextColor(getColor(inc >= 0 ? R.color.ok : R.color.danger));
                    if (history != null && !history.trim().isEmpty()) {
                        historyText.setText(history);
                        if (!history.startsWith("Закрытых сделок пока нет")) hasBybitHistory = true;
                    }
                });
            } catch (Exception e) {
                BotRuntime.log("DASHBOARD API: " + e);
            } finally {
                dashboardBusy.set(false);
            }
        });
    }

    private double firstKnownBalance() {
        String sql = "SELECT balance_open FROM closed_trades WHERE balance_open > 0 ORDER BY opened_at ASC LIMIT 1";
        try (Cursor c = dashboardDb.getReadableDatabase().rawQuery(sql, null)) {
            if (c.moveToFirst()) return c.getDouble(0);
        } catch (Exception ignored) { }
        return 0.0;
    }

    private String renderPositions(Map<String, Position> positions, Map<String, TradeState> tracked, Map<String, HedgeState> hedgeStates) {
        if (positions == null || positions.isEmpty()) return "Открытых сделок нет.";
        List<Position> rows = new ArrayList<>(positions.values());
        rows.sort((a,b) -> { int c=a.symbol.compareTo(b.symbol); return c!=0?c:Integer.compare(a.positionIdx,b.positionIdx); });
        StringBuilder b = new StringBuilder();
        for (Position p : rows) {
            TradeState t = tracked.get(p.symbol);
            HedgeState h = hedgeStates.get(p.symbol);
            boolean isHedge = h != null && p.side.equals(h.side) && (h.positionIdx==0 || p.positionIdx==h.positionIdx);
            if (b.length() > 0) b.append("\n\n");
            String direction = "Buy".equals(p.side) ? "LONG" : "SHORT";
            String state = isHedge ? "HEDGE • "+h.state : (t == null ? "BYBIT" : t.state);
            b.append(p.symbol).append("  ").append(direction).append("  •  ").append(state);
            b.append(String.format(Locale.US, "\nEntry %.8f   •   Mark %.8f   •   Qty %.8f", p.avgPrice, p.markPrice, p.size));
            b.append(String.format(Locale.US, "\nPnL %+.3f USDT", p.unrealisedPnl));
            if(isHedge){
                double sl=p.stopLoss>0?p.stopLoss:h.currentStop;
                if(sl>0)b.append(String.format(Locale.US,"   •   SL %.8f",sl));
                b.append(String.format(Locale.US,"\nHedge peak %+.2f   •   Protected ~%+.2f USDT",
                        Math.max(0.0,h.peakProfitUsdt),Math.max(0.0,h.protectedProfitUsdt)));
            } else if (t != null && p.side.equals(t.side)) {
                double r = 0;
                if (t.riskDistance > 0) r = "Buy".equals(t.side) ? (p.markPrice-t.entryPrice)/t.riskDistance : (t.entryPrice-p.markPrice)/t.riskDistance;
                b.append(String.format(Locale.US, "   •   %+.2fR", r));
                double sl = p.stopLoss > 0 ? p.stopLoss : t.currentStop;
                b.append(String.format(Locale.US, "   •   SL %.8f", sl));
                b.append(String.format(Locale.US, "\nPeak %+.2f   •   Protected ~%+.2f USDT",
                        Math.max(0.0, t.peakProfitUsdt), Math.max(0.0, t.protectedProfitUsdt)));
                double ha=t.entryAtr>0?t.entryAtr:t.atr;
                if(ha>0){ double ht="Buy".equals(t.side)?t.entryPrice-0.15*ha:t.entryPrice+0.15*ha; b.append(String.format(Locale.US,"\nHedge trigger %.8f • idx %d",ht,p.positionIdx)); }
                if(h!=null && t.tradeId!=null && t.tradeId.equals(h.primaryTradeId)) b.append(" • hedgeState ").append(h.state);
            } else if (p.stopLoss > 0) {
                b.append(String.format(Locale.US, "   •   SL %.8f", p.stopLoss));
            }
        }
        return b.toString();
    }

    private List<HistoryRow> collapseHistory(List<BybitHistoryClient.ClosedTrade> source, int limit) {
        List<HistoryRow> out = new ArrayList<>();
        if (source == null) return out;
        final long mergeWindow = 30L * 60_000L;
        for (BybitHistoryClient.ClosedTrade t : source) {
            HistoryRow match = null;
            for (HistoryRow r : out) {
                double scale = Math.max(1.0, Math.abs(t.avgEntryPrice));
                boolean sameEntry = Math.abs(r.entry - t.avgEntryPrice) <= scale * 0.000001;
                boolean closeInTime = Math.abs(r.updatedTime - t.updatedTime) <= mergeWindow;
                if (r.symbol.equals(t.symbol) && sameEntry && closeInTime) {
                    match = r;
                    break;
                }
            }
            if (match == null) {
                HistoryRow r = new HistoryRow();
                r.symbol = t.symbol;
                r.entry = t.avgEntryPrice;
                r.exitWeighted = t.avgExitPrice * t.closedSize;
                r.qty = t.closedSize;
                r.pnl = t.closedPnl;
                r.updatedTime = t.updatedTime;
                out.add(r);
            } else {
                match.exitWeighted += t.avgExitPrice * t.closedSize;
                match.qty += t.closedSize;
                match.pnl += t.closedPnl;
                match.updatedTime = Math.max(match.updatedTime, t.updatedTime);
            }
            if (out.size() >= limit + 4) break;
        }
        out.sort((a, b) -> Long.compare(b.updatedTime, a.updatedTime));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private String renderBybitHistory(List<HistoryRow> closed) {
        SimpleDateFormat time = new SimpleDateFormat("dd.MM HH:mm", Locale.US);
        StringBuilder b = new StringBuilder();
        int count = Math.min(3, closed.size());
        for (int i = 0; i < count; i++) {
            HistoryRow t = closed.get(i);
            if (b.length() > 0) b.append("\n\n");
            double exit = t.qty > 0 ? t.exitWeighted / t.qty : 0.0;
            b.append(t.symbol).append("  •  ")
                    .append(t.updatedTime > 0 ? time.format(new Date(t.updatedTime)) : "закрыта");
            if (t.entry > 0 || exit > 0) {
                b.append(String.format(Locale.US,
                        "\nEntry %.8f  →  Exit %.8f  •  Qty %.8f",
                        t.entry, exit, t.qty));
            }
            b.append(String.format(Locale.US, "\nРезультат: %+.3f USDT", t.pnl));
        }
        return b.length() == 0 ? "Закрытых сделок пока нет." : b.toString();
    }

    private String stripBalanceLines(String text) {
        if (text == null || text.isEmpty()) return "Закрытых сделок пока нет.";
        return text.replaceAll("(?m)^Баланс:.*(?:\\n|$)", "").trim();
    }

    private static final class HistoryRow {
        String symbol;
        double entry;
        double exitWeighted;
        double qty;
        double pnl;
        long updatedTime;
    }

    @Override protected void onDestroy() {
        if (dashboardDb != null) dashboardDb.close();
        dashboardWorker.shutdownNow();
        super.onDestroy();
    }
}
