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

import com.keytrins.liveresearch.model.Position;
import com.keytrins.liveresearch.model.TradeState;
import com.keytrins.liveresearch.net.BybitClient;
import com.keytrins.liveresearch.storage.Db;

import java.util.ArrayList;
import java.util.Collections;
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

        requestNotificationPermission();
        BotRuntime.log("Live Research v0.1.3 готов.");
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
                if ((refreshTick++ % 3) == 0) {
                    historyText.setText(stripBalanceLines(dashboardDb.recentClosedTradesText(3)));
                    pollRealDashboard();
                }
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
            try (BybitClient api = new BybitClient(snap)) {
                double balance = api.walletBalanceUsdt();
                Map<String, Position> positions = api.openPositions();
                Map<String, TradeState> tracked = dashboardDb.openTrades();
                BotRuntime.balance = balance;
                BotRuntime.openPositions = positions.size();
                BotRuntime.positionsText = renderPositions(positions, tracked);

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
                ui.post(() -> {
                    balanceText.setText(String.format(Locale.US, "%.2f", b));
                    incomeText.setText(String.format(Locale.US, "%+.2f", inc));
                    incomeText.setTextColor(getColor(inc >= 0 ? R.color.ok : R.color.danger));
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

    private String renderPositions(Map<String, Position> positions, Map<String, TradeState> tracked) {
        if (positions == null || positions.isEmpty()) return "Открытых сделок нет.";
        List<String> symbols = new ArrayList<>(positions.keySet());
        Collections.sort(symbols);
        StringBuilder b = new StringBuilder();
        for (String symbol : symbols) {
            Position p = positions.get(symbol);
            TradeState t = tracked.get(symbol);
            if (b.length() > 0) b.append("\n\n");
            String state = t == null ? "BYBIT" : t.state;
            double r = 0;
            if (t != null && t.riskDistance > 0) {
                r = "Buy".equals(t.side) ? (p.markPrice - t.entryPrice) / t.riskDistance : (t.entryPrice - p.markPrice) / t.riskDistance;
            }
            String direction = "Buy".equals(p.side) ? "LONG" : "SHORT";
            b.append(symbol).append("  ").append(direction).append("  •  ").append(state);
            b.append(String.format(Locale.US, "\nEntry %.8f   •   Mark %.8f   •   Qty %.8f", p.avgPrice, p.markPrice, p.size));
            b.append(String.format(Locale.US, "\nPnL %+.3f USDT", p.unrealisedPnl));
            if (t != null) {
                b.append(String.format(Locale.US, "   •   %+.2fR", r));
                double sl = p.stopLoss > 0 ? p.stopLoss : t.currentStop;
                b.append(String.format(Locale.US, "   •   SL %.8f", sl));
            } else if (p.stopLoss > 0) {
                b.append(String.format(Locale.US, "   •   SL %.8f", p.stopLoss));
            }
        }
        return b.toString();
    }

    private String stripBalanceLines(String text) {
        if (text == null || text.isEmpty()) return "Закрытых сделок пока нет.";
        return text.replaceAll("(?m)^Баланс:.*(?:\\n|$)", "").trim();
    }

    @Override protected void onDestroy() {
        if (dashboardDb != null) dashboardDb.close();
        dashboardWorker.shutdownNow();
        super.onDestroy();
    }
}
