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
        public String apiKey, apiSecret, apiPassphrase;
        public boolean testnet, live;
        public double riskUsdt, minTurnoverUsdt, maxNotionalUsdt, maxCostR, defaultTakerFee;
        public int universeSize, leverage;
        public double adxMin, atrSlMult, reduceTriggerR, forceReduceR, reduceFraction, beTriggerR, trailTriggerR, trailAtrMult;
        public int h1EmaFast, h1EmaSlow, h1SlopeBars, m15EmaFast, m15EmaSlow, pullbackLookback, atrPeriod, swingLookback;
    }

    public Snapshot load() {
        Snapshot s = new Snapshot();
        s.apiKey = vault.get("apiKey");
        s.apiSecret = vault.get("apiSecret");
        s.apiPassphrase = vault.get("apiPassphrase");
        s.testnet = p.getBoolean("testnet", false);
        s.live = p.getBoolean("live", false);
        s.riskUsdt = d("riskUsdt", 3.0);
        s.universeSize = i("universeSize", 30);
        s.minTurnoverUsdt = d("minTurnoverUsdt", 5_000_000.0);
        s.leverage = i("leverage", 5);
        s.maxNotionalUsdt = d("maxNotionalUsdt", 1000.0);
        s.maxCostR = d("maxCostR", 0.25);
        s.defaultTakerFee = d("defaultTakerFee", 0.00055);
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
        // Legacy fields remain for StrategyEngine/storage compatibility, but inverse v0.1.0 does not reduce or trail.
        s.reduceTriggerR = -999.0;
        s.forceReduceR = -999.0;
        s.reduceFraction = 0.0;
        s.beTriggerR = d("beTriggerR", 1.5);
        s.trailTriggerR = 999.0;
        s.trailAtrMult = 0.0;
        return s;
    }

    public void saveCredentials(String key, String secret, String passphrase) {
        vault.put("apiKey", key == null ? "" : key.trim());
        vault.put("apiSecret", secret == null ? "" : secret.trim());
        vault.put("apiPassphrase", passphrase == null ? "" : passphrase.trim());
    }

    public void saveBasic(boolean testnet, boolean live, double risk, int universe) {
        p.edit().putBoolean("testnet", testnet).putBoolean("live", live)
                .putString("riskUsdt", Double.toString(risk))
                .putInt("universeSize", universe).apply();
    }

    private double d(String k, double def) {
        try { return Double.parseDouble(p.getString(k, Double.toString(def))); }
        catch (Exception e) { return def; }
    }
    private int i(String k, int def) { return p.getInt(k, def); }
}
