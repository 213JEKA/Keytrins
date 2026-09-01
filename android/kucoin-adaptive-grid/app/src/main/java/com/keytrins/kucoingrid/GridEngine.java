package com.keytrins.kucoingrid;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GridEngine implements Runnable {
 public enum Mode{RANGE,TREND_UP,TREND_DOWN,SHOCK}
 public interface Log{void add(String s);}
 public static final class Config{public String symbol="XBTUSDTM"; public double capital=100; public int leverage=2,levels=5; public boolean test=true; public double maxDailyLossPct=3.0;}
 private final ApiClient api; private final Config cfg; private final Log log; private final AtomicBoolean run=new AtomicBoolean(true); private Mode last=null; private double anchor=0; private long lastRebuild=0; private boolean accountChecked=false;
 public GridEngine(ApiClient a,Config c,Log l){api=a;cfg=c;log=l;}
 public void stop(){run.set(false);}
 public void run(){ while(run.get()){try{cycle();Thread.sleep(15000);}catch(InterruptedException e){return;}catch(Exception e){log.add("Ошибка: "+e.getMessage());sleep(10000);}} }
 private void sleep(long ms){try{Thread.sleep(ms);}catch(Exception ignored){}}
 private void cycle() throws Exception {
  if(!accountChecked){int pm=api.positionMode(); if(pm!=0) throw new Exception("Для v0.1 нужен KuCoin One-Way Position Mode (positionMode=0). Hedge Mode не запускаем, чтобы neutral-grid не открыл две независимые позиции."); accountChecked=true; log.add("Режим позиции KuCoin: One-Way — OK");}
  List<ApiClient.Candle> m1=api.klines(cfg.symbol,60,180); if(m1.size()<60)throw new Exception("Недостаточно свечей: KuCoin вернул "+m1.size()+" (нужно ≥60)"); ApiClient.ContractInfo ci=api.contract(cfg.symbol);
  double price=m1.get(m1.size()-1).c, atr=IndicatorMath.atr(m1,14), e20=IndicatorMath.ema(m1,20),e50=IndicatorMath.ema(m1,50),adx=IndicatorMath.adxApprox(m1,14); double atrPct=atr/price; ApiClient.Candle x=m1.get(m1.size()-1),q=m1.get(m1.size()-2); double tr=Math.max(x.h-x.l,Math.max(Math.abs(x.h-q.c),Math.abs(x.l-q.c)));
  Mode mode; boolean shock=tr>2.2*atr || Math.abs(x.c-q.c)>1.6*atr; double sep=Math.abs(e20-e50)/Math.max(atr,1e-9); double slope=(e20-IndicatorMath.ema(m1.subList(0,m1.size()-5),20))/Math.max(atr,1e-9);
  if(shock)mode=Mode.SHOCK; else if(adx>=23 && sep>=0.35 && slope>0.10 && e20>e50)mode=Mode.TREND_UP; else if(adx>=23 && sep>=0.35 && slope<-0.10 && e20<e50)mode=Mode.TREND_DOWN; else mode=Mode.RANGE;
  double feeFloor=2.4*ci.makerFee; double stepPct=IndicatorMath.clamp(Math.max(feeFloor+0.0008,0.55*atrPct),0.0030,0.0150); double step=price*stepPct;
  boolean rebuild= last==null || mode!=last || Math.abs(price-anchor)>=0.75*step || System.currentTimeMillis()-lastRebuild>180000;
  log.add(String.format(Locale.US,"%s  %.6f | свечей %d | ATR %.2f%% ADX %.1f | шаг %.2f%%",mode,price,m1.size(),atrPct*100,adx,stepPct*100));
  if(mode==Mode.SHOCK){ if(last!=Mode.SHOCK){if(!cfg.test) api.cancelAll(cfg.symbol); log.add("SHOCK: сетка снята. Новые позиции запрещены."+(cfg.test?" [TEST: реальные ордера не трогаем]":""));} last=mode; return; }
  if(!rebuild){last=mode;return;} if(!cfg.test) api.cancelAll(cfg.symbol); anchor=price; lastRebuild=System.currentTimeMillis(); last=mode;
  double usable=cfg.capital*0.80; int sides=(mode==Mode.RANGE?2:1); double minOrderNotional=price*ci.multiplier*ci.lot; int maxLevelsByCapital=(int)Math.floor((usable*cfg.leverage)/(Math.max(1,sides)*minOrderNotional)); int effectiveLevels=Math.max(1,Math.min(cfg.levels,maxLevelsByCapital));
  double notionalPer=usable*cfg.leverage/Math.max(1,effectiveLevels*sides); int contracts=(int)Math.floor(notionalPer/(price*ci.multiplier)); contracts=Math.max(ci.lot,(contracts/ci.lot)*ci.lot);
  if(maxLevelsByCapital<1) log.add("ВНИМАНИЕ: минимальный контракт крупнее расчетной доли капитала; используется 1 уровень.");
  if(effectiveLevels<cfg.levels) log.add("Уровни уменьшены "+cfg.levels+" → "+effectiveLevels+" по размеру контракта/капиталу.");
  int buys=0,sells=0;
  for(int i=1;i<=effectiveLevels;i++){
   if(mode!=Mode.TREND_DOWN){double p=round(price-i*step,ci.tick);api.place(cfg.symbol,"buy",p,contracts,cfg.leverage,cfg.test,false);buys++;}
   if(mode!=Mode.TREND_UP){double p=round(price+i*step,ci.tick);api.place(cfg.symbol,"sell",p,contracts,cfg.leverage,cfg.test,false);sells++;}
  }
  log.add("Сетка перестроена: BUY "+buys+", SELL "+sells+", контрактов/ордер "+contracts+(cfg.test?" [TEST]":" [LIVE]"));
 }
 private static double round(double p,double tick){return Math.round(p/tick)*tick;}
}
