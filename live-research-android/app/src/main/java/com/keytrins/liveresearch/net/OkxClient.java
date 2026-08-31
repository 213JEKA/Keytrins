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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class OkxClient implements AutoCloseable {
    private final SettingsStore.Snapshot s;
    private static final String BASE = "https://www.okx.com";

    public OkxClient(SettingsStore.Snapshot s) {
        this.s = s;
    }

    public long serverTimeMs() throws Exception {
        JSONArray data = data(publicGet("/api/v5/public/time", new LinkedHashMap<>()));
        if (data.length() == 0) return System.currentTimeMillis();
        return Long.parseLong(data.getJSONObject(0).optString("ts", Long.toString(System.currentTimeMillis())));
    }

    public String positionMode() throws Exception {
        JSONArray d = data(privateGet("/api/v5/account/config", new LinkedHashMap<>()));
        return d.length() == 0 ? "" : d.getJSONObject(0).optString("posMode", "");
    }

    public Map<String, Instrument> getInstruments() throws Exception {
        LinkedHashMap<String,String> q = new LinkedHashMap<>();
        q.put("instType", "SWAP");
        JSONArray list = data(publicGet("/api/v5/public/instruments", q));
        Map<String,Instrument> out = new HashMap<>();
        for (int i=0;i<list.length();i++) {
            JSONObject x = list.getJSONObject(i);
            String symbol = x.optString("instId", "");
            if (!symbol.endsWith("-USDT-SWAP")) continue;
            if (!"USDT".equals(x.optString("settleCcy", ""))) continue;
            if (!"linear".equalsIgnoreCase(x.optString("ctType", ""))) continue;
            if (!"live".equalsIgnoreCase(x.optString("state", ""))) continue;
            String[] p = symbol.split("-");
            if (p.length < 3) continue;
            String baseCoin = p[0];
            BigDecimal ctVal = bd(x.optString("ctVal", "0"));
            if (ctVal.signum() <= 0) continue;
            String ctValCcy = x.optString("ctValCcy", "");
            if (!ctValCcy.isEmpty() && !baseCoin.equalsIgnoreCase(ctValCcy)) continue;
            BigDecimal tick = positiveBd(x.optString("tickSz", ""), "0.00000001");
            BigDecimal lot = positiveBd(x.optString("lotSz", ""), "1");
            BigDecimal min = positiveBd(x.optString("minSz", ""), "1");
            BigDecimal max = positiveBd(x.optString("maxMktSz", ""), "999999999");
            out.put(symbol, new Instrument(symbol, baseCoin, tick, lot, min, max, BigDecimal.ZERO, ctVal));
        }
        return out;
    }

    public Map<String,Ticker> getAllTickers() throws Exception {
        LinkedHashMap<String,String> q = new LinkedHashMap<>(); q.put("instType", "SWAP");
        JSONArray list = data(publicGet("/api/v5/market/tickers", q));
        Map<String,Ticker> out = new HashMap<>();
        for (int i=0;i<list.length();i++) {
            JSONObject x = list.getJSONObject(i);
            String symbol=x.optString("instId",""); if(!symbol.endsWith("-USDT-SWAP")) continue;
            double last=d(x,"last"), bid=d(x,"bidPx"), ask=d(x,"askPx");
            double baseVol=d(x,"volCcy24h");
            double turnover=(last>0&&baseVol>0)?last*baseVol:0;
            out.put(symbol,new Ticker(symbol,last,last,bid,ask,turnover,0));
        }
        return out;
    }

    public List<Candle> getKlines(String symbol,String interval,int limit)throws Exception{
        LinkedHashMap<String,String> q=new LinkedHashMap<>();
        q.put("instId",symbol); q.put("bar","60".equals(interval)?"1H":"15m"); q.put("limit",Integer.toString(Math.min(300,Math.max(1,limit))));
        JSONArray rows=data(publicGet("/api/v5/market/candles",q));
        List<Candle> out=new ArrayList<>();
        for(int i=0;i<rows.length();i++){
            JSONArray x=rows.getJSONArray(i);
            if(x.length()>8 && !"1".equals(x.optString(8))) continue;
            long ts=Long.parseLong(x.getString(0));
            double vol=x.length()>5?Double.parseDouble(x.getString(5)):0;
            double quote=x.length()>7?Double.parseDouble(x.getString(7)):0;
            out.add(new Candle(ts,Double.parseDouble(x.getString(1)),Double.parseDouble(x.getString(2)),
                    Double.parseDouble(x.getString(3)),Double.parseDouble(x.getString(4)),vol,quote));
        }
        out.sort(Comparator.comparingLong(c->c.startMs));
        return out;
    }

    public double feeRate(String symbol){
        if(s.apiKey.isEmpty()||s.apiSecret.isEmpty()||s.apiPassphrase.isEmpty())return s.defaultTakerFee;
        try{
            String[] p=symbol.split("-");
            LinkedHashMap<String,String> q=new LinkedHashMap<>(); q.put("instType","SWAP");
            if(p.length>=2)q.put("instFamily",p[0]+"-"+p[1]);
            JSONArray a=data(privateGet("/api/v5/account/trade-fee",q));
            if(a.length()>0){
                JSONObject x=a.getJSONObject(0);
                JSONArray groups=x.optJSONArray("feeGroup");
                if(groups!=null&&groups.length()>0){double v=Math.abs(d(groups.getJSONObject(0),"taker"));if(v>0)return v;}
                double v=Math.abs(d(x,"takerU")); if(v>0)return v;
                v=Math.abs(d(x,"taker")); if(v>0)return v;
            }
        }catch(Exception ignored){}
        return s.defaultTakerFee;
    }

    public double walletBalanceUsdt()throws Exception{
        LinkedHashMap<String,String> q=new LinkedHashMap<>();q.put("ccy","USDT");
        JSONArray a=data(privateGet("/api/v5/account/balance",q));if(a.length()==0)return 0;
        JSONArray details=a.getJSONObject(0).optJSONArray("details");
        if(details!=null)for(int i=0;i<details.length();i++){
            JSONObject x=details.getJSONObject(i);if("USDT".equals(x.optString("ccy"))){double eq=d(x,"eq");return eq!=0?eq:d(x,"cashBal");}
        }
        return 0;
    }

    public Map<String,Position> openPositions()throws Exception{
        LinkedHashMap<String,String> q=new LinkedHashMap<>();q.put("instType","SWAP");
        JSONArray a=data(privateGet("/api/v5/account/positions",q));Map<String,Position> out=new HashMap<>();
        for(int i=0;i<a.length();i++){
            JSONObject x=a.getJSONObject(i);String symbol=x.optString("instId","");if(!symbol.endsWith("-USDT-SWAP"))continue;
            double signed=d(x,"pos");if(Math.abs(signed)<=0)continue;
            String posSide=x.optString("posSide","net");
            int idx="net".equals(posSide)?0:("long".equals(posSide)?1:2);
            String side=signed>0?"Buy":"Sell";
            double stop=extractStop(x);
            Position p=new Position(symbol,side,Math.abs(signed),d(x,"avgPx"),d(x,"markPx"),stop,idx);out.put(symbol,p);
        }
        return out;
    }

    public Position position(String symbol)throws Exception{
        LinkedHashMap<String,String> q=new LinkedHashMap<>();q.put("instId",symbol);
        JSONArray a=data(privateGet("/api/v5/account/positions",q));
        for(int i=0;i<a.length();i++){
            JSONObject x=a.getJSONObject(i);double signed=d(x,"pos");if(Math.abs(signed)<=0)continue;
            String posSide=x.optString("posSide","net");int idx="net".equals(posSide)?0:("long".equals(posSide)?1:2);
            return new Position(symbol,signed>0?"Buy":"Sell",Math.abs(signed),d(x,"avgPx"),d(x,"markPx"),extractStop(x),idx);
        }
        return null;
    }

    public double[] transactionSummary(String symbol,long startMs,long endMs)throws Exception{
        LinkedHashMap<String,String> q=new LinkedHashMap<>();q.put("instType","SWAP");q.put("instId",symbol);q.put("limit","100");
        JSONArray a=data(privateGet("/api/v5/trade/fills-history",q));
        double gross=0,fees=0;
        for(int i=0;i<a.length();i++){
            JSONObject x=a.getJSONObject(i);long ts=parseLong(x.optString("ts","0"));if(ts<startMs-10_000L||ts>endMs+10_000L)continue;
            gross+=d(x,"fillPnl");fees+=Math.abs(d(x,"fee"));
        }
        return new double[]{gross,fees,0,gross-fees};
    }

    public void setLeverage(String symbol,int leverage)throws Exception{
        JSONObject b=new JSONObject();b.put("instId",symbol);b.put("lever",Integer.toString(leverage));b.put("mgnMode","cross");
        privatePost("/api/v5/account/set-leverage",b);
    }

    public String placeEntry(String tradeId,String symbol,String side,String qty,String stopLoss)throws Exception{
        if(!"net_mode".equals(positionMode()))throw new IllegalStateException("OKX account должен быть в One-Way / net_mode");
        setLeverage(symbol,s.leverage);
        JSONObject b=new JSONObject();
        b.put("instId",symbol);b.put("tdMode","cross");b.put("clOrdId",orderId(tradeId));
        b.put("side","Buy".equals(side)?"buy":"sell");b.put("posSide","net");b.put("ordType","market");b.put("sz",qty);b.put("reduceOnly",false);
        JSONArray attached=new JSONArray();JSONObject sl=new JSONObject();
        sl.put("attachAlgoClOrdId",stopAlgoId(tradeId));sl.put("slTriggerPx",stopLoss);sl.put("slOrdPx","-1");sl.put("slTriggerPxType","mark");attached.put(sl);b.put("attachAlgoOrds",attached);
        JSONObject r=privatePost("/api/v5/trade/order",b);JSONArray a=data(r);if(a.length()==0)throw new IllegalStateException("OKX order: empty data");
        JSONObject x=a.getJSONObject(0);checkSubCode(x);return x.optString("ordId","");
    }

    public void setStop(String tradeId,String symbol,String stopLoss)throws Exception{
        JSONObject x=new JSONObject();x.put("instId",symbol);x.put("algoClOrdId",stopAlgoId(tradeId));
        x.put("newSlTriggerPx",stopLoss);x.put("newSlOrdPx","-1");x.put("newSlTriggerPxType","mark");
        JSONArray body=new JSONArray();body.put(x);
        JSONObject r=privatePostArray("/api/v5/trade/amend-algos",body);JSONArray a=data(r);if(a.length()>0)checkSubCode(a.getJSONObject(0));
    }

    public Position waitPosition(String symbol,long timeoutMs)throws Exception{
        long end=System.currentTimeMillis()+timeoutMs;
        while(System.currentTimeMillis()<end){Position p=position(symbol);if(p!=null&&p.size>0)return p;Thread.sleep(350);}
        throw new IllegalStateException("OKX позиция не появилась после ордера: "+symbol);
    }

    private JSONObject publicGet(String path,LinkedHashMap<String,String> params)throws Exception{
        String query=query(params);String requestPath=path+(query.isEmpty()?"":"?"+query);return request("GET",requestPath,null,null);
    }
    private JSONObject privateGet(String path,LinkedHashMap<String,String> params)throws Exception{
        requireKey();String query=query(params);String requestPath=path+(query.isEmpty()?"":"?"+query);String ts=timestamp();
        Map<String,String> h=auth(ts,"GET",requestPath,"");return request("GET",requestPath,null,h);
    }
    private JSONObject privatePost(String path,JSONObject body)throws Exception{
        requireKey();String json=body.toString();String ts=timestamp();return request("POST",path,json,auth(ts,"POST",path,json));
    }
    private JSONObject privatePostArray(String path,JSONArray body)throws Exception{
        requireKey();String json=body.toString();String ts=timestamp();return request("POST",path,json,auth(ts,"POST",path,json));
    }
    private Map<String,String> auth(String ts,String method,String path,String body)throws Exception{
        Map<String,String> h=new HashMap<>();h.put("OK-ACCESS-KEY",s.apiKey);h.put("OK-ACCESS-TIMESTAMP",ts);
        h.put("OK-ACCESS-PASSPHRASE",s.apiPassphrase);h.put("OK-ACCESS-SIGN",hmacBase64(ts+method+path+body,s.apiSecret));
        h.put("Content-Type","application/json");return h;
    }
    private JSONObject request(String method,String path,String body,Map<String,String> headers)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(BASE+path).openConnection();c.setRequestMethod(method);c.setConnectTimeout(10_000);c.setReadTimeout(15_000);c.setUseCaches(false);
        c.setRequestProperty("User-Agent","OKX-Inverse-Android/0.1");if(s.testnet)c.setRequestProperty("x-simulated-trading","1");
        if(headers!=null)for(Map.Entry<String,String>e:headers.entrySet())c.setRequestProperty(e.getKey(),e.getValue());
        if(body!=null){c.setDoOutput(true);try(OutputStream o=c.getOutputStream()){o.write(body.getBytes(StandardCharsets.UTF_8));}}
        int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();String text=read(in);c.disconnect();
        if(text.isEmpty())throw new IllegalStateException("OKX empty HTTP response "+code);
        JSONObject r=new JSONObject(text);String rc=r.optString("code","-1");if(code<200||code>=300||!"0".equals(rc))throw new ApiException(rc,r.optString("msg","HTTP "+code),text);return r;
    }

    private static JSONArray data(JSONObject r){JSONArray a=r.optJSONArray("data");return a==null?new JSONArray():a;}
    private static void checkSubCode(JSONObject x){String c=x.optString("sCode","0");if(!"0".equals(c))throw new IllegalStateException("OKX "+c+": "+x.optString("sMsg","order rejected"));}
    private static double extractStop(JSONObject x){JSONArray a=x.optJSONArray("closeOrderAlgo");if(a!=null&&a.length()>0)return d(a.optJSONObject(0),"slTriggerPx");return 0;}
    private static String timestamp(){return Instant.ofEpochMilli(System.currentTimeMillis()).toString();}
    private void requireKey(){if(s.apiKey.isEmpty()||s.apiSecret.isEmpty()||s.apiPassphrase.isEmpty())throw new IllegalStateException("OKX API key/secret/passphrase не заданы");}
    private static String orderId(String tradeId){return shortAlnum("OI"+tradeId);}
    private static String stopAlgoId(String tradeId){return shortAlnum("SL"+tradeId);}
    private static String shortAlnum(String x){String y=x.replaceAll("[^A-Za-z0-9]","");if(y.isEmpty())y="OKXI"+System.currentTimeMillis();return y.length()>32?y.substring(0,32):y;}
    private static String query(LinkedHashMap<String,String> p)throws Exception{StringBuilder b=new StringBuilder();for(Map.Entry<String,String>e:p.entrySet()){if(b.length()>0)b.append('&');b.append(enc(e.getKey())).append('=').append(enc(e.getValue()));}return b.toString();}
    private static String enc(String x)throws Exception{return URLEncoder.encode(x,"UTF-8").replace("+","%20");}
    private static String hmacBase64(String text,String secret)throws Exception{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return android.util.Base64.encodeToString(m.doFinal(text.getBytes(StandardCharsets.UTF_8)),android.util.Base64.NO_WRAP);}
    private static String read(InputStream in)throws Exception{if(in==null)return "";try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){StringBuilder b=new StringBuilder();String line;while((line=r.readLine())!=null)b.append(line);return b.toString();}}
    private static double d(JSONObject x,String k){if(x==null)return 0;try{String v=x.optString(k,"");return v.isEmpty()?0:Double.parseDouble(v);}catch(Exception e){return 0;}}
    private static BigDecimal bd(String x){try{return new BigDecimal(x);}catch(Exception e){return BigDecimal.ZERO;}}
    private static BigDecimal positiveBd(String x,String fallback){BigDecimal v=bd(x);return v.signum()>0?v:new BigDecimal(fallback);}
    private static long parseLong(String x){try{return Long.parseLong(x);}catch(Exception e){return 0;}}
    @Override public void close(){}

    public static final class ApiException extends Exception{
        public final String code;public final String raw;
        public ApiException(String code,String message,String raw){super(message);this.code=code;this.raw=raw;}
    }
}
