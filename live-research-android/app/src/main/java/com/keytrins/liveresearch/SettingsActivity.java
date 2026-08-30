package com.keytrins.liveresearch;

import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import com.keytrins.liveresearch.bot.LiveResearchEngine;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends android.app.Activity {
    private EditText apiKey, apiSecret;
    private Switch testnetSwitch;
    private SettingsStore store;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        store = new SettingsStore(this);
        apiKey = findViewById(R.id.apiKey);
        apiSecret = findViewById(R.id.apiSecret);
        testnetSwitch = findViewById(R.id.testnetSwitch);
        Button save = findViewById(R.id.saveApiButton);
        Button doctor = findViewById(R.id.doctorApiButton);
        Button back = findViewById(R.id.backButton);

        SettingsStore.Snapshot s = store.load();
        apiKey.setText(s.apiKey);
        apiSecret.setText(s.apiSecret);
        apiSecret.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        testnetSwitch.setChecked(s.testnet);

        save.setOnClickListener(v -> save(true));
        doctor.setOnClickListener(v -> {
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
                    runOnUiThread(() -> {
                        doctor.setEnabled(true);
                        Toast.makeText(this, "Ошибка API: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        });
        back.setOnClickListener(v -> finish());
    }

    private boolean save(boolean toast) {
        try {
            store.saveCredentials(apiKey.getText().toString(), apiSecret.getText().toString());
            store.saveConnection(testnetSwitch.isChecked());
            if (toast) Toast.makeText(this, "API-настройки сохранены", Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка сохранения: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
