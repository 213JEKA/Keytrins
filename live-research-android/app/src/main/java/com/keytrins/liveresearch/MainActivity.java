package com.keytrins.liveresearch;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
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
    private EditText riskInput, universeInput;
    private Switch liveSwitch;
    private TextView statusText, metricsText, positionsText, historyText, scanText, logText;
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

        riskInput = findViewById(R.id.riskInput);
        universeInput = findViewById(R.id.universeInput);
        liveSwitch = findViewById(R.id.liveSwitch);
        statusText = findViewById(R.id.statusText);
        metricsText = findViewById(R.id.metricsText);
        positionsText = findViewById(R.id.positionsText);
        historyText = findViewById(R.id.historyText);
        scanText = findViewById(R.id.scanText);
        logText = findViewById(R.id.logText);
        Button settings = findViewById(R.id.settingsButton);
        Button save = findViewById(R.id.saveButton);
        Button start = findViewById(R.id.startButton);
        Button stop = findViewById(R.id.stopButton);

        loadForm();
        liveSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isChecked) BotRuntime.liveArmed = false;
            if (isChecked && buttonView.isPressed()) confirmLive();
        });

        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        save.setOnClickListener(v -> saveForm(true));
        start.setOnClickListener(v -> startBot());
        stop.setOnClickListener(v -> stopBot());

        requestNotificationPermission();
        BotRuntime.log("Live Research v0.1.1 готов. Настройки и журнал сохраняются между обновлениями.");
        refreshUiLoop();
    }

    @Override protected void onResume() {
        super.onResume();
        loadForm();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
    }

    private void loadForm() {
        SettingsStore.Snapshot s = store.load();
        liveSwitch.setChecked(s.live);
        riskInput.setText(String.format(Locale.US, "%.2f", s.riskUsdt));
        universeInput.setText(Integer.toString(s.universeSize));
    }

    private boolean saveForm(boolean toast) {
        try {
            double risk = Double.parseDouble(riskInput.getText().toString().trim().replace(',', '.'));
            int universe = Integer.parseInt(universeInput.getText().toString().trim());
            if (risk <= 0 || risk > 100) throw new IllegalArgumentException("Риск должен быть 0–100 USDT");
            if (universe < 1 || universe > 100) throw new IllegalArgumentException("Активов должно быть 1–100");
            store.saveLive(liveSwitch.isChecked());
            store.saveStrategy(risk, universe);
            if (toast) Toast.makeText(this, "Стратегия сохранена", Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void confirmLive() {
        final EditText input = new EditText(this);
        input.setHint("Введите LIVE");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        new AlertDialog.Builder(this)
                .setTitle("Включить реальные ордера?")
                .setMessage("Робот сможет отправлять реальные ордера Bybit. API-ключ не должен иметь право вывода средств.\n\nЧтобы включить, введите LIVE.")
                .setView(input)
                .setPositiveButton("Включить", (d, w) -> {
                    if (!"LIVE".equals(input.getText().toString().trim())) {
                        BotRuntime.liveArmed = false;
                        liveSwitch.setChecked(false);
                        Toast.makeText(this, "Не включено", Toast.LENGTH_SHORT).show();
                    } else {
                        BotRuntime.liveArmed = true;
                        Toast.makeText(this, "LIVE разрешён для текущего запуска приложения", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Отмена", (d, w) -> liveSwitch.setChecked(false))
                .setOnCancelListener(d -> liveSwitch.setChecked(false))
                .show();
    }

    private void startBot() {
        if (!saveForm(false)) return;
        SettingsStore.Snapshot s = store.load();
        if (s.live && !BotRuntime.liveArmed) {
            Toast.makeText(this, "LIVE не подтверждён. Выключите и снова включите LIVE, затем введите LIVE.", Toast.LENGTH_LONG).show();
            return;
        }
        if (s.live && (s.apiKey.isEmpty() || s.apiSecret.isEmpty())) {
            Toast.makeText(this, "Откройте Настройки API и добавьте key + secret", Toast.LENGTH_LONG).show();
            return;
        }
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
                        "Реальный баланс Bybit: %.2f USDT\nUniverse: %d\nОткрыто позиций: %d\nСканов: %d\nСигналов: %d\nВходов: %d\nСокращений: %d",
                        BotRuntime.balance, BotRuntime.universe, BotRuntime.openPositions,
                        BotRuntime.scans, BotRuntime.signals, BotRuntime.entries, BotRuntime.reductions));
                positionsText.setText(BotRuntime.positionsText);
                scanText.setText(BotRuntime.lastScanText);
                if ((refreshTick++ % 3) == 0) {
                    historyText.setText(dashboardDb.recentClosedTradesText(12));
                    pollRealDashboard();
                }
                logText.setText(BotRuntime.logText());
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
                BotRuntime.positionsText = renderPositions(positions, tracked, balance);
            } catch (Exception e) {
                BotRuntime.log("DASHBOARD API: " + e.getMessage());
            } finally {
                dashboardBusy.set(false);
            }
        });
    }

    private String renderPositions(Map<String, Position> positions, Map<String, TradeState> tracked, double balance) {
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
            b.append(symbol).append(' ').append(p.side).append(" • ").append(state);
            b.append(String.format(Locale.US, "\nEntry %.8f • Mark %.8f • Qty %.8f", p.avgPrice, p.markPrice, p.size));
            b.append(String.format(Locale.US, "\nРеальный PnL Bybit: %+.3f USDT", p.unrealisedPnl));
            if (t != null) {
                b.append(String.format(Locale.US, " • %+.2fR", r));
                double sl = p.stopLoss > 0 ? p.stopLoss : t.currentStop;
                b.append(String.format(Locale.US, " • SL %.8f", sl));
            } else if (p.stopLoss > 0) {
                b.append(String.format(Locale.US, " • SL %.8f", p.stopLoss));
            }
            b.append(String.format(Locale.US, "\nРеальный баланс счёта: %.2f USDT", balance));
        }
        return b.toString();
    }

    @Override protected void onDestroy() {
        if (dashboardDb != null) dashboardDb.close();
        dashboardWorker.shutdownNow();
        super.onDestroy();
    }
}
