package com.keytrins.liveresearch;

import android.content.Context;
import android.content.SharedPreferences;

public final class SettingsStore {
    private static final String PREFS = "settings";
    private final SharedPreferences p;
    private final CryptoVault vault;

    public SettingsStore(Context context) {
        p = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        vault = new CryptoVault(context);
    }

    public static final class Snapshot {
        public String apiKey, apiSecret, apiPassphrase, apiKeyVersion;
        public boolean testnet, live;
        public double riskUsdt, minTurnoverUsdt, maxNotionalUsdt, maxCostR, defaultTakerFee;
        public int universeSize, leverage, minAgeDays;
        public double adxMin, atrSlMult, reduceTriggerR, forceReduceR, reduceFraction, beTriggerR, trailTriggerR, trailAtrMult;
        public int h1EmaFast, h1EmaSlow, h1SlopeBars, m15EmaFast, m15EmaSlow, pullbackLookback, atrPeriod, swingLookback;
    }

    public Snapshot load() {
        Snapshot s = new Snapshot();
        s.apiKey = vault.get("apiKey");
        s.apiSecret = vault.get("apiSecret");
        s.apiPassphrase = vault.get("apiPassphrase");
        s.apiKeyVersion = vault.get("apiKeyVersion");
        if (s.apiKeyVersion == null || s.apiKeyVersion.trim().isEmpty()) s.apiKeyVersion = "3";
        s.testnet = false;
        s.live = p.getBoolean("live", false);
        s.riskUsdt = d("riskUsdt", 3.0);
        s.universeSize = i("universeSize", 30);
        s.minTurnoverUsdt = d("minTurnoverUsdt", 5_000_000.0);
        s.minAgeDays = i("minAgeDays", 30);
        s.leverage = i("leverage", 5);
        s.maxNotionalUsdt = d("maxNotionalUsdt", 1000.0);
        s.maxCostR = d("maxCostR", 0.25);
        s.defaultTakerFee = d("defaultTakerFee", 0.00060);
        s.adxMin = d("adxMin", 22.0);
        s.h1EmaFast = i("h1EmaFast", 50);
        s.h1EmaSlow = i("h1EmaSlow", 200);
        s.h1SlopeBars = i("h1SlopeBars", 3);
        s.m15EmaFast = i("m15EmaFast", 20);
        s.m15EmaSlow = i("m15EmaSlow", 50);
        s.pullbackLookback = i("pullbackLookback", 4);
        s.atrPeriod = i("atrPeriod", 14);
        s.atrSlMult = d("atrSlMult", 1.2);
        s.swingLookback = i("swingLookback", 5);
        s.reduceTriggerR = d("reduceTriggerR", -0.20);
        s.forceReduceR = d("forceReduceR", -0.35);
        s.reduceFraction = d("reduceFraction", 0.85);
        s.beTriggerR = d("beTriggerR", 1.5);
        s.trailTriggerR = d("trailTriggerR", 2.0);
        s.trailAtrMult = d("trailAtrMult", 2.2);
        return s;
    }

    public void saveCredentials(String key, String secret, String passphrase, String keyVersion) {
        vault.put("apiKey", key == null ? "" : key.trim());
        vault.put("apiSecret", secret == null ? "" : secret.trim());
        vault.put("apiPassphrase", passphrase == null ? "" : passphrase.trim());
        String v = keyVersion == null ? "" : keyVersion.trim();
        vault.put("apiKeyVersion", v.isEmpty() ? "3" : v);
    }

    public void saveConnection(boolean demo) { p.edit().putBoolean("testnet", false).apply(); }
    public void saveLive(boolean live) { p.edit().putBoolean("live", live).apply(); }
    public void saveStrategy(double risk, int universe) {
        p.edit().putString("riskUsdt", Double.toString(risk)).putInt("universeSize", universe).apply();
    }
    public void saveBasic(boolean demo, boolean live, double risk, int universe) {
        saveConnection(false); saveLive(live); saveStrategy(risk, universe);
    }

    public double baselineBalance() {
        long bits = p.getLong("baselineBalanceBits", Long.MIN_VALUE);
        return bits == Long.MIN_VALUE ? Double.NaN : Double.longBitsToDouble(bits);
    }
    public void seedBaselineIfAbsent(double balance) {
        if (!(balance > 0) || p.contains("baselineBalanceBits")) return;
        p.edit().putLong("baselineBalanceBits", Double.doubleToRawLongBits(balance)).apply();
    }
    public void setBaselineBalance(double balance) {
        if (!(balance > 0)) return;
        p.edit().putLong("baselineBalanceBits", Double.doubleToRawLongBits(balance)).apply();
    }
    public void clearBaseline() { p.edit().remove("baselineBalanceBits").apply(); }

    private double d(String k, double def) {
        try { return Double.parseDouble(p.getString(k, Double.toString(def))); }
        catch (Exception e) { return def; }
    }
    private int i(String k, int def) { return p.getInt(k, def); }
}
