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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class OkxClient implements AutoCloseable {
    private static final String BASE="https://www.okx.com";
    private final SettingsStore.Snapshot s;
    public OkxClient(SettingsStore.Snapshot s){this.s=s;}

    public long serverTimeMs() throws Exception {
        JSONArray a=data(publicGet("/api/v5/public/time",new LinkedHashMap<>()));
        return a.length()>0?Long.parseLong(a.getJSONObject(0).optString("ts",Long.toString(System.currentTimeMillis()))):System.currentTimeMillis();
    }

    public boolean isNetMode() throws Exception {
        JSONArray a=data(privateGet("/api/v5/account/config",new LinkedHashMap<>()));
        return a.length()>0&&"net_mode".equals(a.getJSONObject(0).optString("posMode"));
    }

    public Map<String,Instrument> getInstruments() throws Exception {
        LinkedHashMap<String,String> q=new LinkedHashMap<>();q.put("instType","SWAP");
        JSONArray a=data(publicGet("/api/v5/public/instruments",q));Map<String,Instrument> out=new HashMap<>();
        long minLaunch=System.currentTimeMillis()-Math.max(0,s.minAgeDays)*24L*60L*60_000L;
        for(int i=0;i<a.length();i++){
            JSONObject x=a.getJSONObject(i);String instId=x.optString("instId"),settle=x.optString("settleCcy"),ctType=x.optString("ctType"),state=x.optString("state"),base=x.optString("ctValCcy");
            if(!"USDT".equals(settle)||!"linear".equalsIgnoreCase(ctType)||!"live".equalsIgnoreCase(state)||!instId.endsWith("-USDT-SWAP"))continue;
            String normalized=normalize(instId);String baseCoin=normalized.substring(0,normalized.length()-4);long listTime=parseLong(x.optString("listTime","0"));if(listTime>0&&listTime>minLaunch)continue;
            BigDecimal tick=bd(x.optString("tickSz","0.00000001")),lot=bd(x.optString("lotSz","1")),min=bd(x.optString("minSz","1")),max=bd(x.optString("maxMktSz","999999999")),ctVal=bd(x.optString("ctVal","1"));
            if(max.signum()<=0)max=new BigDecimal("999999999");
            String ctValCcy=x.optString("ctValCcy",baseCoin); if(base.isEmpty())base=baseCoin;
            out.put(normalized,new Instrument(normalized,instId,baseCoin,ctType,listTime,tick,lot,min,max,BigDecimal.ZERO,ctVal,ctValCcy));
        }return out;
    }

    public Map<String,Ticker> getAllTickers() throws Exception {
        LinkedHashMap<String,String> q=new LinkedHashMap<>();q.put("instType","SWAP");JSONArray a=data(publicGet("/api/v5/market/tickers",q));Map<String,Ticker> out=new HashMap<>();
        for(int i=0;i<a.length();i++){
            JSONObject x=a.getJSONObject(i);String instId=x.optString("instId");if(!instId.endsWith("-USDT-SWAP"))continue;String sym=normalize(instId);
            double last=d(x,"last"),bid=d(x,"bidPx"),ask=d(x,"askPx"),mark=last;double turnover=d(x,"volCcyQuote24h");if(!(turnover>0)){double v=d(x,"volCcy24h");turnover=v*(last>0?last:1);}
            out.put(sym,new Ticker(sym,last,mark,bid,ask,turnover,0));
        }return out;
    }

    public List<Candle> getKlines(String symbol,String interval,int limit) throws Exception {
        LinkedHashMap<String,String> q=new LinkedHashMap<>();q.put("instId",toInstId(symbol));q.put("bar","60".equals(interval)?"1H":"15m");q.put("limit",Integer.toString(Math.min(300,Math.max(1,limit))));
        JSONArray a=data(publicGet("/api/v5/market/candles",q));List<Candle> out=new ArrayList<>();
        for(int i=0;i<a.length();i++){JSONArray x=a.getJSONArray(i);if(x.length()>8&&"0".equals(x.optString(8)))continue;out.add(new Candle(Long.parseLong(x.getString(0)),Double.parseDouble(x.getString(1)),Double.parseDouble(x.getString(2)),Double.parseDouble(x.getString(3)),Double.parseDouble(x.getString(4)),Double.parseDouble(x.getString(5)),x.length()>7?Double.parseDouble(x.getString(7)):0));}
        out.sort(Comparator.comparingLong(c->c.startMs));return out;
    }

    public double walletBalanceUsdt() throws Exception {
        LinkedHashMap<String,String> q=new LinkedHashMap<>();q.put("ccy","USDT");JSONArray a=data(privateGet("/api/v5/account/balance",q));if(a.length()==0)return 0;JSONObject root=a.getJSONObject(0);double total=d(root,"totalEq");
        JSONArray details=root.optJSONArray("details");if(details!=null)for(int i=0;i<details.length();i++){JSONObject c=details.getJSONObject(i);if("USDT".equals(c.optString("ccy"))){double eq=d(c,"eq");if(eq>0)return eq;double cash=d(c,"cashBal");if(cash>0)return cash;}}
        return total;
    }

    public double feeRate(String symbol){
        if(empty(s.apiKey)||empty(s.apiSecret)||empty(s.apiPassphrase))return s.defaultTakerFee;
        try{LinkedHashMap<String,String> q=new LinkedHashMap<>();q.put("instType","SWAP");q.put("instId",toInstId(symbol));JSONArray a=data(privateGet("/api/v5/account/trade-fee",q));if(a.length()>0){double t=Math.abs(d(a.getJSONObject(0),"taker"));if(t>0)return t;}}catch(Exception ignored){}return s.defaultTakerFee;
    }

    public Map<String,Position> openPositions() throws Exception {
        LinkedHashMap<String,String> q=new LinkedHashMap<>();q.put("instType","SWAP");JSONArray a=data(privateGet("/api/v5/account/positions",q));Map<String,Position> out=new HashMap<>();
        for(int i=0;i<a.length();i++){Position p=parsePosition(a.getJSONObject(i));if(p!=null)out.put(p.symbol,p);}return out;
    }

    public Position position(String symbol) throws Exception {
        LinkedHashMap<String,String> q=new LinkedHashMap<>();q.put("instId",toInstId(symbol));JSONArray a=data(privateGet("/api/v5/account/positions",q));for(int i=0;i<a.length();i++){Position p=parsePosition(a.getJSONObject(i));if(p!=null)return p;}return null;
    }

    private Position parsePosition(JSONObject x){
        double raw=d(x,"pos");if(Math.abs(raw)<=0)return null;String posSide=x.optString("posSide","net"),side;if("long".equals(posSide))side="Buy";else if("short".equals(posSide))side="Sell";else side=raw>0?"Buy":"Sell";
        return new Position(normalize(x.optString("instId")),side,Math.abs(raw),d(x,"avgPx"),d(x,"markPx"),0,d(x,"upl"),0);
    }

    public void setLeverage(String symbol,int leverage) throws Exception {
        LinkedHashMap<String,Object> b=new LinkedHashMap<>();b.put("instId",toInstId(symbol));b.put("lever",Integer.toString(leverage));b.put("mgnMode","cross");privatePost("/api/v5/account/set-leverage",b);
    }

    public OrderResult placeEntry(String tradeId,String symbol,String side,String contracts,String stopLoss) throws Exception {
        setLeverage(symbol,s.leverage);LinkedHashMap<String,Object>b=new LinkedHashMap<>();b.put("instId",toInstId(symbol));b.put("tdMode","cross");b.put("side","Buy".equals(side)?"buy":"sell");b.put("ordType","market");b.put("sz",contracts);b.put("clOrdId",safeId(tradeId));
        JSONArray attach=new JSONArray();JSONObject sl=new JSONObject();sl.put("attachAlgoClOrdId",safeId("SL"+tradeId));sl.put("slTriggerPx",stopLoss);sl.put("slTriggerPxType","mark");sl.put("slOrdPx","-1");attach.put(sl);b.put("attachAlgoOrds",attach);
        JSONArray a=data(privatePost("/api/v5/trade/order",b));checkItem(a);String orderId=a.length()>0?a.getJSONObject(0).optString("ordId",""):"";return new OrderResult(orderId,"");
    }

    public String reducePosition(String id,String symbol,String closeSide,String contracts) throws Exception {
        LinkedHashMap<String,Object>b=new LinkedHashMap<>();b.put("instId",toInstId(symbol));b.put("tdMode","cross");b.put("side","Buy".equals(closeSide)?"buy":"sell");b.put("ordType","market");b.put("sz",contracts);b.put("reduceOnly",true);b.put("clOrdId",safeId(id));JSONArray a=data(privatePost("/api/v5/trade/order",b));checkItem(a);return a.length()>0?a.getJSONObject(0).optString("ordId",""):"";
    }

    public StopInfo stopInfo(String symbol,String orderId) throws Exception {
        if(empty(orderId))return new StopInfo("",0);LinkedHashMap<String,String>q=new LinkedHashMap<>();q.put("instId",toInstId(symbol));q.put("ordId",orderId);JSONArray a=data(privateGet("/api/v5/trade/order",q));if(a.length()==0)return new StopInfo("",0);JSONObject o=a.getJSONObject(0);JSONArray aa=o.optJSONArray("attachAlgoOrds");
        if(aa!=null)for(int i=0;i<aa.length();i++){JSONObject z=aa.getJSONObject(i);String id=z.optString("attachAlgoId",z.optString("algoId",""));double px=d(z,"slTriggerPx");if(!empty(id)||px>0)return new StopInfo(id,px);}return new StopInfo("",0);
    }

    public void amendStop(String symbol,String algoId,String stopLoss) throws Exception {
        if(empty(algoId))throw new IllegalStateException("OKX stop algo id ещё не получен");JSONObject o=new JSONObject();o.put("instId",toInstId(symbol));o.put("algoId",algoId);o.put("newSlTriggerPx",stopLoss);o.put("newSlTriggerPxType","mark");o.put("newSlOrdPx","-1");JSONArray body=new JSONArray();body.put(o);JSONArray a=data(privatePostArray("/api/v5/trade/amend-algos",body));checkItem(a);
    }

    public Position waitPosition(String symbol,long timeoutMs)throws Exception{long end=System.currentTimeMillis()+timeoutMs;while(System.currentTimeMillis()<end){Position p=position(symbol);if(p!=null&&p.size>0)return p;Thread.sleep(300);}throw new IllegalStateException("OKX позиция не появилась: "+symbol);}
    public Position waitReduced(String symbol,double before,long timeoutMs)throws Exception{long end=System.currentTimeMillis()+timeoutMs;while(System.currentTimeMillis()<end){Position p=position(symbol);if(p==null||p.size+1e-12<before)return p;Thread.sleep(250);}throw new IllegalStateException("OKX сокращение не подтверждено: "+symbol);}

    public double[] transactionSummary(String symbol,long startMs,long endMs)throws Exception{
        LinkedHashMap<String,String>q=new LinkedHashMap<>();q.put("instType","SWAP");q.put("instId",toInstId(symbol));q.put("limit","100");JSONArray a=data(privateGet("/api/v5/trade/fills-history",q));double gross=0,fees=0;
        for(int i=0;i<a.length();i++){JSONObject x=a.getJSONObject(i);long ts=parseLong(x.optString("fillTime",x.optString("ts","0")));if(ts<startMs-10_000L||ts>endMs+10_000L)continue;gross+=d(x,"fillPnl");fees+=d(x,"fee");}return new double[]{gross,fees,0,gross+fees};
    }

    private JSONObject publicGet(String path,LinkedHashMap<String,String>params)throws Exception{String q=query(params);return request("GET",path+(q.isEmpty()?"":"?"+q),null,null);}
    private JSONObject privateGet(String path,LinkedHashMap<String,String>params)throws Exception{requireKey();String q=query(params),full=path+(q.isEmpty()?"":"?"+q),ts=isoNow();Map<String,String>h=auth(ts,"GET",full,"");return request("GET",full,null,h);}
    private JSONObject privatePost(String path,LinkedHashMap<String,Object>body)throws Exception{JSONObject o=new JSONObject();for(Map.Entry<String,Object>e:body.entrySet())o.put(e.getKey(),e.getValue());return privatePostRaw(path,o.toString());}
    private JSONObject privatePostArray(String path,JSONArray body)throws Exception{return privatePostRaw(path,body.toString());}
    private JSONObject privatePostRaw(String path,String body)throws Exception{requireKey();String ts=isoNow();return request("POST",path,body,auth(ts,"POST",path,body));}

    private Map<String,String> auth(String ts,String method,String requestPath,String body)throws Exception{Map<String,String>h=new HashMap<>();h.put("OK-ACCESS-KEY",s.apiKey);h.put("OK-ACCESS-SIGN",sign(ts+method+requestPath+body));h.put("OK-ACCESS-TIMESTAMP",ts);h.put("OK-ACCESS-PASSPHRASE",s.apiPassphrase);h.put("Content-Type","application/json");if(s.testnet)h.put("x-simulated-trading","1");return h;}
    private String sign(String v)throws Exception{Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(s.apiSecret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.encodeToString(m.doFinal(v.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);}
    private JSONObject request(String method,String path,String body,Map<String,String>headers)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(BASE+path).openConnection();c.setRequestMethod(method);c.setConnectTimeout(10_000);c.setReadTimeout(15_000);c.setUseCaches(false);c.setRequestProperty("User-Agent","OKX-Inverse-Android/0.1.1");if(headers!=null)for(Map.Entry<String,String>e:headers.entrySet())c.setRequestProperty(e.getKey(),e.getValue());if(body!=null){c.setDoOutput(true);try(OutputStream o=c.getOutputStream()){o.write(body.getBytes(StandardCharsets.UTF_8));}}int http=c.getResponseCode();InputStream in=http>=200&&http<300?c.getInputStream():c.getErrorStream();String text=read(in);c.disconnect();if(text.isEmpty())throw new IllegalStateException("OKX пустой HTTP ответ "+http);JSONObject r=new JSONObject(text);String code=r.optString("code",Integer.toString(http));if(http<200||http>=300||!"0".equals(code))throw new ApiException(code,r.optString("msg","HTTP "+http),text);return r;}
    private static JSONArray data(JSONObject r)throws Exception{return r.getJSONArray("data");}
    private static void checkItem(JSONArray a)throws Exception{if(a.length()==0)throw new IllegalStateException("OKX пустой data");JSONObject x=a.getJSONObject(0);String sc=x.optString("sCode","0");if(!"0".equals(sc))throw new ApiException(sc,x.optString("sMsg","order rejected"),x.toString());}
    private void requireKey(){if(empty(s.apiKey)||empty(s.apiSecret)||empty(s.apiPassphrase))throw new IllegalStateException("OKX API key/secret/passphrase не заданы");}
    private static String isoNow(){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f.format(new Date());}
    private static String query(LinkedHashMap<String,String>p)throws Exception{StringBuilder b=new StringBuilder();for(Map.Entry<String,String>e:p.entrySet()){if(b.length()>0)b.append('&');b.append(URLEncoder.encode(e.getKey(),"UTF-8")).append('=').append(URLEncoder.encode(e.getValue(),"UTF-8"));}return b.toString();}
    private static String read(InputStream in)throws Exception{if(in==null)return"";try(BufferedReader b=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){StringBuilder s=new StringBuilder();String x;while((x=b.readLine())!=null)s.append(x);return s.toString();}}
    private static String normalize(String instId){if(instId==null)return"";if(instId.endsWith("-USDT-SWAP"))return instId.substring(0,instId.length()-10).replace("-","")+"USDT";return instId.replace("-","");}
    public static String toInstId(String symbol){if(symbol==null)return"";if(symbol.contains("-"))return symbol;String s=symbol.toUpperCase(Locale.US);if(s.endsWith("USDT"))return s.substring(0,s.length()-4)+"-USDT-SWAP";return s;}
    private static String safeId(String x){String z=(x==null?"":x).replaceAll("[^A-Za-z0-9]","");if(z.isEmpty())z="KTRN"+System.currentTimeMillis();return z.length()<=32?z:z.substring(0,32);}
    private static boolean empty(String x){return x==null||x.trim().isEmpty();}
    private static double d(JSONObject x,String k){String v=x.optString(k,"");if(v==null||v.isEmpty())return 0;try{return Double.parseDouble(v);}catch(Exception e){return x.optDouble(k,0);}}
    private static BigDecimal bd(String x){try{return new BigDecimal(x);}catch(Exception e){return BigDecimal.ZERO;}}
    private static long parseLong(String x){try{return Long.parseLong(x);}catch(Exception e){return 0;}}
    @Override public void close(){}

    public static final class OrderResult{public final String orderId,stopAlgoId;public OrderResult(String o,String s){orderId=o;stopAlgoId=s;}}
    public static final class StopInfo{public final String algoId;public final double stopPrice;public StopInfo(String id,double px){algoId=id;stopPrice=px;}}
    public static final class ApiException extends Exception{public final String code,raw;ApiException(String code,String msg,String raw){super("OKX "+code+": "+msg);this.code=code;this.raw=raw;}}
}
