package com.keytrins.kucoingrid;
import java.util.*;
final class IndicatorMath {
 static double ema(List<ApiClient.Candle> c,int p){double k=2.0/(p+1),e=c.get(Math.max(0,c.size()-p*3)).c; for(int i=Math.max(1,c.size()-p*3+1);i<c.size();i++)e=c.get(i).c*k+e*(1-k); return e;}
 static double atr(List<ApiClient.Candle> c,int p){int s=Math.max(1,c.size()-p);double sum=0;int n=0;for(int i=s;i<c.size();i++){ApiClient.Candle x=c.get(i),q=c.get(i-1); sum+=Math.max(x.h-x.l,Math.max(Math.abs(x.h-q.c),Math.abs(x.l-q.c)));n++;} return n==0?0:sum/n;}
 static double adxApprox(List<ApiClient.Candle> c,int p){int s=Math.max(1,c.size()-p);double tr=0,plus=0,minus=0;for(int i=s;i<c.size();i++){ApiClient.Candle x=c.get(i),q=c.get(i-1);double up=x.h-q.h, dn=q.l-x.l;plus+=(up>dn&&up>0)?up:0;minus+=(dn>up&&dn>0)?dn:0;tr+=Math.max(x.h-x.l,Math.max(Math.abs(x.h-q.c),Math.abs(x.l-q.c)));} if(tr==0)return 0;double pdi=100*plus/tr,mdi=100*minus/tr;return 100*Math.abs(pdi-mdi)/Math.max(1e-9,pdi+mdi);}
 static double clamp(double x,double a,double b){return Math.max(a,Math.min(b,x));}
}
