package com.keytrins.liveresearch.net;

import android.util.Base64;

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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class KucoinClient implements AutoCloseable {
    private static final String BASE = "https://api-futures.kucoin.com";
    private final SettingsStore.Snapshot s;
    private final Map<String,String> instBySymbol = new HashMap<>();
    private final Map<String,Double> turnoverBySymbol = new HashMap<>();
    private final Map<String,Double> markBySymbol = new HashMap<>();
    private final Map<String,Double> takerBySymbol = new HashMap<>();

    public KucoinClient(SettingsStore.Snapshot s) { this.s = s; }

    public long serverTimeMs() throws Exception {
        Object d = data(publicGet("/api/v1/timestamp", new LinkedHashMap<>()));
        if (d instanceof Number) return ((Number)d).longValue();
        return Long.parseLong(String.valueOf(d));
    }

    public boolean isOneWayMode() throws Exception {
        JSONObject d = asObject(data(privateGet("/api/v2/position/getPositionMode", new LinkedHashMap<>())));
        return d.optInt("positionMode", 1) == 0;
    }

    public Map<String,Instrument> getInstruments() throws Exception {
        JSONArray a = asArray(data(publicGet("/api/v1/contracts/active", new LinkedHashMap<>())));
        Map<String,Instrument> out = new HashMap<>();
        instBySymbol.clear(); turnoverBySymbol.clear(); markBySymbol.clear(); takerBySymbol.clear();
        long minLaunch = System.currentTimeMillis() - Math.max(0, s.minAgeDays) * 24L * 60L * 60_000L;
        for (int i=0;i<a.length();i++) {
            JSONObject x = a.getJSONObject(i);
            String instId=x.optString("symbol"), base=x.optString("baseCurrency"), quote=x.optString("quoteCurrency"), settle=x.optString("settleCurrency");
            boolean inverse=x.optBoolean("isInverse", false), cross=x.optBoolean("supportCross", true);
            String stage=x.optString("marketStage", "NORMAL");
            if (instId.isEmpty() || !"USDT".equalsIgnoreCase(quote) || !"USDT".equalsIgnoreCase(settle) || inverse || !cross || !("NORMAL".equalsIgnoreCase(stage) || stage.isEmpty())) continue;
            long launch=x.optLong("firstOpenDate",0); if (launch>0 && launch>minLaunch) continue;
            String symbol=normalize(instId);
            BigDecimal tick=bd(x.opt("tickSize"), "0.00000001");
            BigDecimal lot=bd(x.opt("lotSize"), "1"); if(lot.signum()<=0)lot=BigDecimal.ONE;
            BigDecimal max=bd(x.opt("marketMaxOrderQty"), "999999999"); if(max.signum()<=0)max=new BigDecimal("999999999");
            BigDecimal mult=bd(x.opt("multiplier"), "1"); if(mult.signum()<=0)continue;
            Instrument inst=new Instrument(symbol,instId,base,"linear",launch,tick,lot,lot,max,BigDecimal.ZERO,mult,base);
            double last=d(x,"lastTradePrice"), turnover=d(x,"turnoverOf24h");
            if(!(turnover>0)){double vol=d(x,"volumeOf24h");turnover=vol*mult.doubleValue()*(last>0?last:1);}
            inst.turnover24h=turnover;
            out.put(symbol,inst); instBySymbol.put(symbol,instId); turnoverBySymbol.put(symbol,turnover); markBySymbol.put(symbol,d(x,"markPrice"));
            double fee=Math.abs(d(x,"takerFeeRate")); if(fee>0)takerBySymbol.put(symbol,fee);
        }
        return out;
    }

    public Map<String,Ticker> getAllTickers() throws Exception {
        if(instBySymbol.isEmpty()) getInstruments();
        JSONArray a=asArray(data(publicGet("/api/v1/allTickers",new LinkedHashMap<>())));
        Map<String,Ticker> out=new HashMap<>();
        for(int i=0;i<a.length();i++){
            JSONObject x=a.getJSONObject(i);String instId=x.optString("symbol"),sym=normalize(instId);if(!instBySymbol.containsKey(sym))continue;
            double last=d(x,"price"),bid=d(x,"bestBidPrice"),ask=d(x,"bestAskPrice"),mark=markBySymbol.getOrDefault(sym,last),turn=turnoverBySymbol.getOrDefault(sym,0.0);
            out.put(sym,new Ticker(sym,last,mark,bid,ask,turn,0));
        }
        return out;
    }

    public List<Candle> getKlines(String symbol,String interval,int limit)throws Exception{
        int gran="60".equals(interval)?3600:900;
        int want=Math.min(500,Math.max(1,limit));long now=System.currentTimeMillis(),from=now-(long)(want+8)*gran*1000L;
        LinkedHashMap<String,String>q=new LinkedHashMap<>();q.put("symbol",toInstId(symbol));q.put("granularity",Integer.toString(gran));q.put("from",Long.toString(from));q.put("to",Long.toString(now));
        JSONArray a=asArray(data(publicGet("/api/v1/kline/query",q)));List<Candle>out=new ArrayList<>();
        for(int i=0;i<a.length();i++){JSONArray x=a.getJSONArray(i);if(x.length()<7)continue;long ts=asLong(x.opt(0));if(ts<10_000_000_000L)ts*=1000L;out.add(new Candle(ts,asDouble(x.opt(1)),asDouble(x.opt(2)),asDouble(x.opt(3)),asDouble(x.opt(4)),asDouble(x.opt(5)),asDouble(x.opt(6))));}
        out.sort(Comparator.comparingLong(c->c.startMs));if(out.size()>want)return new ArrayList<>(out.subList(out.size()-want,out.size()));return out;
    }

    public double walletBalanceUsdt()throws Exception{
        LinkedHashMap<String,String>q=new LinkedHashMap<>();q.put("currency","USDT");JSONObject d=asObject(data(privateGet("/api/v1/account-overview",q)));
        double eq=num(d,"accountEquity");if(eq>0)return eq;double bal=num(d,"marginBalance");return bal>0?bal:num(d,"availableBalance");
    }

    public double feeRate(String symbol){Double f=takerBySymbol.get(symbol);if(f!=null&&f>0)return f;try{getInstruments();f=takerBySymbol.get(symbol);if(f!=null&&f>0)return f;}catch(Exception ignored){}return s.defaultTakerFee;}

    public Map<String,Position> openPositions()throws Exception{
        LinkedHashMap<String,String>q=new LinkedHashMap<>();q.put("currency","USDT");JSONArray a=asArray(data(privateGet("/api/v1/positions",q)));Map<String,Position>out=new HashMap<>();
        for(int i=0;i<a.length();i++){Position p=parsePosition(a.getJSONObject(i));if(p!=null)out.put(p.symbol,p);}return out;
    }

    public Position position(String symbol)throws Exception{
        LinkedHashMap<String,String>q=new LinkedHashMap<>();q.put("symbol",toInstId(symbol));Object raw=data(privateGet("/api/v2/position",q));JSONArray a=asArray(raw);for(int i=0;i<a.length();i++){Position p=parsePosition(a.getJSONObject(i));if(p!=null)return p;}return null;
    }

    private Position parsePosition(JSONObject x){
        double qty=num(x,"currentQty");if(Math.abs(qty)<1e-12)qty=num(x,"size");if(Math.abs(qty)<1e-12||!x.optBoolean("isOpen",true))return null;
        String side=qty>0?"Buy":"Sell";String ps=x.optString("positionSide","");if(qty==0){if("LONG".equalsIgnoreCase(ps))side="Buy";else if("SHORT".equalsIgnoreCase(ps))side="Sell";}
        double entry=num(x,"avgEntryPrice");if(!(entry>0))entry=num(x,"entryPrice");double mark=num(x,"markPrice"),upl=num(x,"unrealisedPnl");if(upl==0)upl=num(x,"unrealizedPnL");
        return new Position(normalize(x.optString("symbol")),side,Math.abs(qty),entry,mark,0,upl,0);
    }

    private void ensureCrossAndLeverage(String symbol)throws Exception{
        String inst=toInstId(symbol);LinkedHashMap<String,String>q=new LinkedHashMap<>();q.put("symbol",inst);
        try{JSONObject d=asObject(data(privateGet("/api/v2/position/getMarginMode",q)));if(!"CROSS".equalsIgnoreCase(d.optString("marginMode"))){LinkedHashMap<String,Object>b=new LinkedHashMap<>();b.put("symbol",inst);b.put("marginMode","CROSS");privatePost("/api/v2/position/changeMarginMode",b);}}catch(Exception e){throw new IllegalStateException("KuCoin не удалось включить CROSS для "+symbol+": "+e.getMessage(),e);}
        LinkedHashMap<String,Object>b=new LinkedHashMap<>();b.put("symbol",inst);b.put("leverage",Integer.toString(s.leverage));privatePost("/api/v2/changeCrossUserLeverage",b);
    }

    public OrderResult placeEntry(String tradeId,String symbol,String side,String contracts,String stopLoss)throws Exception{
        if(!isOneWayMode())throw new IllegalStateException("KuCoin account должен быть One-Way mode");ensureCrossAndLeverage(symbol);
        String inst=toInstId(symbol),client=safeId(tradeId);LinkedHashMap<String,Object>b=new LinkedHashMap<>();b.put("clientOid",client);b.put("symbol",inst);b.put("marginMode","CROSS");b.put("leverage",s.leverage);b.put("positionSide","BOTH");b.put("side","Buy".equals(side)?"buy":"sell");b.put("type","market");b.put("size",sizeValue(contracts));b.put("reduceOnly",false);
        JSONObject d=asObject(data(privatePost("/api/v1/orders",b)));String entryId=d.optString("orderId");
        Position p=waitPosition(symbol,20_000L);String stopId="";
        try{stopId=placeStop(symbol,side,p.size,stopLoss);}catch(Exception e){try{reducePosition("SAFE"+System.currentTimeMillis(),symbol,"Buy".equals(side)?"Sell":"Buy",fmtQty(p.size));}catch(Exception ignored){}throw new IllegalStateException("KuCoin initial exchange-side SL не установлен; позиция аварийно закрывается: "+e.getMessage(),e);}
        return new OrderResult(entryId,stopId);
    }

    private String placeStop(String symbol,String positionSide,double qty,String stopLoss)throws Exception{
        String closing="Buy".equals(positionSide)?"sell":"buy",trigger="Buy".equals(positionSide)?"down":"up";LinkedHashMap<String,Object>b=new LinkedHashMap<>();b.put("clientOid",safeId("SL"+System.currentTimeMillis()+UUID.randomUUID().toString().substring(0,5)));b.put("symbol",toInstId(symbol));b.put("marginMode","CROSS");b.put("leverage",s.leverage);b.put("positionSide","BOTH");b.put("side",closing);b.put("type","market");b.put("size",sizeValue(fmtQty(qty)));b.put("reduceOnly",true);b.put("closeOrder",false);b.put("stop",trigger);b.put("stopPriceType","MP");b.put("stopPrice",stopLoss);
        JSONObject d=asObject(data(privatePost("/api/v1/orders",b)));String id=d.optString("orderId");if(id.isEmpty())throw new IllegalStateException("KuCoin stop orderId пуст");StopInfo si=stopInfo(symbol,id);if(!(si.stopPrice>0))throw new IllegalStateException("KuCoin stop не подтверждён биржей");return id;
    }

    public String replaceStop(String symbol,String oldStopId,String positionSide,double qty,String stopLoss)throws Exception{
        String newId=placeStop(symbol,positionSide,qty,stopLoss);if(oldStopId!=null&&!oldStopId.isEmpty()){try{privateDelete("/api/v1/orders/"+oldStopId,new LinkedHashMap<>());}catch(Exception ignored){}}return newId;
    }

    public StopInfo stopInfo(String symbol,String orderId)throws Exception{
        if(orderId==null||orderId.isEmpty())return new StopInfo("",0);JSONObject d=asObject(data(privateGet("/api/v1/orders/"+orderId,new LinkedHashMap<>())));return new StopInfo(d.optString("id",orderId),num(d,"stopPrice"));
    }

    public StopInfo findActiveStop(String symbol,String positionSide)throws Exception{
        LinkedHashMap<String,String>q=new LinkedHashMap<>();q.put("symbol",toInstId(symbol));q.put("pageSize","50");JSONObject page=asObject(data(privateGet("/api/v1/stopOrders",q)));JSONArray items=page.optJSONArray("items");if(items==null)return new StopInfo("",0);String closing="Buy".equals(positionSide)?"sell":"buy";for(int i=0;i<items.length();i++){JSONObject x=items.getJSONObject(i);if(!closing.equalsIgnoreCase(x.optString("side")))continue;if(!x.optBoolean("reduceOnly",false)&&!x.optBoolean("closeOrder",false))continue;double px=num(x,"stopPrice");if(px>0)return new StopInfo(x.optString("id"),px);}return new StopInfo("",0);
    }

    public String reducePosition(String id,String symbol,String closeSide,String contracts)throws Exception{
        LinkedHashMap<String,Object>b=new LinkedHashMap<>();b.put("clientOid",safeId(id));b.put("symbol",toInstId(symbol));b.put("marginMode","CROSS");b.put("leverage",s.leverage);b.put("positionSide","BOTH");b.put("side","Buy".equals(closeSide)?"buy":"sell");b.put("type","market");b.put("size",sizeValue(contracts));b.put("reduceOnly",true);JSONObject d=asObject(data(privatePost("/api/v1/orders",b)));return d.optString("orderId");
    }

    public Position waitPosition(String symbol,long timeoutMs)throws Exception{long end=System.currentTimeMillis()+timeoutMs;while(System.currentTimeMillis()<end){Position p=position(symbol);if(p!=null&&p.size>0)return p;Thread.sleep(300);}throw new IllegalStateException("KuCoin позиция не появилась: "+symbol);}
    public Position waitReduced(String symbol,double before,long timeoutMs)throws Exception{long end=System.currentTimeMillis()+timeoutMs;while(System.currentTimeMillis()<end){Position p=position(symbol);if(p==null||p.size+1e-12<before)return p;Thread.sleep(250);}throw new IllegalStateException("KuCoin сокращение не подтверждено: "+symbol);}

    public double[] transactionSummary(String symbol,long startMs,long endMs)throws Exception{
        LinkedHashMap<String,String>q=new LinkedHashMap<>();q.put("symbol",toInstId(symbol));q.put("from",Long.toString(startMs));q.put("to",Long.toString(endMs));q.put("limit","50");JSONObject page=asObject(data(privateGet("/api/v1/history-positions",q)));JSONArray items=page.optJSONArray("items");if(items==null||items.length()==0)return new double[]{0,0,0,0};JSONObject best=null;long bestClose=0;for(int i=0;i<items.length();i++){JSONObject x=items.getJSONObject(i);long ct=x.optLong("closeTime",0);if(ct>=startMs-60_000L&&ct<=endMs+60_000L&&ct>=bestClose){best=x;bestClose=ct;}}if(best==null)best=items.getJSONObject(0);double net=num(best,"pnl"),fee=-Math.abs(num(best,"tradeFee")),funding=num(best,"fundingFee"),gross=net-fee-funding;return new double[]{gross,fee,funding,net};
    }

    private JSONObject publicGet(String path,LinkedHashMap<String,String>params)throws Exception{String q=query(params);return request("GET",path+(q.isEmpty()?"":"?"+q),null,null);}
    private JSONObject privateGet(String path,LinkedHashMap<String,String>params)throws Exception{requireKey();String q=query(params),ep=path+(q.isEmpty()?"":"?"+q);return request("GET",ep,null,auth("GET",ep,""));}
    private JSONObject privatePost(String path,LinkedHashMap<String,Object>body)throws Exception{requireKey();JSONObject o=new JSONObject();for(Map.Entry<String,Object>e:body.entrySet())o.put(e.getKey(),e.getValue());String raw=o.toString();return request("POST",path,raw,auth("POST",path,raw));}
    private JSONObject privateDelete(String path,LinkedHashMap<String,String>params)throws Exception{requireKey();String q=query(params),ep=path+(q.isEmpty()?"":"?"+q);return request("DELETE",ep,null,auth("DELETE",ep,""));}

    private Map<String,String> auth(String method,String endpoint,String body)throws Exception{String ts=Long.toString(System.currentTimeMillis());Map<String,String>h=new HashMap<>();h.put("KC-API-KEY",s.apiKey);h.put("KC-API-SIGN",hmacB64(ts+method+endpoint+body));h.put("KC-API-TIMESTAMP",ts);h.put("KC-API-PASSPHRASE",hmacB64(s.apiPassphrase));h.put("KC-API-KEY-VERSION",empty(s.apiKeyVersion)?"3":s.apiKeyVersion.trim());h.put("Content-Type","application/json");return h;}
    private String hmacB64(String v)throws Exception{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(s.apiSecret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.encodeToString(m.doFinal(v.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);}

    private JSONObject request(String method,String endpoint,String body,Map<String,String>headers)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(BASE+endpoint).openConnection();c.setRequestMethod(method);c.setConnectTimeout(10_000);c.setReadTimeout(15_000);c.setUseCaches(false);c.setRequestProperty("User-Agent","KuCoin-Inverse-Android/0.1.1");if(headers!=null)for(Map.Entry<String,String>e:headers.entrySet())c.setRequestProperty(e.getKey(),e.getValue());if(body!=null){c.setDoOutput(true);try(OutputStream o=c.getOutputStream()){o.write(body.getBytes(StandardCharsets.UTF_8));}}int http=c.getResponseCode();InputStream in=http>=200&&http<300?c.getInputStream():c.getErrorStream();String text=read(in);c.disconnect();if(text.isEmpty())throw new IllegalStateException("KuCoin пустой HTTP ответ "+http);JSONObject r=new JSONObject(text);String code=r.optString("code",Integer.toString(http));if(http<200||http>=300||!"200000".equals(code))throw new ApiException(code,r.optString("msg","HTTP "+http),text);return r;}

    private String toInstId(String symbol){String x=instBySymbol.get(symbol);if(x!=null)return x;if(symbol.endsWith("USDT"))return symbol+"M";return symbol;}
    private static String normalize(String inst){return inst!=null&&inst.endsWith("M")?inst.substring(0,inst.length()-1):inst;}
    private static String safeId(String x){String s=x==null?"":x.replaceAll("[^A-Za-z0-9_-]","");if(s.length()>40)s=s.substring(0,40);return s.isEmpty()?UUID.randomUUID().toString().replace("-","").substring(0,24):s;}
    private static Object sizeValue(String x){try{double v=Double.parseDouble(x);long r=Math.round(v);if(Math.abs(v-r)<1e-9)return r;return v;}catch(Exception e){return x;}}
    private static String fmtQty(double q){if(Math.abs(q-Math.rint(q))<1e-9)return Long.toString(Math.round(q));return String.format(Locale.US,"%.12f",q).replaceAll("0+$","").replaceAll("\\.$","");}
    private static String query(LinkedHashMap<String,String>p){StringBuilder b=new StringBuilder();for(Map.Entry<String,String>e:p.entrySet()){if(e.getValue()==null||e.getValue().isEmpty())continue;if(b.length()>0)b.append('&');b.append(e.getKey()).append('=').append(e.getValue());}return b.toString();}
    private static Object data(JSONObject r){return r.opt("data");}
    private static JSONArray asArray(Object o){if(o instanceof JSONArray)return (JSONArray)o;JSONArray a=new JSONArray();if(o instanceof JSONObject)a.put(o);return a;}
    private static JSONObject asObject(Object o){return o instanceof JSONObject?(JSONObject)o:new JSONObject();}
    private static BigDecimal bd(Object o,String def){try{return new BigDecimal(String.valueOf(o));}catch(Exception e){return new BigDecimal(def);}}
    private static double d(JSONObject o,String k){return asDouble(o.opt(k));}private static double num(JSONObject o,String k){return asDouble(o.opt(k));}
    private static double asDouble(Object o){try{return o==null||o==JSONObject.NULL?0:Double.parseDouble(String.valueOf(o));}catch(Exception e){return 0;}}
    private static long asLong(Object o){try{return o==null||o==JSONObject.NULL?0:Long.parseLong(String.valueOf(o));}catch(Exception e){return 0;}}
    private static boolean empty(String x){return x==null||x.trim().isEmpty();}
    private static String read(InputStream in)throws Exception{if(in==null)return "";try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){StringBuilder b=new StringBuilder();String line;while((line=r.readLine())!=null)b.append(line);return b.toString();}}
    private void requireKey(){if(empty(s.apiKey)||empty(s.apiSecret)||empty(s.apiPassphrase))throw new IllegalStateException("KuCoin API key/secret/passphrase не заданы");}
    @Override public void close(){}

    public static final class OrderResult{public final String orderId,stopAlgoId;public OrderResult(String orderId,String stopAlgoId){this.orderId=orderId;this.stopAlgoId=stopAlgoId;}}
    public static final class StopInfo{public final String algoId;public final double stopPrice;public StopInfo(String algoId,double stopPrice){this.algoId=algoId;this.stopPrice=stopPrice;}}
    public static final class ApiException extends Exception{public final String code,raw;public ApiException(String code,String msg,String raw){super("KuCoin "+code+": "+msg);this.code=code;this.raw=raw;}}
}
