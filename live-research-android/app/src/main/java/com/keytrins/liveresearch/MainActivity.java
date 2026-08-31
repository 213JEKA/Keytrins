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

import com.keytrins.liveresearch.bot.LiveResearchEngine;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends android.app.Activity {
    private EditText apiKey, apiSecret, apiPassphrase, riskInput, universeInput;
    private Switch testnetSwitch, liveSwitch;
    private TextView statusText, metricsText, logText;
    private SettingsStore store;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        store = new SettingsStore(this);

        apiKey = findViewById(R.id.apiKey);
        apiSecret = findViewById(R.id.apiSecret);
        apiPassphrase = findViewById(R.id.apiPassphrase);
        riskInput = findViewById(R.id.riskInput);
        universeInput = findViewById(R.id.universeInput);
        testnetSwitch = findViewById(R.id.testnetSwitch);
        liveSwitch = findViewById(R.id.liveSwitch);
        statusText = findViewById(R.id.statusText);
        metricsText = findViewById(R.id.metricsText);
        logText = findViewById(R.id.logText);
        Button save = findViewById(R.id.saveButton);
        Button doctor = findViewById(R.id.doctorButton);
        Button start = findViewById(R.id.startButton);
        Button stop = findViewById(R.id.stopButton);

        loadForm();
        liveSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isChecked) BotRuntime.liveArmed = false;
            if (isChecked && buttonView.isPressed()) confirmLive();
        });

        save.setOnClickListener(v -> saveForm(true));
        doctor.setOnClickListener(v -> doctor());
        start.setOnClickListener(v -> startBot());
        stop.setOnClickListener(v -> stopBot());

        requestNotificationPermission();
        BotRuntime.log("OKX Inverse готов. LONG-сигнал открывает SHORT, SHORT-сигнал открывает LONG. LIVE по умолчанию выключен.");
        refreshUiLoop();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
    }

    private void loadForm() {
        SettingsStore.Snapshot s = store.load();
        apiKey.setText(s.apiKey);
        apiSecret.setText(s.apiSecret);
        apiPassphrase.setText(s.apiPassphrase);
        testnetSwitch.setChecked(s.testnet);
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
            store.saveCredentials(apiKey.getText().toString(), apiSecret.getText().toString(), apiPassphrase.getText().toString());
            store.saveBasic(testnetSwitch.isChecked(), liveSwitch.isChecked(), risk, universe);
            if (toast) Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
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
                .setTitle("Включить реальные OKX ордера?")
                .setMessage("OKX Inverse сможет отправлять реальные SWAP ордера. LONG-сигнал стратегии исполняется как SHORT, SHORT-сигнал — как LONG. После +1.5R стоп переносится в безубыток с покрытием расходов. API-ключ не должен иметь право вывода средств.\n\nЧтобы включить, введите LIVE.")
                .setView(input)
                .setPositiveButton("Включить", (d, w) -> {
                    if (!"LIVE".equals(input.getText().toString().trim())) {
                        BotRuntime.liveArmed = false;
                        liveSwitch.setChecked(false);
                        Toast.makeText(this, "Не включено", Toast.LENGTH_SHORT).show();
                    } else {
                        BotRuntime.liveArmed = true;
                        Toast.makeText(this, "OKX LIVE разрешён для текущего запуска приложения", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Отмена", (d, w) -> liveSwitch.setChecked(false))
                .setOnCancelListener(d -> liveSwitch.setChecked(false))
                .show();
    }

    private void doctor() {
        if (!saveForm(false)) return;
        statusText.setText("Проверка OKX API…");
        worker.submit(() -> {
            try {
                SettingsStore.Snapshot s = store.load();
                String result = LiveResearchEngine.doctor(this, s);
                BotRuntime.log(result);
                ui.post(() -> Toast.makeText(this, result, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                BotRuntime.log("DOCTOR ERROR: " + e.getMessage());
                ui.post(() -> Toast.makeText(this, "Ошибка проверки: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void startBot() {
        if (!saveForm(false)) return;
        SettingsStore.Snapshot s = store.load();
        if (s.live && !BotRuntime.liveArmed) {
            Toast.makeText(this, "LIVE не подтверждён. Выключите и снова включите LIVE, затем введите LIVE.", Toast.LENGTH_LONG).show();
            return;
        }
        if (s.live && (s.apiKey.isEmpty() || s.apiSecret.isEmpty() || s.apiPassphrase.isEmpty())) {
            Toast.makeText(this, "Для OKX LIVE нужны API key + secret + passphrase", Toast.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, BotService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        Toast.makeText(this, s.live ? "OKX Inverse LIVE запущен" : "OKX Inverse наблюдение запущено", Toast.LENGTH_SHORT).show();
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
                        "Баланс: %.2f USDT\nUniverse: %d\nОткрыто позиций: %d\nСканов: %d\nСигналов: %d\nВходов: %d",
                        BotRuntime.balance, BotRuntime.universe, BotRuntime.openPositions,
                        BotRuntime.scans, BotRuntime.signals, BotRuntime.entries));
                logText.setText(BotRuntime.logText());
                ui.postDelayed(this, 1000);
            }
        }, 250);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }
}
