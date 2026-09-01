package com.keytrins.kucoingrid;

import android.util.Base64;
import org.json.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ApiClient {
    public static final String BASE = "https://api-futures.kucoin.com";
    private final String apiKey, secret, passphrase;
    public ApiClient(String apiKey, String secret, String passphrase){this.apiKey=apiKey;this.secret=secret;this.passphrase=passphrase;}
    private String hmac(String s) throws Exception { Mac m=Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256")); return Base64.encodeToString(m.doFinal(s.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP); }
    private JSONObject request(String method,String path,String body,boolean auth) throws Exception {
        long ts=System.currentTimeMillis(); URL u=new URL(BASE+path); HttpURLConnection c=(HttpURLConnection)u.openConnection(); c.setRequestMethod(method); c.setConnectTimeout(10000); c.setReadTimeout(12000); c.setRequestProperty("Content-Type","application/json");
        if(auth){ String pre=ts+method+path+(body==null?"":body); c.setRequestProperty("KC-API-KEY",apiKey); c.setRequestProperty("KC-API-SIGN",hmac(pre)); c.setRequestProperty("KC-API-TIMESTAMP",String.valueOf(ts)); c.setRequestProperty("KC-API-PASSPHRASE",hmac(passphrase)); c.setRequestProperty("KC-API-KEY-VERSION","2"); }
        if(body!=null && !body.isEmpty()){c.setDoOutput(true); try(OutputStream os=c.getOutputStream()){os.write(body.getBytes(StandardCharsets.UTF_8));}}
        int code=c.getResponseCode(); InputStream is=(code>=200&&code<300)?c.getInputStream():c.getErrorStream(); String txt=readAll(is); if(txt==null||txt.isEmpty()) txt="{}"; JSONObject o=new JSONObject(txt); if(code<200||code>=300 || !"200000".equals(o.optString("code"))) throw new IOException("HTTP "+code+" "+txt); return o;
    }
    private static String readAll(InputStream is) throws Exception { if(is==null)return ""; BufferedReader br=new BufferedReader(new InputStreamReader(is,StandardCharsets.UTF_8)); StringBuilder sb=new StringBuilder(); String l; while((l=br.readLine())!=null)sb.append(l); return sb.toString(); }
    public ContractInfo contract(String symbol) throws Exception { JSONObject d=request("GET","/api/v1/contracts/"+URLEncoder.encode(symbol,"UTF-8"),null,false).getJSONObject("data"); return new ContractInfo(symbol,d.getDouble("tickSize"),d.getInt("lotSize"),d.getDouble("multiplier"),d.optDouble("makerFeeRate",0.0002),d.optDouble("takerFeeRate",0.0006)); }
    public List<Candle> klines(String symbol,int seconds,int count) throws Exception {
        String enc=URLEncoder.encode(symbol,"UTF-8");
        String p="/api/v1/kline/query?symbol="+enc+"&granularity="+seconds;
        JSONArray a=request("GET",p,null,false).getJSONArray("data");
        if(a.length()<Math.min(count,60)){
            long to=System.currentTimeMillis();
            long from=to-(long)seconds*1000L*Math.max(count*4,500);
            String p2=p+"&from="+from+"&to="+to;
            JSONArray b=request("GET",p2,null,false).getJSONArray("data");
            if(b.length()>a.length()) a=b;
        }
        List<Candle> out=new ArrayList<>();
        for(int i=0;i<a.length();i++){
            JSONArray x=a.getJSONArray(i);
            if(x.length()<6) continue;
            // KuCoin futures Kline: [time, open, high, low, close, volume, turnover]
            out.add(new Candle(x.getLong(0),x.getDouble(1),x.getDouble(4),x.getDouble(2),x.getDouble(3),x.getDouble(5)));
        }
        out.sort(Comparator.comparingLong(z->z.t));
        if(out.size()>count) return new ArrayList<>(out.subList(out.size()-count,out.size()));
        return out;
    }
    public void cancelAll(String symbol) throws Exception { request("DELETE","/api/v3/orders?symbol="+URLEncoder.encode(symbol,"UTF-8"),null,true); }
    public String place(String symbol,String side,double price,int size,int leverage,boolean test,boolean reduceOnly) throws Exception { JSONObject b=new JSONObject(); b.put("clientOid",UUID.randomUUID().toString().replace("-","")); b.put("symbol",symbol); b.put("marginMode","ISOLATED"); b.put("leverage",leverage); b.put("positionSide","BOTH"); b.put("side",side); b.put("type","limit"); b.put("size",size); b.put("price",fmt(price)); b.put("timeInForce","GTC"); b.put("postOnly",true); b.put("reduceOnly",reduceOnly); JSONObject r=request("POST",test?"/api/v1/orders/test":"/api/v1/orders",b.toString(),true); return r.getJSONObject("data").optString("orderId",""); }
    public int positionMode() throws Exception { return request("GET","/api/v2/position/getPositionMode",null,true).getJSONObject("data").optInt("positionMode",-1); }
    private static String fmt(double v){return String.format(Locale.US,"%.12f",v).replaceAll("0+$","").replaceAll("\\.$","");}
    public static final class ContractInfo{public final String symbol; public final double tick,multiplier,makerFee,takerFee; public final int lot; ContractInfo(String s,double t,int l,double m,double mf,double tf){symbol=s;tick=t;lot=l;multiplier=m;makerFee=mf;takerFee=tf;}}
    public static final class Candle{public final long t; public final double o,c,h,l,v; Candle(long t,double o,double c,double h,double l,double v){this.t=t;this.o=o;this.c=c;this.h=h;this.l=l;this.v=v;}}
}
