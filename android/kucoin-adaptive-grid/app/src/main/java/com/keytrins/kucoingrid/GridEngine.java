package com.keytrins.kucoingrid;

import android.content.Context;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GridEngine implements Runnable {
 public enum Mode{RANGE,TREND_UP,TREND_DOWN,SHOCK}
 public interface Log{void add(String s);}
 public static final class Config{
  public String symbol="DOGEUSDTM"; public double capital=100; public int leverage=2,levels=5; public boolean test=true;
  public double maxDailyLossPct=3.0,maxPositionLossPct=1.5,protectStepUsd=1.0,protectLagUsd=1.0;
 }
 private static final String BOT="KAG2";
 private final ApiClient api; private final Config cfg; private final Log log; private final RiskState state;
 private final AtomicBoolean run=new AtomicBoolean(true); private Mode last=null; private int uncoveredCycles=0; private double anchor=0; private long lastRebuild=0; private boolean accountChecked=false;

 public GridEngine(Context ctx,ApiClient a,Config c,Log l){api=a;cfg=c;log=l;state=new RiskState(ctx,c.symbol);}
 public void stop(){run.set(false);}
 public void run(){try{while(run.get()){try{cycle();Thread.sleep(cfg.test?15000:8000);}catch(InterruptedException e){break;}catch(Exception e){log.add("Ошибка: "+safe(e.getMessage()));sleep(8000);}}}finally{if(!cfg.test)shutdownLive();}}
 private void sleep(long ms){try{Thread.sleep(ms);}catch(Exception ignored){}}

 private void cycle() throws Exception {
  if(!accountChecked){int pm=api.positionMode();if(pm!=0)throw new Exception("Нужен KuCoin One-Way Position Mode (positionMode=0)");accountChecked=true;log.add("Режим позиции KuCoin: One-Way — OK");}
  List<ApiClient.Candle> m1=api.klines(cfg.symbol,60,180);if(m1.size()<60)throw new Exception("Недостаточно свечей: "+m1.size());
  ApiClient.ContractInfo ci=api.contract(cfg.symbol); Market mk=market(m1,ci);
  if(cfg.test){testCycle(mk,ci,m1.size());return;}

  ApiClient.Account acc=api.account(); state.rollDay(acc.equity);
  ApiClient.Position pos=api.position(cfg.symbol);
  List<ApiClient.Order> active=api.openOrders(cfg.symbol),done=api.doneOrders(cfg.symbol);
  List<ApiClient.Order> botActive=bot(active),botDone=bot(done);

  double dayPnl=acc.equity-state.dayStartEquity();
  if(state.dayHalted()){if(pos.qty!=0)emergencyClose(pos,botActive,"Дневной стоп уже активен",24*60);else cancelEntries(botActive);log.add(String.format(Locale.US,"ДНЕВНОЙ СТОП активен | PnL %.2f USDT | новых входов нет",dayPnl));return;}
  if(dayPnl<=-cfg.capital*cfg.maxDailyLossPct/100.0){state.haltDay();emergencyClose(pos,botActive,"Дневной лимит убытка "+fmt(dayPnl)+" USDT",24*60);return;}

  for(ApiClient.Order o:botActive)if(isEntry(o)&&o.filledSize>0&&o.remaining()>0)tryCancel(o);
  ensureLayerExits(pos,botActive,botDone,ci,mk.price,mk.stepPct);
  pos=api.position(cfg.symbol);

  if(pos.qty==0){state.resetCampaign();}
  else{
   double peak=Math.max(state.peak(),pos.unrealisedPnl);double prot=protectedPnl(peak,pos,ci);state.campaign(peak,prot);
   double posLossLimit=-cfg.capital*cfg.maxPositionLossPct/100.0;
   if(pos.unrealisedPnl<=posLossLimit){state.cooldown(System.currentTimeMillis()+30*60*1000L);emergencyClose(pos,api.openOrders(cfg.symbol),"Аварийный убыток позиции "+fmt(pos.unrealisedPnl)+" USDT",30);return;}
   if(peak>=cfg.protectStepUsd&&pos.unrealisedPnl<=prot){state.cooldown(System.currentTimeMillis()+10*60*1000L);emergencyClose(pos,api.openOrders(cfg.symbol),"Денежная защита: peak "+fmt(peak)+" → protected "+fmt(prot),10);return;}
  }

  boolean shockAgainst=mk.mode==Mode.SHOCK&&pos.qty!=0&&pos.unrealisedPnl<0&&((pos.qty>0&&mk.last.c<mk.prev.c)||(pos.qty<0&&mk.last.c>mk.prev.c));
  if(mk.mode==Mode.SHOCK){cancelEntries(api.openOrders(cfg.symbol));if(shockAgainst){state.cooldown(System.currentTimeMillis()+15*60*1000L);emergencyClose(pos,api.openOrders(cfg.symbol),"SHOCK против позиции",15);}else log.add("SHOCK: новые входы сняты; прибыльная/нейтральная позиция остаётся под reduce-only выходами.");last=mk.mode;return;}

  if(System.currentTimeMillis()<state.cooldownUntil()){cancelEntries(api.openOrders(cfg.symbol));log.add("Пауза защиты: новые входы временно запрещены.");return;}

  enforceDirection(pos,mk.mode,api.openOrders(cfg.symbol));

  boolean rebuild=last==null||mk.mode!=last||Math.abs(mk.price-anchor)>=0.75*mk.step||System.currentTimeMillis()-lastRebuild>180000;
  if(rebuild&&hasBotEntries(api.openOrders(cfg.symbol))){cancelEntries(api.openOrders(cfg.symbol));anchor=mk.price;lastRebuild=System.currentTimeMillis();last=mk.mode;log.add("Перецентровка: старые входные уровни снимаются; новая сетка будет выставлена после подтверждения отмены.");return;}
  if(rebuild){anchor=mk.price;lastRebuild=System.currentTimeMillis();last=mk.mode;}

  List<ApiClient.Order> now=api.openOrders(cfg.symbol);
  placeDesiredEntries(pos,mk,ci,now);
  ensureFallbackExit(api.position(cfg.symbol),api.openOrders(cfg.symbol),ci,mk.price,mk.stepPct);

  ApiClient.Position p2=api.position(cfg.symbol);
  log.add(String.format(Locale.US,"%s %.6f | свечей %d | ATR %.2f%% ADX %.1f | шаг %.2f%% | POS %d @ %.6f | uPnL %.2f | peak %.2f / protect %.2f | day %.2f",
          mk.mode,mk.price,m1.size(),mk.atrPct*100,mk.adx,mk.stepPct*100,p2.qty,p2.avgEntry,p2.unrealisedPnl,state.peak(),state.protectedPnl(),dayPnl));
 }

 private void testCycle(Market mk,ApiClient.ContractInfo ci,int candles) throws Exception{
  Sizing s=sizing(mk.price,ci,mk.mode);int buys=0,sells=0;
  if(s.levels<1){log.add("TEST: минимальный контракт слишком крупный для заданного капитала/плеча — входы запрещены.");return;}
  for(int i=1;i<=s.levels;i++){
   if(mk.mode==Mode.SHOCK)break;
   if(mk.mode!=Mode.TREND_DOWN){api.placeLimit(cfg.symbol,"buy",round(mk.price-i*mk.step,ci.tick),s.contracts,cfg.leverage,true,false,entryOid('L',i,mk.stepPct));buys++;}
   if(mk.mode!=Mode.TREND_UP){api.placeLimit(cfg.symbol,"sell",round(mk.price+i*mk.step,ci.tick),s.contracts,cfg.leverage,true,false,entryOid('S',i,mk.stepPct));sells++;}
  }
  log.add(String.format(Locale.US,"%s %.6f | свечей %d | ATR %.2f%% ADX %.1f | шаг %.2f%%",mk.mode,mk.price,candles,mk.atrPct*100,mk.adx,mk.stepPct*100));
  if(mk.mode==Mode.SHOCK)log.add("TEST: SHOCK → входы не выставляются."); else log.add("TEST: входные заявки приняты KuCoin: BUY "+buys+" / SELL "+sells+", по "+s.contracts+" контрактов. /orders/test не попадает в matching engine, поэтому fill → reduce-only → восстановление проверяется только в минимальном LIVE.");
 }

 private void ensureLayerExits(ApiClient.Position pos,List<ApiClient.Order> active,List<ApiClient.Order> done,ApiClient.ContractInfo ci,double market,double defaultStepPct) throws Exception{
  if(pos.qty==0)return;List<ApiClient.Order> all=mergeOrders(active,done);
  List<ApiClient.Order> entries=new ArrayList<>();for(ApiClient.Order o:all)if(isEntry(o)&&o.filledSize>0)entries.add(o);
  entries.sort(Comparator.comparingLong(o->o.createdAt));
  int capacity=Math.abs(pos.qty);int already=0;for(ApiClient.Order o:active)if(isBot(o)&&o.reduceOnly&&exitMatchesPosition(o,pos))already+=o.remaining();
  int free=Math.max(0,capacity-already);
  for(ApiClient.Order e:entries){char dir=entryDir(e.clientOid);if((pos.qty>0&&dir!='L')||(pos.qty<0&&dir!='S'))continue;
   String key=exitPrefixFor(e.clientOid);int exitFilled=0,exitOpen=0,maxAttempt=-1;
   for(ApiClient.Order x:all)if(x.clientOid.startsWith(key)){exitFilled+=x.filledSize;if(x.active)exitOpen+=x.remaining();maxAttempt=Math.max(maxAttempt,exitAttempt(x.clientOid));}
   int need=Math.max(0,e.filledSize-exitFilled-exitOpen);if(need<=0||free<=0)continue;need=Math.min(need,free);
   double ep=e.avgDealPrice>0?e.avgDealPrice:e.price;double sp=stepPctFromEntry(e.clientOid,defaultStepPct);double target=dir=='L'?ep*(1+sp):ep*(1-sp);target=round(target,ci.tick);
   String oid=key+String.format(Locale.US,"-%02d",maxAttempt+1);String side=dir=='L'?"sell":"buy";
   if((dir=='L'&&market>=target)||(dir=='S'&&market<=target)) api.placeMarket(cfg.symbol,side,need,cfg.leverage,false,true,oid);
   else api.placeLimit(cfg.symbol,side,target,need,cfg.leverage,false,true,oid);
   free-=need;log.add("Fill уровня → reduce-only "+side.toUpperCase(Locale.US)+" "+need+" @ "+fmt(target));
  }
 }

 private void ensureFallbackExit(ApiClient.Position pos,List<ApiClient.Order> active,ApiClient.ContractInfo ci,double market,double stepPct) throws Exception{
  if(pos.qty==0){uncoveredCycles=0;return;}int covered=0;for(ApiClient.Order o:active)if(o.reduceOnly&&isBot(o)&&exitMatchesPosition(o,pos))covered+=o.remaining();
  int need=Math.max(0,Math.abs(pos.qty)-covered);if(need<=0){uncoveredCycles=0;return;}uncoveredCycles++;if(uncoveredCycles<2)return;
  double target=pos.qty>0?pos.avgEntry*(1+stepPct):pos.avgEntry*(1-stepPct);target=round(target,ci.tick);String side=pos.qty>0?"sell":"buy";String oid="KAG2F-"+Long.toString(System.currentTimeMillis(),36);
  if((pos.qty>0&&market>=target)||(pos.qty<0&&market<=target))api.placeMarket(cfg.symbol,side,need,cfg.leverage,false,true,oid);else api.placeLimit(cfg.symbol,side,target,need,cfg.leverage,false,true,oid);
  uncoveredCycles=0;log.add("Резервный reduce-only выход для непокрытой позиции: "+need+" @ "+fmt(target));
 }

 private void placeDesiredEntries(ApiClient.Position pos,Market mk,ApiClient.ContractInfo ci,List<ApiClient.Order> active) throws Exception{
  Sizing s=sizing(mk.price,ci,mk.mode);if(s.levels<1){cancelEntries(active);log.add("Входы запрещены: минимальный контракт превышает бюджет сетки.");return;}int maxQty=s.contracts*s.levels;int current=Math.abs(pos.qty);
  int pendingSame=0;for(ApiClient.Order o:active)if(isEntry(o)&&entryMatchesPositionOrMode(o,pos,mk.mode))pendingSame+=o.remaining();
  int slots=Math.max(0,(maxQty-current-pendingSame)/Math.max(1,s.contracts));if(pos.qty==0)slots=s.levels;
  int activeBuy=countEntries(active,'L'),activeSell=countEntries(active,'S');int madeB=0,madeS=0;
  if(pos.qty>0){if(mk.mode!=Mode.TREND_DOWN)madeB=fillSide('L',s.levels-activeBuy,slots,mk,ci,s.contracts,active);}
  else if(pos.qty<0){if(mk.mode!=Mode.TREND_UP)madeS=fillSide('S',s.levels-activeSell,slots,mk,ci,s.contracts,active);}
  else if(mk.mode==Mode.RANGE){madeB=fillSide('L',s.levels-activeBuy,s.levels,mk,ci,s.contracts,active);madeS=fillSide('S',s.levels-activeSell,s.levels,mk,ci,s.contracts,active);}
  else if(mk.mode==Mode.TREND_UP)madeB=fillSide('L',s.levels-activeBuy,s.levels,mk,ci,s.contracts,active);
  else if(mk.mode==Mode.TREND_DOWN)madeS=fillSide('S',s.levels-activeSell,s.levels,mk,ci,s.contracts,active);
  if(madeB+madeS>0)log.add("Входная сетка дополнена: BUY "+madeB+", SELL "+madeS+", контрактов/уровень "+s.contracts+", максимум позиции "+maxQty);
 }

 private int fillSide(char dir,int missing,int cap,Market mk,ApiClient.ContractInfo ci,int contracts,List<ApiClient.Order> active) throws Exception{
  int n=Math.max(0,Math.min(missing,cap)),made=0;Set<Long> used=new HashSet<>();for(ApiClient.Order o:active)if(isEntry(o)&&entryDir(o.clientOid)==dir)used.add(Math.round(o.price/ci.tick));
  for(int i=1;i<=cfg.levels&&made<n;i++){double p=round(dir=='L'?mk.price-i*mk.step:mk.price+i*mk.step,ci.tick);long k=Math.round(p/ci.tick);if(used.contains(k))continue;api.placeLimit(cfg.symbol,dir=='L'?"buy":"sell",p,contracts,cfg.leverage,false,false,entryOid(dir,i,mk.stepPct));used.add(k);made++;}
  return made;
 }

 private void enforceDirection(ApiClient.Position pos,Mode mode,List<ApiClient.Order> active){
  for(ApiClient.Order o:active)if(isEntry(o)){char d=entryDir(o.clientOid);boolean cancel=(pos.qty>0&&d=='S')||(pos.qty<0&&d=='L')||(mode==Mode.TREND_UP&&d=='S')||(mode==Mode.TREND_DOWN&&d=='L');if(cancel)tryCancel(o);}
 }
 private void cancelEntries(List<ApiClient.Order> orders){for(ApiClient.Order o:orders)if(isEntry(o))tryCancel(o);}
 private void emergencyClose(ApiClient.Position pos,List<ApiClient.Order> orders,String why,int cooldownMin) throws Exception{
  for(ApiClient.Order o:orders)if(isBot(o))tryCancel(o);if(pos.qty!=0){String side=pos.qty>0?"sell":"buy";api.placeMarket(cfg.symbol,side,Math.abs(pos.qty),cfg.leverage,false,true,"KAG2C-"+Long.toString(System.currentTimeMillis(),36));}
  if(cooldownMin>0&&cooldownMin<24*60)state.cooldown(System.currentTimeMillis()+cooldownMin*60*1000L);log.add("ЗАЩИТА: "+why+" → все ордера робота сняты, позиция закрывается reduce-only MARKET.");
 }
 private void tryCancel(ApiClient.Order o){try{if(o.active)api.cancelOrder(o.id);}catch(Exception e){log.add("Отмена "+o.clientOid+": "+safe(e.getMessage()));}}

 private void shutdownLive(){try{List<ApiClient.Order> a=api.openOrders(cfg.symbol);cancelEntries(a);ApiClient.Position p=api.position(cfg.symbol);if(p.qty!=0){ApiClient.ContractInfo ci=api.contract(cfg.symbol);uncoveredCycles=1;ensureFallbackExit(p,api.openOrders(cfg.symbol),ci,p.mark>0?p.mark:p.avgEntry,0.005);log.add("СТОП: входные ордера робота сняты; открытая позиция оставлена только под reduce-only выходом.");}else log.add("СТОП: входные ордера робота сняты, позиции нет.");}catch(Exception e){log.add("СТОП: не удалось завершить очистку: "+safe(e.getMessage()));}}
 private double protectedPnl(double peak,ApiClient.Position pos,ApiClient.ContractInfo ci){
  if(peak<cfg.protectStepUsd)return 0;double notional=Math.abs(pos.qty)*ci.multiplier*Math.max(pos.avgEntry,pos.mark);double costs=notional*(ci.makerFee+ci.takerFee)+0.02;
  if(peak<2*cfg.protectStepUsd)return Math.max(0,costs);
  double stair=Math.floor(peak/cfg.protectStepUsd)*cfg.protectStepUsd-cfg.protectLagUsd;return Math.max(costs,stair);
 }
 private Sizing sizing(double price,ApiClient.ContractInfo ci,Mode mode){double usable=cfg.capital*0.80;int sides=mode==Mode.RANGE?2:1;double minNotional=price*ci.multiplier*ci.lot;int maxLevelsByCapital=(int)Math.floor((usable*cfg.leverage)/(Math.max(1,sides)*Math.max(minNotional,1e-9)));int levels=Math.max(0,Math.min(cfg.levels,maxLevelsByCapital));if(levels<1)return new Sizing(0,0);double notionalPer=usable*cfg.leverage/Math.max(1,levels*sides);int contracts=(int)Math.floor(notionalPer/Math.max(price*ci.multiplier,1e-9));contracts=(contracts/ci.lot)*ci.lot;if(contracts<ci.lot)return new Sizing(0,0);return new Sizing(levels,contracts);}
 private Market market(List<ApiClient.Candle> m1,ApiClient.ContractInfo ci){double price=m1.get(m1.size()-1).c,atr=IndicatorMath.atr(m1,14),e20=IndicatorMath.ema(m1,20),e50=IndicatorMath.ema(m1,50),adx=IndicatorMath.adxApprox(m1,14),atrPct=atr/Math.max(price,1e-9);ApiClient.Candle x=m1.get(m1.size()-1),q=m1.get(m1.size()-2);double tr=Math.max(x.h-x.l,Math.max(Math.abs(x.h-q.c),Math.abs(x.l-q.c)));boolean shock=tr>2.2*atr||Math.abs(x.c-q.c)>1.6*atr;double sep=Math.abs(e20-e50)/Math.max(atr,1e-9),slope=(e20-IndicatorMath.ema(m1.subList(0,m1.size()-5),20))/Math.max(atr,1e-9);Mode mode;if(shock)mode=Mode.SHOCK;else if(adx>=23&&sep>=0.35&&slope>0.10&&e20>e50)mode=Mode.TREND_UP;else if(adx>=23&&sep>=0.35&&slope<-0.10&&e20<e50)mode=Mode.TREND_DOWN;else mode=Mode.RANGE;double feeFloor=2.4*ci.makerFee,stepPct=IndicatorMath.clamp(Math.max(feeFloor+0.0008,0.55*atrPct),0.0030,0.0150);return new Market(mode,price,atrPct,adx,stepPct,price*stepPct,x,q);}

 private static List<ApiClient.Order> mergeOrders(List<ApiClient.Order> a,List<ApiClient.Order> b){LinkedHashMap<String,ApiClient.Order> m=new LinkedHashMap<>();for(ApiClient.Order o:a)mergeOne(m,o);for(ApiClient.Order o:b)mergeOne(m,o);return new ArrayList<>(m.values());}
 private static void mergeOne(Map<String,ApiClient.Order> m,ApiClient.Order o){String k=(o.clientOid!=null&&!o.clientOid.isEmpty())?o.clientOid:o.id;ApiClient.Order q=m.get(k);if(q==null||o.filledSize>q.filledSize||(o.filledSize==q.filledSize&&o.active&&!q.active))m.put(k,o);}
 private static List<ApiClient.Order> bot(List<ApiClient.Order> in){List<ApiClient.Order> out=new ArrayList<>();for(ApiClient.Order o:in)if(isBot(o))out.add(o);return out;}
 private static boolean isBot(ApiClient.Order o){return o.clientOid!=null&&o.clientOid.startsWith(BOT);}
 private static boolean isEntry(ApiClient.Order o){return o.clientOid!=null&&o.clientOid.startsWith("KAG2E-");}
 private static boolean hasBotEntries(List<ApiClient.Order> a){for(ApiClient.Order o:a)if(isEntry(o))return true;return false;}
 private static int countEntries(List<ApiClient.Order> a,char d){int n=0;for(ApiClient.Order o:a)if(isEntry(o)&&entryDir(o.clientOid)==d)n++;return n;}
 private static char entryDir(String oid){try{return oid.split("-")[1].charAt(0);}catch(Exception e){return '?';}}
 private static double stepPctFromEntry(String oid,double def){try{return Integer.parseInt(oid.split("-")[4])/10000.0;}catch(Exception e){return def;}}
 private static String exitPrefixFor(String entryOid){return "KAG2X-"+entryOid.substring("KAG2E-".length());}
 private static int exitAttempt(String oid){try{String[] p=oid.split("-");return Integer.parseInt(p[p.length-1]);}catch(Exception e){return 0;}}
 private static String entryOid(char dir,int level,double stepPct){int bps=(int)Math.round(stepPct*10000);String rnd=Integer.toString(new Random().nextInt(1296),36);return String.format(Locale.US,"KAG2E-%c-%s-%02d-%03d-%s",dir,Long.toString(System.currentTimeMillis(),36),level,bps,rnd);}
 private static boolean exitMatchesPosition(ApiClient.Order o,ApiClient.Position p){return p.qty>0&&"sell".equalsIgnoreCase(o.side)||p.qty<0&&"buy".equalsIgnoreCase(o.side);}
 private static boolean entryMatchesPositionOrMode(ApiClient.Order o,ApiClient.Position p,Mode m){char d=entryDir(o.clientOid);if(p.qty>0)return d=='L';if(p.qty<0)return d=='S';return (m==Mode.RANGE)||(m==Mode.TREND_UP&&d=='L')||(m==Mode.TREND_DOWN&&d=='S');}
 private static double round(double p,double tick){return Math.round(p/Math.max(tick,1e-12))*tick;}
 private static String fmt(double v){return String.format(Locale.US,"%.4f",v);}
 private static String safe(String s){return s==null?"неизвестная ошибка":s;}
 private static final class Sizing{final int levels,contracts;Sizing(int l,int c){levels=l;contracts=c;}}
 private static final class Market{final Mode mode;final double price,atrPct,adx,stepPct,step;final ApiClient.Candle last,prev;Market(Mode m,double p,double a,double d,double sp,double s,ApiClient.Candle l,ApiClient.Candle q){mode=m;price=p;atrPct=a;adx=d;stepPct=sp;step=s;last=l;prev=q;}}
}
