package com.keytrins.zonemonitor;

import static com.keytrins.zonemonitor.MarketModels.Candle;
import static com.keytrins.zonemonitor.MarketModels.Snapshot;
import static com.keytrins.zonemonitor.MarketModels.Zone;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

final class MarketDataClient {
    private MarketDataClient() {}

    static Snapshot demo(String symbol) {
        Snapshot s = build(symbol, "DEMO", DemoData.candles(symbol));
        s.updatedAt = utcNow();
        return s;
    }

    static Snapshot twelveData(String symbol, String apiKey) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) throw new Exception("Введите API key Twelve Data");
        String pair = symbol.substring(0, 3) + "/" + symbol.substring(3);
        String url = "https://api.twelvedata.com/time_series?symbol=" +
                URLEncoder.encode(pair, "UTF-8") +
                "&interval=15min&outputsize=320&timezone=UTC&order=ASC&apikey=" +
                URLEncoder.encode(apiKey.trim(), "UTF-8");
        JSONObject root = getJson(url);
        if (root.has("status") && "error".equalsIgnoreCase(root.optString("status"))) {
            throw new Exception(root.optString("message", "Ошибка Twelve Data"));
        }
        JSONArray values = root.optJSONArray("values");
        if (values == null || values.length() < 80) throw new Exception("Недостаточно свечей от Twelve Data");
        List<Candle> candles = new ArrayList<>();
        SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        parser.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (int i = 0; i < values.length(); i++) {
            JSONObject v = values.getJSONObject(i);
            Date date = parser.parse(v.getString("datetime"));
            if (date == null) continue;
            candles.add(new Candle(date.getTime(), d(v, "open"), d(v, "high"),
                    d(v, "low"), d(v, "close"), Math.max(1, v.optLong("volume", 1))));
        }
        candles.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        Snapshot s = build(symbol, "TWELVE DATA", candles);
        s.updatedAt = utcNow();
        return s;
    }

    static Snapshot bridge(String symbol, String baseUrl) throws Exception {
        if (baseUrl == null || baseUrl.trim().isEmpty()) throw new Exception("Введите HTTPS URL MT5 Bridge");
        String separator = baseUrl.contains("?") ? "&" : "?";
        JSONObject root = getJson(baseUrl.trim() + separator + "symbol=" +
                URLEncoder.encode(symbol, "UTF-8") + "&timeframe=M15&limit=320");
        JSONArray data = root.optJSONArray("candles");
        if (data == null || data.length() < 80) throw new Exception("Bridge вернул меньше 80 свечей");
        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject v = data.getJSONObject(i);
            long time = v.optLong("time", 0);
            if (time < 100000000000L) time *= 1000L;
            candles.add(new Candle(time, d(v, "open"), d(v, "high"), d(v, "low"),
                    d(v, "close"), Math.max(1, v.optLong("volume", 1))));
        }
        candles.sort((a, b) -> Long.compare(a.timeMs, b.timeMs));
        Snapshot s = build(symbol, "MT5 BRIDGE", candles);
        s.updatedAt = root.optString("updated_at", utcNow());

        JSONArray suppliedZones = root.optJSONArray("zones");
        if (suppliedZones != null && suppliedZones.length() > 0) {
            List<Zone> zones = new ArrayList<>();
            for (int i = 0; i < suppliedZones.length(); i++) {
                JSONObject j = suppliedZones.getJSONObject(i);
                Zone z = new Zone();
                z.low = d(j, "low");
                z.high = d(j, "high");
                z.center = (z.low + z.high) / 2.0;
                z.halfWidth = Math.max(0, (z.high - z.low) / 2.0);
                z.score = j.optInt("score", 55);
                z.type = j.optString("type", "TRADE").toUpperCase(Locale.US);
                z.supportTouches = j.optInt("touches", 0);
                z.acceptanceBars = j.optInt("acceptance_bars", 0);
                zones.add(z);
            }
            zones.sort((a, b) -> Integer.compare(b.score, a.score));
            s.zones = zones;
        }
        return s;
    }

    private static Snapshot build(String symbol, String source, List<Candle> candles) {
        Snapshot s = new Snapshot();
        s.symbol = symbol;
        s.source = source;
        s.candles = candles;
        if (!candles.isEmpty()) s.price = candles.get(candles.size() - 1).close;
        s.zones = ZoneEngine.calculate(candles);
        ZoneEngine.classify(s.zones, s.price);
        s.signal = signal(s.zones, s.price);
        return s;
    }

    private static String signal(List<Zone> zones, double price) {
        for (Zone z : zones) {
            if (price >= z.low && price <= z.high) {
                if (z.score >= 85) return "В СИЛЬНОЙ ЗОНЕ";
                return "В ЗОНЕ";
            }
        }
        return "ЖДЁМ ПОДХОДА";
    }

    private static double d(JSONObject object, String name) throws Exception {
        Object value = object.get(name);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(value.toString());
    }

    private static JSONObject getJson(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Keytrins-Zone-Monitor/0.1.0");
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) throw new Exception("HTTP " + code);
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) text.append(line);
        connection.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + text);
        return new JSONObject(text.toString());
    }

    private static String utcNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
