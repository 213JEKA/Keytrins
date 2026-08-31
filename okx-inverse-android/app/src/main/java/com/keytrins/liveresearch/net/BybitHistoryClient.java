package com.keytrins.liveresearch.net;

import com.keytrins.liveresearch.SettingsStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Read-only Bybit client used only by the dashboard history.
 * Trading requests intentionally remain in BybitClient.
 */
public final class BybitHistoryClient implements AutoCloseable {
    private static final long RECV_WINDOW = 5000L;

    private final SettingsStore.Snapshot s;
    private final String base;

    public BybitHistoryClient(SettingsStore.Snapshot s) {
        this.s = s;
        this.base = s.testnet ? "https://api-testnet.bybit.com" : "https://api.bybit.com";
    }

    public List<ClosedTrade> recentClosed(int limit) throws Exception {
        requireKey();
        int n = Math.max(1, Math.min(200, limit));
        LinkedHashMap<String,String> q = new LinkedHashMap<>();
        q.put("category", "linear");
        q.put("limit", Integer.toString(n));

        JSONObject r = privateGet("/v5/position/closed-pnl", q);
        JSONArray list = r.getJSONObject("result").getJSONArray("list");
        List<ClosedTrade> out = new ArrayList<>();
        for (int i = 0; i < list.length() && out.size() < n; i++) {
            JSONObject x = list.getJSONObject(i);
            String symbol = x.optString("symbol", "");
            if (symbol.isEmpty() || !symbol.endsWith("USDT")) continue;
            out.add(new ClosedTrade(
                    symbol,
                    x.optString("side", ""),
                    d(x, "closedSize"),
                    d(x, "avgEntryPrice"),
                    d(x, "avgExitPrice"),
                    d(x, "closedPnl"),
                    l(x, "updatedTime")));
        }
        return out;
    }

    private JSONObject privateGet(String path, LinkedHashMap<String,String> params) throws Exception {
        String query = query(params);
        long ts = System.currentTimeMillis();
        String sign = hmac(ts + s.apiKey + RECV_WINDOW + query, s.apiSecret);
        Map<String,String> headers = new HashMap<>();
        headers.put("X-BAPI-API-KEY", s.apiKey);
        headers.put("X-BAPI-TIMESTAMP", Long.toString(ts));
        headers.put("X-BAPI-SIGN", sign);
        headers.put("X-BAPI-RECV-WINDOW", Long.toString(RECV_WINDOW));
        return request(path + (query.isEmpty() ? "" : "?" + query), headers);
    }

    private JSONObject request(String path, Map<String,String> headers) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "LiveResearchAndroid/0.1.3.2");
        for (Map.Entry<String,String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());

        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String text = read(in);
        c.disconnect();
        if (text.isEmpty()) throw new IllegalStateException("Пустой HTTP ответ " + code);

        JSONObject r = new JSONObject(text);
        int ret = r.optInt("retCode", -1);
        if (code < 200 || code >= 300 || ret != 0) {
            throw new IllegalStateException("Bybit " + ret + ": " + r.optString("retMsg", "HTTP " + code));
        }
        return r;
    }

    private void requireKey() {
        if (s.apiKey == null || s.apiKey.isEmpty() || s.apiSecret == null || s.apiSecret.isEmpty()) {
            throw new IllegalStateException("API key/secret не заданы");
        }
    }

    private static String query(LinkedHashMap<String,String> p) throws Exception {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String,String> e : p.entrySet()) {
            if (b.length() > 0) b.append('&');
            b.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return b.toString();
    }

    private static String hmac(String value, String secret) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] x = m.doFinal(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (byte z : x) b.append(String.format(Locale.US, "%02x", z & 255));
        return b.toString();
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder b = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) b.append(line);
            return b.toString();
        }
    }

    private static double d(JSONObject x, String k) {
        try {
            String v = x.optString(k, "");
            return v == null || v.isEmpty() ? 0.0 : Double.parseDouble(v);
        } catch (Exception e) {
            return x.optDouble(k, 0.0);
        }
    }

    private static long l(JSONObject x, String k) {
        try {
            String v = x.optString(k, "");
            return v == null || v.isEmpty() ? 0L : Long.parseLong(v);
        } catch (Exception e) {
            return x.optLong(k, 0L);
        }
    }

    @Override public void close() { }

    public static final class ClosedTrade {
        public final String symbol;
        public final String closeSide;
        public final double closedSize;
        public final double avgEntryPrice;
        public final double avgExitPrice;
        public final double closedPnl;
        public final long updatedTime;

        public ClosedTrade(String symbol, String closeSide, double closedSize,
                           double avgEntryPrice, double avgExitPrice,
                           double closedPnl, long updatedTime) {
            this.symbol = symbol;
            this.closeSide = closeSide;
            this.closedSize = closedSize;
            this.avgEntryPrice = avgEntryPrice;
            this.avgExitPrice = avgExitPrice;
            this.closedPnl = closedPnl;
            this.updatedTime = updatedTime;
        }
    }
}
