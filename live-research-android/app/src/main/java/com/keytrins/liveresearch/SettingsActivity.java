package com.keytrins.liveresearch;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.keytrins.liveresearch.bot.LiveResearchEngine;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends android.app.Activity {
    private EditText apiKey, apiSecret, riskInput, universeInput;
    private Switch testnetSwitch, liveSwitch;
    private SettingsStore store;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        store = new SettingsStore(this);

        apiKey = findViewById(R.id.apiKey);
        apiSecret = findViewById(R.id.apiSecret);
        riskInput = findViewById(R.id.riskInput);
        universeInput = findViewById(R.id.universeInput);
        testnetSwitch = findViewById(R.id.testnetSwitch);
        liveSwitch = findViewById(R.id.liveSwitch);
        Button save = findViewById(R.id.saveSettingsButton);
        Button doctor = findViewById(R.id.doctorApiButton);
        Button log = findViewById(R.id.logButton);
        Button resetIncome = findViewById(R.id.resetIncomeButton);
        Button back = findViewById(R.id.backButton);

        load();
        save.setOnClickListener(v -> save(true));
        doctor.setOnClickListener(v -> doctor(doctor));
        log.setOnClickListener(v -> showLog());
        resetIncome.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Сбросить отсчёт дохода?")
                .setMessage("Текущий баланс станет новой точкой отсчёта общего дохода.")
                .setPositiveButton("Сбросить", (d, w) -> {
                    store.clearBaseline();
                    Toast.makeText(this, "Отсчёт дохода сброшен", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Отмена", null)
                .show());
        back.setOnClickListener(v -> finish());
    }

    private void load() {
        SettingsStore.Snapshot s = store.load();
        apiKey.setText(s.apiKey);
        apiSecret.setText(s.apiSecret);
        apiSecret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        testnetSwitch.setChecked(s.testnet);
        liveSwitch.setChecked(s.live);
        riskInput.setText(String.format(Locale.US, "%.2f", s.riskUsdt));
        universeInput.setText(Integer.toString(s.universeSize));
    }

    private boolean save(boolean toast) {
        try {
            double risk = Double.parseDouble(riskInput.getText().toString().trim().replace(',', '.'));
            int universe = Integer.parseInt(universeInput.getText().toString().trim());
            if (risk <= 0 || risk > 100) throw new IllegalArgumentException("Риск должен быть 0–100 USDT");
            if (universe < 1 || universe > 100) throw new IllegalArgumentException("Активов должно быть 1–100");
            store.saveCredentials(apiKey.getText().toString(), apiSecret.getText().toString());
            store.saveBasic(testnetSwitch.isChecked(), liveSwitch.isChecked(), risk, universe);
            if (toast) Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка сохранения: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void doctor(Button doctor) {
        if (!save(false)) return;
        doctor.setEnabled(false);
        worker.submit(() -> {
            try {
                String result = LiveResearchEngine.doctor(this, store.load());
                BotRuntime.log(result);
                runOnUiThread(() -> {
                    doctor.setEnabled(true);
                    Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                });
            } catch (Exception e) {
                BotRuntime.log("DOCTOR ERROR: " + e);
                runOnUiThread(() -> {
                    doctor.setEnabled(true);
                    Toast.makeText(this, "Ошибка API: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showLog() {
        ScrollView sc = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(BotRuntime.logText());
        tv.setTextColor(getColor(R.color.text));
        tv.setTextSize(12f);
        int pad = Math.round(getResources().getDisplayMetrics().density * 12);
        tv.setPadding(pad, pad, pad, pad);
        sc.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Журнал")
                .setView(sc)
                .setPositiveButton("Закрыть", null)
                .show();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
