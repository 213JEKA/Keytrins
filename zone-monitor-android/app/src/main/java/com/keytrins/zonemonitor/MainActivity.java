package com.keytrins.zonemonitor;

import static com.keytrins.zonemonitor.MarketModels.Snapshot;
import static com.keytrins.zonemonitor.MarketModels.Zone;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String[] SYMBOLS = {"EURUSD", "GBPUSD", "AUDUSD", "USDCAD"};
    private static final long REFRESH_MS = 15L * 60L * 1000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Button[] symbolButtons = new Button[SYMBOLS.length];
    private String symbol = "EURUSD";
    private String mode = "DEMO";
    private SecretStore secrets;
    private ZoneChartView chart;
    private TextView sourceBadge;
    private TextView titleLine;
    private TextView statusLine;
    private TextView supportLine;
    private TextView resistanceLine;
    private TextView detailLine;
    private ProgressBar progress;
    private boolean resumed;

    private final Runnable periodicRefresh = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            load();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        secrets = new SecretStore(this);
        mode = getPreferences(MODE_PRIVATE).getString("mode", "DEMO");
        symbol = getPreferences(MODE_PRIVATE).getString("symbol", "EURUSD");
        setContentView(buildUi());
        selectSymbol(symbol);
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        handler.removeCallbacks(periodicRefresh);
        periodicRefresh.run();
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(periodicRefresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF071019);
        LinearLayout root = column();
        root.setPadding(dp(16), dp(15), dp(16), dp(20));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        LinearLayout header = row();
        TextView brand = text("KEYTRINS  ZONE MONITOR", 17, 0xFFF1F6FA, Typeface.BOLD);
        header.addView(brand, new LinearLayout.LayoutParams(0, dp(42), 1));
        sourceBadge = text("DEMO", 10, 0xFF071019, Typeface.BOLD);
        sourceBadge.setGravity(Gravity.CENTER);
        sourceBadge.setBackground(rounded(0xFFF5C451, 15));
        header.addView(sourceBadge, new LinearLayout.LayoutParams(dp(90), dp(32)));
        Button settings = button("⚙", 0xFF172635, 0xFFD5E3EC);
        settings.setContentDescription("Настройки источника данных");
        settings.setOnClickListener(v -> showSettings());
        LinearLayout.LayoutParams gearParams = new LinearLayout.LayoutParams(dp(44), dp(36));
        gearParams.setMargins(dp(8), 0, 0, 0);
        header.addView(settings, gearParams);
        root.addView(header);

        titleLine = text("EURUSD  ·  M15", 24, 0xFFF5FAFD, Typeface.BOLD);
        root.addView(titleLine, margin(-1, dp(42), 0, dp(5), 0, dp(3)));
        TextView sub = text("Автоматическая карта повторных реакций и проторговок", 11,
                0xFF8294A8, Typeface.NORMAL);
        root.addView(sub, margin(-1, dp(24), 0, 0, 0, dp(10)));

        LinearLayout pairs = row();
        for (int i = 0; i < SYMBOLS.length; i++) {
            final String pair = SYMBOLS[i];
            symbolButtons[i] = button(pair.substring(0, 3) + "/" + pair.substring(3),
                    0xFF101E2B, 0xFF91A3B5);
            symbolButtons[i].setOnClickListener(v -> selectSymbol(pair));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(38), 1);
            if (i > 0) p.setMargins(dp(5), 0, 0, 0);
            pairs.addView(symbolButtons[i], p);
        }
        root.addView(pairs, margin(-1, dp(38), 0, 0, 0, dp(12)));

        LinearLayout chartCard = column();
        chartCard.setBackground(rounded(0xFF091522, 14));
        chart = new ZoneChartView(this);
        chartCard.addView(chart, new LinearLayout.LayoutParams(-1, dp(470)));
        root.addView(chartCard, margin(-1, dp(470), 0, 0, 0, dp(12)));

        LinearLayout signalCard = column();
        signalCard.setPadding(dp(14), dp(13), dp(14), dp(13));
        signalCard.setBackground(rounded(0xFF101E2B, 14));
        LinearLayout signalTop = row();
        statusLine = text("ЗАГРУЗКА…", 15, 0xFF6EE7F9, Typeface.BOLD);
        signalTop.addView(statusLine, new LinearLayout.LayoutParams(0, dp(30), 1));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        signalTop.addView(progress, new LinearLayout.LayoutParams(dp(30), dp(30)));
        signalCard.addView(signalTop);
        supportLine = text("Поддержка: —", 12, 0xFF8FE3BD, Typeface.NORMAL);
        resistanceLine = text("Сопротивление: —", 12, 0xFFFF98A8, Typeface.NORMAL);
        detailLine = text("Q — сила зоны от 0 до 100", 10, 0xFF8294A8, Typeface.NORMAL);
        signalCard.addView(supportLine, margin(-1, dp(25), 0, dp(2), 0, 0));
        signalCard.addView(resistanceLine, margin(-1, dp(25), 0, 0, 0, 0));
        signalCard.addView(detailLine, margin(-1, dp(24), 0, dp(5), 0, 0));
        root.addView(signalCard);

        TextView warning = text("Информационный инструмент. Сделки не открывает. Источник и задержка цены указаны сверху.",
                9, 0xFF64788A, Typeface.NORMAL);
        warning.setGravity(Gravity.CENTER);
        root.addView(warning, margin(-1, dp(42), dp(8), dp(9), dp(8), 0));
        return scroll;
    }

    private void selectSymbol(String value) {
        symbol = value;
        getPreferences(MODE_PRIVATE).edit().putString("symbol", value).apply();
        titleLine.setText(symbol.substring(0, 3) + "/" + symbol.substring(3) + "  ·  M15");
        for (int i = 0; i < SYMBOLS.length; i++) {
            boolean selected = SYMBOLS[i].equals(symbol);
            symbolButtons[i].setTextColor(selected ? 0xFF071019 : 0xFF91A3B5);
            symbolButtons[i].setBackground(rounded(selected ? 0xFF6EE7F9 : 0xFF101E2B, 10));
        }
        if (resumed) load();
    }

    private void load() {
        progress.setVisibility(View.VISIBLE);
        statusLine.setText("ОБНОВЛЕНИЕ…");
        final String requestedSymbol = symbol;
        final String requestedMode = mode;
        executor.submit(() -> {
            try {
                Snapshot snapshot;
                if ("TWELVE".equals(requestedMode)) {
                    snapshot = MarketDataClient.twelveData(requestedSymbol, secrets.get("twelve_api_key"));
                } else if ("BRIDGE".equals(requestedMode)) {
                    snapshot = MarketDataClient.bridge(requestedSymbol, secrets.get("bridge_url"));
                } else {
                    snapshot = MarketDataClient.demo(requestedSymbol);
                }
                handler.post(() -> {
                    if (!requestedSymbol.equals(symbol) || !requestedMode.equals(mode)) return;
                    render(snapshot);
                });
            } catch (Exception e) {
                String message = cleanError(e.getMessage());
                handler.post(() -> {
                    progress.setVisibility(View.GONE);
                    statusLine.setText("НЕТ ДАННЫХ");
                    detailLine.setText(message);
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void render(Snapshot snapshot) {
        progress.setVisibility(View.GONE);
        sourceBadge.setText(snapshot.source);
        int badge = "DEMO".equals(snapshot.source) ? 0xFFF5C451 :
                (snapshot.source.contains("MT5") ? 0xFFB58CFF : 0xFF34D399);
        sourceBadge.setBackground(rounded(badge, 15));
        statusLine.setText(snapshot.signal);
        chart.setSnapshot(snapshot);
        Zone support = nearest(snapshot, true);
        Zone resistance = nearest(snapshot, false);
        int digits = snapshot.price >= 10 ? 3 : 5;
        supportLine.setText(support == null ? "Поддержка: —" : "Поддержка: " +
                format(support.low, digits) + "–" + format(support.high, digits) + "  Q" + support.score);
        resistanceLine.setText(resistance == null ? "Сопротивление: —" : "Сопротивление: " +
                format(resistance.low, digits) + "–" + format(resistance.high, digits) + "  Q" + resistance.score);
        detailLine.setText("Цена " + format(snapshot.price, digits) + "  ·  зон " +
                snapshot.zones.size() + "  ·  " + snapshot.updatedAt);
    }

    private Zone nearest(Snapshot snapshot, boolean support) {
        Zone best = null;
        double distance = Double.MAX_VALUE;
        for (Zone z : snapshot.zones) {
            boolean fits = support ? z.high < snapshot.price : z.low > snapshot.price;
            if (!fits) continue;
            double d = Math.abs(z.center - snapshot.price);
            if (d < distance) { best = z; distance = d; }
        }
        return best;
    }

    private void showSettings() {
        String[] labels = {"Demo — работает сразу", "Twelve Data — внешний live feed", "MT5 Bridge — точные данные терминала"};
        int checked = "TWELVE".equals(mode) ? 1 : "BRIDGE".equals(mode) ? 2 : 0;
        new AlertDialog.Builder(this)
                .setTitle("Источник котировок")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (which == 1) editSecret("TWELVE", "API key Twelve Data", "twelve_api_key", true);
                    else if (which == 2) editSecret("BRIDGE", "HTTPS URL MT5 Bridge", "bridge_url", false);
                    else setMode("DEMO");
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void editSecret(String newMode, String title, String key, boolean password) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(secrets.get(key));
        input.setHint(password ? "Ваш API key" : "https://example.com/mt5/zones");
        input.setInputType(password ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD :
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        int pad = dp(20);
        LinearLayout holder = column();
        holder.setPadding(pad, 0, pad, 0);
        holder.addView(input, new LinearLayout.LayoutParams(-1, dp(55)));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(password ? "Ключ шифруется Android Keystore и не попадает в APK." :
                        "Bridge должен отвечать JSON со свечами M15. Только HTTPS.")
                .setView(holder)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) { Toast.makeText(this, "Поле пустое", Toast.LENGTH_SHORT).show(); return; }
                    if (!password && !value.startsWith("https://")) {
                        Toast.makeText(this, "Нужен адрес https://", Toast.LENGTH_LONG).show(); return;
                    }
                    try {
                        secrets.put(key, value);
                        setMode(newMode);
                    } catch (Exception e) {
                        Toast.makeText(this, "Не удалось защитить настройку", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void setMode(String value) {
        mode = value;
        getPreferences(MODE_PRIVATE).edit().putString("mode", value).apply();
        load();
    }

    private String cleanError(String message) {
        if (message == null || message.trim().isEmpty()) return "Не удалось загрузить данные";
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    private String format(double price, int digits) {
        return String.format(Locale.US, "%." + digits + "f", price);
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView text(String value, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans", style));
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private Button button(String value, int background, int foreground) {
        Button view = new Button(this);
        view.setText(value);
        view.setTextSize(10);
        view.setAllCaps(false);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(2), 0, dp(2), 0);
        view.setTextColor(foreground);
        view.setBackground(rounded(background, 10));
        return view;
    }

    private android.graphics.drawable.GradientDrawable rounded(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    private LinearLayout.LayoutParams margin(int width, int height, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
