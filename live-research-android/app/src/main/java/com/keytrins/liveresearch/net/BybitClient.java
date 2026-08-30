package com.keytrins.liveresearch.net;

import com.keytrins.liveresearch.SettingsStore;
import com.keytrins.liveresearch.model.Candle;
import com.keytrins.liveresearch.model.Instrument;
import com.keytrins.liveresearch.model.Position;
import com.keytrins.liveresearch.model.Ticker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class BybitClient implements AutoCloseable {
    private static final Set<String> CRYPTO_BASES = new HashSet<>(Arrays.asList(
            "BTC","ETH","SOL","XRP","BNB","DOGE","ADA","TRX","LINK","AVAX","SUI","LTC","BCH","DOT",
            "AAVE","UNI","NEAR","ETC","FIL","ATOM","TON","ARB","OP","INJ","RENDER","ICP","XLM","HBAR",
            "ALGO","SEI","TIA","WLD","PEPE","SHIB","BONK","TAO","JUP","ENA","ONDO","PENDLE","CRV","LDO",
            "FET","KAS","PYTH","RUNE","POL","MATIC","APT","GALA","SAND","MANA","IMX","MKR","COMP","SNX",
            "DYDX","AR","STX","EGLD","FLOW","XTZ","THETA","QNT","ZEC","DASH","IOTA","KAVA","CAKE","GMX"));

    private final SettingsStore.Snapshot s;
    private final String base;
    private static final long RECV_WINDOW = 5000L;

    public BybitClient(SettingsStore.Snapshot s) {
        this.s = s;
        this.base = s.testnet ? "https://api-testnet.bybit.com" : "https://api.bybit.com";
    }

    public long serverTimeMs() throws Exception {
        JSONObject r = publicGet("/v5/market/time", new LinkedHashMap<>());
        JSONObject result = r.optJSONObject("result");
        if (result != null) {
            String sec = result.optString("timeSecond", "");
            if (!sec.isEmpty()) return Long.parseLong(sec) * 1000L;
            String nano = result.optString("timeNano", "");
            if (!nano.isEmpty()) return Long.parseLong(nano.substring(0, Math.min(13, nano.length())));
        }
        return r.optLong("time", System.currentTimeMillis());
    }

    public Map<String, Instrument> getInstruments() throws Exception {
        Map<String, Instrument> out = new HashMap<>();
        String cursor = "";
        do {
            LinkedHashMap<String,String> q = new LinkedHashMap<>();
            q.put("category", "linear"); q.put("limit", "1000");
            if (!cursor.isEmpty()) q.put("cursor", cursor);
            JSONObject r = publicGet("/v5/market/instruments-info", q);
            JSONObject result = result(r);
            JSONArray list = result.getJSONArray("list");
            for (int i = 0; i < list.length(); i++) {
                JSONObject x = list.getJSONObject(i);
                String symbol = x.optString("symbol");
                String settle = x.optString("settleCoin");
                String status = x.optString("status");
                String baseCoin = x.optString("baseCoin");
                String contractType = x.optString("contractType", "");
                long launchTime = x.optLong("launchTime", 0L);
                long minLaunch = System.currentTimeMillis() - Math.max(0, s.minAgeDays) * 24L * 60L * 60_000L;
                if (!"USDT".equals(settle) || !"Trading".equals(status) || !symbol.endsWith("USDT")) continue;
                if (!CRYPTO_BASES.contains(baseCoin)) continue;
                if (!contractType.isEmpty() && !"LinearPerpetual".equals(contractType)) continue;
                if (launchTime > 0 && launchTime > minLaunch) continue;
                JSONObject pf = x.getJSONObject("priceFilter");
                JSONObject lf = x.getJSONObject("lotSizeFilter");
                BigDecimal tick = bd(pf.optString("tickSize", "0.00000001"));
                BigDecimal step = bd(lf.optString("qtyStep", "0.000001"));
                BigDecimal minQty = bd(lf.optString("minOrderQty", "0"));
                String maxMarket = lf.optString("maxMktOrderQty", lf.optString("maxMarketOrderQty", "999999999"));
                BigDecimal maxQty = bd(maxMarket.isEmpty() ? "999999999" : maxMarket);
                BigDecimal minNotional = bd(lf.optString("minNotionalValue", "0"));
                out.put(symbol, new Instrument(symbol, baseCoin, contractType, launchTime,
                        tick, step, minQty, maxQty, minNotional));
            }
            cursor = result.optString("nextPageCursor", "");
        } while (!cursor.isEmpty());
        return out;
    }

    public Map<String, Ticker> getAllTickers() throws Exception {
        LinkedHashMap<String,String> q = new LinkedHashMap<>(); q.put("category", "linear");
        JSONObject r = publicGet("/v5/market/tickers", q);
        JSONArray list = result(r).getJSONArray("list");
        Map<String,Ticker> out = new HashMap<>();
        for (int i=0; i<list.length(); i++) {
            JSONObject x = list.getJSONObject(i);
            String symbol = x.optString("symbol");
            if (!symbol.endsWith("USDT")) continue;
            out.put(symbol, new Ticker(symbol,
                    d(x,"lastPrice"), d(x,"markPrice"), d(x,"bid1Price"), d(x,"ask1Price"),
                    d(x,"turnover24h"), d(x,"fundingRate")));
        }
        return out;
    }

    public List<Candle> getKlines(String symbol, String interval, int limit) throws Exception {
        LinkedHashMap<String,String> q = new LinkedHashMap<>();
        q.put("category","linear"); q.put("symbol",symbol); q.put("interval",interval); q.put("limit",Integer.toString(limit));
        JSONObject r = publicGet("/v5/market/kline", q);
        JSONArray rows = result(r).getJSONArray("list");
        List<Candle> out = new ArrayList<>();
        for (int i=0;i<rows.length();i++) {
            JSONArray x = rows.getJSONArray(i);
            out.add(new Candle(Long.parseLong(x.getString(0)),
                    Double.parseDouble(x.getString(1)), Double.parseDouble(x.getString(2)),
                    Double.parseDouble(x.getString(3)), Double.parseDouble(x.getString(4)),
                    Double.parseDouble(x.getString(5)), Double.parseDouble(x.getString(6))));
        }
        out.sort(Comparator.comparingLong(c -> c.startMs));
        long intervalMs = "60".equals(interval) ? 60*60_000L : 15*60_000L;
        long now = System.currentTimeMillis();
        out.removeIf(c -> c.startMs + intervalMs > now);
        return out;
    }

    public double feeRate(String symbol) {
        if (s.apiKey.isEmpty() || s.apiSecret.isEmpty()) return s.defaultTakerFee;
        try {
            LinkedHashMap<String,String> q = new LinkedHashMap<>(); q.put("category","linear"); q.put("symbol",symbol);
            JSONObject r = privateGet("/v5/account/fee-rate", q);
            JSONArray list = result(r).getJSONArray("list");
            if (list.length() > 0) return d(list.getJSONObject(0), "takerFeeRate");
        } catch (Exception ignored) {}
        return s.defaultTakerFee;
    }

    public double walletBalanceUsdt() throws Exception {
        LinkedHashMap<String,String> q = new LinkedHashMap<>(); q.put("accountType","UNIFIED"); q.put("coin","USDT");
        JSONObject r = privateGet("/v5/account/wallet-balance", q);
        JSONArray accounts = result(r).getJSONArray("list");
        if (accounts.length()==0) return 0;
        JSONObject a = accounts.getJSONObject(0);
        String total = a.optString("totalWalletBalance", "");
        if (!total.isEmpty()) return Double.parseDouble(total);
        JSONArray coins = a.optJSONArray("coin");
        if (coins != null) for (int i=0;i<coins.length();i++) {
            JSONObject c = coins.getJSONObject(i);
            if ("USDT".equals(c.optString("coin"))) return d(c,"walletBalance");
        }
        return 0;
    }

    public Map<String, Position> openPositions() throws Exception {
        LinkedHashMap<String,String> q = new LinkedHashMap<>();
        q.put("category","linear"); q.put("settleCoin","USDT"); q.put("limit","200");
        JSONObject r = privateGet("/v5/position/list", q);
        JSONArray list = result(r).getJSONArray("list");
        Map<String,Position> out = new HashMap<>();
        for (int i=0;i<list.length();i++) {
            JSONObject x = list.getJSONObject(i);
            double size = d(x,"size"); if (size <= 0) continue;
            Position p = new Position(x.optString("symbol"), x.optString("side"), size,
                    d(x,"avgPrice"), d(x,"markPrice"), d(x,"stopLoss"), d(x,"unrealisedPnl"),
                    x.optInt("positionIdx",0));
            out.put(positionKey(p.symbol,p.positionIdx),p);
        }
        return out;
    }

    public static String positionKey(String symbol, int positionIdx) {
        return positionIdx == 0 ? symbol : symbol + "#" + positionIdx;
    }

    public Position position(String symbol) throws Exception { return position(symbol, -1); }

    public Position position(String symbol, int positionIdx) throws Exception {
        LinkedHashMap<String,String> q = new LinkedHashMap<>(); q.put("category","linear"); q.put("symbol",symbol);
        JSONObject r = privateGet("/v5/position/list", q);
        JSONArray list = result(r).getJSONArray("list");
        for (int i=0;i<list.length();i++) {
            JSONObject x = list.getJSONObject(i); double size=d(x,"size"); if(size<=0) continue;
            int idx=x.optInt("positionIdx",0); if(positionIdx>=0 && idx!=positionIdx) continue;
            return new Position(symbol,x.optString("side"),size,d(x,"avgPrice"),d(x,"markPrice"),
                    d(x,"stopLoss"),d(x,"unrealisedPnl"),idx);
        }
        return null;
    }

    public double[] transactionSummary(String symbol, long startMs, long endMs) throws Exception {
        double gross = 0, fees = 0, funding = 0, net = 0;
        long windowStart = Math.max(0, startMs - 10_000L);
        long finalEnd = Math.max(windowStart + 1, endMs);
        while (windowStart < finalEnd) {
            long windowEnd = Math.min(finalEnd, windowStart + 7L * 24 * 60 * 60_000L - 1);
            String cursor = "";
            do {
                LinkedHashMap<String,String> q = new LinkedHashMap<>();
                q.put("accountType","UNIFIED"); q.put("category","linear"); q.put("currency","USDT");
                q.put("startTime",Long.toString(windowStart)); q.put("endTime",Long.toString(windowEnd)); q.put("limit","50");
                if (!cursor.isEmpty()) q.put("cursor",cursor);
                JSONObject r = privateGet("/v5/account/transaction-log", q);
                JSONObject rr = result(r); JSONArray list = rr.getJSONArray("list");
                for (int i=0;i<list.length();i++) {
                    JSONObject x = list.getJSONObject(i); if (!symbol.equals(x.optString("symbol"))) continue;
                    gross += d(x,"cashFlow"); fees += d(x,"fee"); funding += d(x,"funding"); net += d(x,"change");
                }
                cursor = rr.optString("nextPageCursor","");
            } while (!cursor.isEmpty());
            windowStart = windowEnd + 1;
        }
        return new double[]{gross, fees, funding, net};
    }

    public void setLeverage(String symbol, int leverage) throws Exception {
        LinkedHashMap<String,Object> b = new LinkedHashMap<>();
        b.put("category","linear"); b.put("symbol",symbol); b.put("buyLeverage",Integer.toString(leverage)); b.put("sellLeverage",Integer.toString(leverage));
        try { privatePost("/v5/position/set-leverage", b); }
        catch (ApiException e) {
            if (e.code != 110043 && !e.getMessage().toLowerCase(Locale.US).contains("not modified")) throw e;
        }
    }

    public void switchToHedgeMode(String symbol) throws Exception {
        LinkedHashMap<String,Object> b = new LinkedHashMap<>();
        b.put("category","linear"); b.put("symbol",symbol); b.put("mode",3);
        try { privatePost("/v5/position/switch-mode", b); }
        catch (ApiException e) {
            String m=e.getMessage()==null?"":e.getMessage().toLowerCase(Locale.US);
            if (e.code != 110025 && !m.contains("not modified")) throw e;
        }
    }

    public String placeEntry(String tradeId, String symbol, String side, String qty, String stopLoss) throws Exception {
        return placeEntry(tradeId,symbol,side,qty,stopLoss,0);
    }

    public String placeEntry(String tradeId, String symbol, String side, String qty, String stopLoss, int positionIdx) throws Exception {
        setLeverage(symbol, s.leverage);
        LinkedHashMap<String,Object> b = new LinkedHashMap<>();
        b.put("category","linear"); b.put("symbol",symbol); b.put("side",side); b.put("orderType","Market");
        b.put("qty",qty); b.put("positionIdx",positionIdx); b.put("reduceOnly",false); b.put("orderLinkId",shortId(tradeId));
        b.put("stopLoss",stopLoss); b.put("slTriggerBy","MarkPrice"); b.put("tpslMode","Full"); b.put("slOrderType","Market");
        JSONObject r = privatePost("/v5/order/create", b);
        return result(r).optString("orderId", "");
    }

    public String placeHedgeEntry(String tradeId, String symbol, String side, String qty, int positionIdx) throws Exception {
        LinkedHashMap<String,Object> b = new LinkedHashMap<>();
        b.put("category","linear"); b.put("symbol",symbol); b.put("side",side); b.put("orderType","Market");
        b.put("qty",qty); b.put("positionIdx",positionIdx); b.put("reduceOnly",false); b.put("orderLinkId",shortId(tradeId));
        JSONObject r = privatePost("/v5/order/create", b);
        return result(r).optString("orderId", "");
    }

    public String reducePosition(String tradeId, String symbol, String side, String qty) throws Exception {
        return reducePosition(tradeId,symbol,side,qty,0);
    }

    public String reducePosition(String tradeId, String symbol, String side, String qty, int positionIdx) throws Exception {
        LinkedHashMap<String,Object> b = new LinkedHashMap<>();
        b.put("category","linear"); b.put("symbol",symbol); b.put("side",side); b.put("orderType","Market");
        b.put("qty",qty); b.put("positionIdx",positionIdx); b.put("reduceOnly",true); b.put("orderLinkId",shortId(tradeId+"_RED"));
        JSONObject r = privatePost("/v5/order/create", b);
        return result(r).optString("orderId", "");
    }

    public void setStop(String symbol, String stopLoss) throws Exception { setStop(symbol,stopLoss,0); }

    public void setStop(String symbol, String stopLoss, int positionIdx) throws Exception {
        LinkedHashMap<String,Object> b = new LinkedHashMap<>();
        b.put("category","linear"); b.put("symbol",symbol); b.put("tpslMode","Full"); b.put("positionIdx",positionIdx);
        b.put("stopLoss",stopLoss); b.put("slTriggerBy","MarkPrice");
        privatePost("/v5/position/trading-stop", b);
    }

    public Position waitPosition(String symbol, long timeoutMs) throws Exception { return waitPosition(symbol,-1,timeoutMs); }

    public Position waitPosition(String symbol, int positionIdx, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis()+timeoutMs;
        while(System.currentTimeMillis()<end) {
            Position p=position(symbol,positionIdx); if(p!=null && p.size>0) return p;
            Thread.sleep(350);
        }
        throw new IllegalStateException("Позиция не появилась после подтверждения ордера: "+symbol+" idx="+positionIdx);
    }

    public Position waitReduced(String symbol, double before, long timeoutMs) throws Exception { return waitReduced(symbol,-1,before,timeoutMs); }

    public Position waitReduced(String symbol, int positionIdx, double before, long timeoutMs) throws Exception {
        long end=System.currentTimeMillis()+timeoutMs;
        while(System.currentTimeMillis()<end) {
            Position p=position(symbol,positionIdx); if(p==null || p.size < before) return p;
            Thread.sleep(250);
        }
        throw new IllegalStateException("Сокращение позиции не подтверждено: "+symbol+" idx="+positionIdx);
    }

    private JSONObject publicGet(String path, LinkedHashMap<String,String> params) throws Exception {
        String query = query(params);
        return request("GET", path + (query.isEmpty()?"":"?"+query), null, null);
    }

    private JSONObject privateGet(String path, LinkedHashMap<String,String> params) throws Exception {
        requireKey();
        String query=query(params); long ts=System.currentTimeMillis();
        String sign=hmac(ts+s.apiKey+RECV_WINDOW+query, s.apiSecret);
        Map<String,String> h=authHeaders(ts,sign);
        return request("GET",path+(query.isEmpty()?"":"?"+query),null,h);
    }

    private JSONObject privatePost(String path, LinkedHashMap<String,Object> body) throws Exception {
        requireKey();
        String json=json(body); long ts=System.currentTimeMillis();
        String sign=hmac(ts+s.apiKey+RECV_WINDOW+json, s.apiSecret);
        Map<String,String> h=authHeaders(ts,sign); h.put("Content-Type","application/json");
        return request("POST",path,json,h);
    }

    private Map<String,String> authHeaders(long ts,String sign){
        Map<String,String> h=new HashMap<>();
        h.put("X-BAPI-API-KEY",s.apiKey); h.put("X-BAPI-TIMESTAMP",Long.toString(ts)); h.put("X-BAPI-SIGN",sign);
        h.put("X-BAPI-RECV-WINDOW",Long.toString(RECV_WINDOW)); return h;
    }

    private JSONObject request(String method,String path,String body,Map<String,String> headers) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(base+path).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(10_000); c.setReadTimeout(15_000); c.setUseCaches(false);
        c.setRequestProperty("User-Agent","LiveResearchAndroid/0.1.1");
        if(headers!=null) for(Map.Entry<String,String> e:headers.entrySet()) c.setRequestProperty(e.getKey(),e.getValue());
        if(body!=null){ c.setDoOutput(true); try(OutputStream o=c.getOutputStream()){o.write(body.getBytes(StandardCharsets.UTF_8));}}
        int code=c.getResponseCode(); InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();
        String text=read(in); c.disconnect();
        if(text.isEmpty()) throw new IllegalStateException("Пустой HTTP ответ "+code);
        JSONObject r=new JSONObject(text); int ret=r.optInt("retCode",-1);
        if(code<200||code>=300||ret!=0) throw new ApiException(ret,r.optString("retMsg","HTTP "+code),text);
        return r;
    }

    private static JSONObject result(JSONObject r) throws Exception { return r.getJSONObject("result"); }
    private void requireKey(){if(s.apiKey==null||s.apiKey.isEmpty()||s.apiSecret==null||s.apiSecret.isEmpty())throw new IllegalStateException("API key/secret не заданы");}
    private static String read(InputStream in)throws Exception{if(in==null)return"";try(BufferedReader b=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){StringBuilder s=new StringBuilder();String x;while((x=b.readLine())!=null)s.append(x);return s.toString();}}
    private static String query(LinkedHashMap<String,String> p)throws Exception{StringBuilder b=new StringBuilder();for(Map.Entry<String,String>e:p.entrySet()){if(b.length()>0)b.append('&');b.append(URLEncoder.encode(e.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(e.getValue(),"UTF-8"));}return b.toString();}
    private static String json(LinkedHashMap<String,Object>b) throws Exception {JSONObject o=new JSONObject();for(Map.Entry<String,Object>e:b.entrySet())o.put(e.getKey(),e.getValue());return o.toString();}
    private static String hmac(String value,String secret)throws Exception{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));byte[]x=m.doFinal(value.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte z:x)b.append(String.format(Locale.US,"%02x",z&255));return b.toString();}
    private static double d(JSONObject x,String k){String v=x.optString(k,"");if(v==null||v.isEmpty())return 0;try{return Double.parseDouble(v);}catch(Exception e){return x.optDouble(k,0);}}
    private static BigDecimal bd(String x){try{return new BigDecimal(x);}catch(Exception e){return BigDecimal.ZERO;}}
    private static String shortId(String x){return x.length()<=36?x:x.substring(0,36);}
    @Override public void close(){}

    public static final class ApiException extends Exception {
        public final int code; public final String raw;
        ApiException(int code,String msg,String raw){super("Bybit "+code+": "+msg);this.code=code;this.raw=raw;}
    }
}
