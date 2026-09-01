package com.keytrins.liveresearch.bot;

import android.content.Context;

import com.keytrins.liveresearch.BotRuntime;
import com.keytrins.liveresearch.SettingsStore;
import com.keytrins.liveresearch.model.Candle;
import com.keytrins.liveresearch.model.Instrument;
import com.keytrins.liveresearch.model.Position;
import com.keytrins.liveresearch.model.Signal;
import com.keytrins.liveresearch.model.SignalResult;
import com.keytrins.liveresearch.model.Ticker;
import com.keytrins.liveresearch.model.TradeState;
import com.keytrins.liveresearch.net.OkxClient;
import com.keytrins.liveresearch.storage.Db;
import com.keytrins.liveresearch.strategy.StrategyEngine;
import com.keytrins.liveresearch.util.Decimals;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class LiveResearchEngine implements AutoCloseable {
    private static final Set<String> STABLE_BASES=new HashSet<>(Arrays.asList("USDT","USDC","USDE","FDUSD","TUSD","DAI","USDD","PYUSD","USD1","USDP"));
    private static final double LOCK_STEP_USDT=1.00, LOCK_LAG_USDT=1.00, LOCK_CATCHUP_TOLERANCE=0.10;
    private static final long MANAGE_INTERVAL_MS=1000L,BALANCE_REFRESH_MS=5000L;

    private final SettingsStore.Snapshot s;private final OkxClient api;private final Db db;private final StrategyEngine strategy;
    private Map<String,Instrument> instruments=new HashMap<>();private Map<String,Ticker> tickers=new HashMap<>();private final Map<String,TradeState> trades;private List<String> universe=new ArrayList<>();
    private long lastUniverseRefresh=0,lastScanClose=-1,lastManage=0,lastBalanceRefresh=0;

    public LiveResearchEngine(Context context,SettingsStore.Snapshot s){this.s=s;api=new OkxClient(s);db=new Db(context.getApplicationContext());strategy=new StrategyEngine(s);trades=db.openTrades();}

    public static String doctor(Context context,SettingsStore.Snapshot s)throws Exception{try(OkxClient api=new OkxClient(s)){long server=api.serverTimeMs(),skew=Math.abs(System.currentTimeMillis()-server);int inst=api.getInstruments().size();if(s.apiKey.isEmpty()||s.apiSecret.isEmpty()||s.apiPassphrase.isEmpty())return "PUBLIC OK • OKX symbols="+inst+" • API ключи не заданы";double bal=api.walletBalanceUsdt();boolean net=api.isNetMode();return String.format(Locale.US,"OKX API OK • balance=%.2f USDT • symbols=%d • time skew=%dms • mode=%s",bal,inst,skew,net?"net_mode":"НЕ net_mode");}}

    public void run(BooleanSupplier keepRunning,Consumer<String> notify)throws Exception{
        refreshUniverse(true);if(!s.apiKey.isEmpty()&&!s.apiSecret.isEmpty()&&!s.apiPassphrase.isEmpty()){try{BotRuntime.balance=api.walletBalanceUsdt();lastBalanceRefresh=System.currentTimeMillis();}catch(Exception e){log("Баланс OKX недоступен: "+err(e));}}
        BotRuntime.status=s.live?"OKX INVERSE LIVE • работает":"OKX INVERSE OBSERVE • работает";notify.accept(BotRuntime.status);log("OKX Inverse v0.1.1 • Universe="+universe.size()+" • риск="+s.riskUsdt+" USDT • profit-lock $1/$1");
        while(keepRunning.getAsBoolean()){
            long now=System.currentTimeMillis();try{if(now-lastUniverseRefresh>=60*60_000L)refreshUniverse(false);if(now-lastManage>=MANAGE_INTERVAL_MS){managePositions();lastManage=System.currentTimeMillis();}long close=(now/(15*60_000L))*(15*60_000L);if(now-close>=8_000L&&close!=lastScanClose){scanOnce();lastScanClose=close;}BotRuntime.openPositions=trades.size();notify.accept(BotRuntime.status+" • pos "+trades.size()+" • uni "+universe.size());}catch(Exception e){log("LOOP ERROR: "+err(e));}Thread.sleep(200L);
        }
    }

    private void refreshUniverse(boolean force)throws Exception{
        if(!force&&System.currentTimeMillis()-lastUniverseRefresh<60*60_000L&&!universe.isEmpty())return;instruments=api.getInstruments();tickers=api.getAllTickers();List<Instrument> c=new ArrayList<>();
        for(Instrument inst:instruments.values()){if(STABLE_BASES.contains(inst.baseCoin))continue;Ticker t=tickers.get(inst.symbol);if(t==null||t.turnover24h<s.minTurnoverUsdt)continue;inst.turnover24h=t.turnover24h;c.add(inst);}c.sort((a,b)->Double.compare(b.turnover24h,a.turnover24h));universe=new ArrayList<>();for(int i=0;i<Math.min(s.universeSize,c.size());i++)universe.add(c.get(i).symbol);lastUniverseRefresh=System.currentTimeMillis();BotRuntime.universe=universe.size();db.event("INFO","UNIVERSE",String.join(",",universe),null,null);log("Universe: "+String.join(", ",universe));
    }

    private void scanOnce(){
        BotRuntime.scans++;try{tickers=api.getAllTickers();}catch(Exception e){log("Ticker scan fail: "+err(e));return;}Map<String,Position> positions=new HashMap<>();if(!s.apiKey.isEmpty()&&!s.apiSecret.isEmpty()&&!s.apiPassphrase.isEmpty()){try{positions=api.openPositions();}catch(Exception e){log("Positions read fail: "+err(e));if(s.live)return;}}
        List<String> symbols=new ArrayList<>(universe);for(String x:trades.keySet())if(!symbols.contains(x))symbols.add(x);int signalCount=0;
        for(String symbol:symbols){try{Instrument inst=instruments.get(symbol);if(inst==null)continue;List<Candle> h1=api.getKlines(symbol,"60",260),m15=api.getKlines(symbol,"15",160);TradeState tr=trades.get(symbol);if(tr!=null){StrategyEngine.BreakResult br=strategy.structuralBreak(tr.side,m15);tr.structureBreak=br.broken;tr.structureBreakTimeMs=br.broken?br.endMs:0;if(br.atr>0&&!Double.isNaN(br.atr))tr.atr=br.atr;db.upsertTrade(tr);}if(positions.containsKey(symbol)||trades.containsKey(symbol))continue;SignalResult sr=strategy.buildSignal(symbol,h1,m15);if(sr.signal==null)continue;signalCount++;BotRuntime.signals++;evaluateEntry(sr.signal,tickers.get(symbol));}catch(Exception e){db.event("ERROR","SCAN",err(e),symbol,null);}try{Thread.sleep(50);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}}
        log("Скан завершён: сигналов "+signalCount+" / "+universe.size());
    }

    private void evaluateEntry(Signal sig,Ticker t)throws Exception{
        Instrument inst=instruments.get(sig.symbol);if(inst==null||t==null)return;long age=Math.max(0,System.currentTimeMillis()-sig.signalTimeMs);if(age>180_000L){db.logSignal(sig.symbol,"REJECT","STALE_SIGNAL",sig,0,0);return;}if(s.live&&db.hasEntryForSignal(sig.symbol,sig.signalTimeMs))return;
        String actualDirection="LONG".equals(sig.direction)?"SHORT":"LONG",side="LONG".equals(actualDirection)?"Buy":"Sell";double entry="Buy".equals(side)?positive(t.ask,t.last,sig.entryRef):positive(t.bid,t.last,sig.entryRef);if(!(entry>0)||!(sig.riskDistance>0))return;double stop="Buy".equals(side)?entry-sig.riskDistance:entry+sig.riskDistance;
        double bpc=inst.basePerContract(entry);if(!(bpc>0)){db.logSignal(sig.symbol,"REJECT","INVALID_CONTRACT_VALUE",sig,0,0);return;}double desiredContracts=(s.riskUsdt/sig.riskDistance)/bpc;BigDecimal qty=quantizeContracts(inst,desiredContracts);if(qty==null){db.logSignal(sig.symbol,"REJECT","QTY_MIN",sig,0,0);return;}double contracts=qty.doubleValue(),baseQty=contracts*bpc,notional=baseQty*entry;if(notional>s.maxNotionalUsdt){db.logSignal(sig.symbol,"REJECT","NOTIONAL_CAP",sig,contracts,0);return;}double taker=api.feeRate(sig.symbol),spread=Math.max(0,t.ask-t.bid),estimatedCost=2*notional*taker+spread*baseQty,costR=estimatedCost/s.riskUsdt;if(costR>s.maxCostR){db.logSignal(sig.symbol,"REJECT","COST_R",sig,contracts,costR);return;}
        BigDecimal stopQ=stopPrice(inst,side,stop);db.logSignal(sig.symbol,s.live?"ENTRY_ATTEMPT":"WOULD_ENTER","INVERSE "+sig.direction+"→"+actualDirection,sig,contracts,costR);log(String.format(Locale.US,"INVERSE %s signal=%s actual=%s contracts=%s R=$%.2f cost/R=%.2f",sig.symbol,sig.direction,actualDirection,Decimals.fmt(qty),baseQty*sig.riskDistance,costR));if(!s.live)return;
        if(!api.isNetMode()){db.logSignal(sig.symbol,"REJECT","OKX_NOT_NET_MODE",sig,contracts,costR);log("ENTRY BLOCK "+sig.symbol+": OKX нужен One-Way / net_mode");return;}
        String tradeId="OX"+sig.symbol.replace("USDT","")+Long.toString(System.currentTimeMillis()/1000L);OkxClient.OrderResult or=api.placeEntry(tradeId,sig.symbol,side,Decimals.fmt(qty),Decimals.fmt(stopQ));Position p=api.waitPosition(sig.symbol,20_000L);double actualEntry=p.avgPrice>0?p.avgPrice:entry,actualContracts=p.size;double actualStop="Buy".equals(side)?actualEntry-sig.riskDistance:actualEntry+sig.riskDistance;BigDecimal actualStopQ=stopPrice(inst,side,actualStop);
        TradeState tr=new TradeState();tr.tradeId=tradeId;tr.symbol=sig.symbol;tr.side=side;tr.state="OPEN_INVERSE";tr.openedAtMs=System.currentTimeMillis();tr.entryPrice=actualEntry;tr.initialQty=actualContracts;tr.currentQty=actualContracts;tr.initialStop=actualStopQ.doubleValue();tr.currentStop=actualStopQ.doubleValue();tr.entryOrderId=or.orderId;tr.stopAlgoId=or.stopAlgoId;tr.riskDistance=sig.riskDistance;tr.targetRiskUsdt=s.riskUsdt;tr.atr=sig.m15Atr;tr.entryAtr=sig.m15Atr;tr.takerFee=taker;tr.spreadAtEntry=spread;tr.costREst=costR;tr.highWater=actualEntry;tr.lowWater=actualEntry;tr.peakProfitUsdt=0;tr.protectedProfitUsdt=0;
        try{for(int k=0;k<6&&empty(tr.stopAlgoId);k++){OkxClient.StopInfo si=api.stopInfo(tr.symbol,tr.entryOrderId);if(!empty(si.algoId)){tr.stopAlgoId=si.algoId;if(si.stopPrice>0)tr.currentStop=si.stopPrice;break;}Thread.sleep(250);}if(!empty(tr.stopAlgoId)&&Math.abs(tr.currentStop-actualStopQ.doubleValue())>=inst.tickSize.doubleValue()){api.amendStop(tr.symbol,tr.stopAlgoId,Decimals.fmt(actualStopQ));tr.currentStop=actualStopQ.doubleValue();}}catch(Exception e){log("Initial SL reconcile "+tr.symbol+": "+err(e));}
        trades.put(tr.symbol,tr);db.upsertTrade(tr);db.logSignal(tr.symbol,"ENTRY","FILLED_INVERSE",sig,actualContracts,costR);BotRuntime.entries++;log(String.format(Locale.US,"OKX ENTRY %s %s contracts=%.8f @ %.8f SL=%.8f stopAlgo=%s",tr.symbol,tr.side,tr.initialQty,tr.entryPrice,tr.currentStop,empty(tr.stopAlgoId)?"pending":"yes"));
    }

    private void managePositions(){if(trades.isEmpty()||empty(s.apiKey)||empty(s.apiSecret)||empty(s.apiPassphrase))return;try{Map<String,Position> positions=api.openPositions();tickers=api.getAllTickers();long now=System.currentTimeMillis();if(now-lastBalanceRefresh>=BALANCE_REFRESH_MS){try{BotRuntime.balance=api.walletBalanceUsdt();lastBalanceRefresh=now;}catch(Exception ignored){}}
        for(String symbol:new ArrayList<>(trades.keySet())){TradeState tr=trades.get(symbol);Instrument inst=instruments.get(symbol);Position p=positions.get(symbol);Ticker tk=tickers.get(symbol);if(p==null){finalizeCycle(tr);continue;}if(!p.side.equals(tr.side)){tr.state="SIDE_MISMATCH";db.upsertTrade(tr);log("SIDE MISMATCH "+symbol+" local="+tr.side+" exchange="+p.side);continue;}if(inst==null)continue;tr.currentQty=p.size;double mark=tk==null?p.markPrice:positive(tk.mark,tk.last,p.markPrice);if(!(mark>0))continue;tr.highWater=Math.max(tr.highWater,mark);tr.lowWater=Math.min(tr.lowWater,mark);double baseQty=baseQty(inst,tr.currentQty,mark),markGross="Buy".equals(tr.side)?(mark-tr.entryPrice)*baseQty:(tr.entryPrice-mark)*baseQty;double observed=p.unrealisedPnl;if(Double.isNaN(observed)||Double.isInfinite(observed))observed=markGross;if(markGross>observed)observed=markGross;if(observed>tr.peakProfitUsdt)tr.peakProfitUsdt=observed;
            if(empty(tr.stopAlgoId)&&!empty(tr.entryOrderId)){try{OkxClient.StopInfo si=api.stopInfo(symbol,tr.entryOrderId);if(!empty(si.algoId)){tr.stopAlgoId=si.algoId;if(si.stopPrice>0)tr.currentStop=si.stopPrice;log("STOP ID RECOVERED "+symbol);}}catch(Exception ignored){}}
            double r=priceR(tr,mark);boolean riskExit=maybeReduce(tr,inst,r);if(!riskExit)maybeDollarLock(tr,inst,mark);if(trades.containsKey(symbol))db.upsertTrade(tr);
        }BotRuntime.openPositions=trades.size();}catch(Exception e){log("MANAGE ERROR: "+err(e));}}

    private void maybeDollarLock(TradeState tr,Instrument inst,double mark)throws Exception{
        if(tr.currentQty<=0)return;double steps=Math.floor((Math.max(0,tr.peakProfitUsdt)+1e-9)/LOCK_STEP_USDT);if(steps<1)return;double protectedUsd=Math.max(0,steps*LOCK_STEP_USDT-LOCK_LAG_USDT),baseQty=baseQty(inst,tr.currentQty,mark);if(!(baseQty>0))return;double costPerBase=tr.entryPrice*(2*tr.takerFee)+tr.spreadAtEntry+2*inst.tickSize.doubleValue();double move=protectedUsd/baseQty+costPerBase;double candidate="Buy".equals(tr.side)?tr.entryPrice+move:tr.entryPrice-move;BigDecimal q=stopPrice(inst,tr.side,candidate);double qp=q.doubleValue();boolean crossed="Buy".equals(tr.side)?mark<=qp:mark>=qp;
        if(stopImproves(tr,inst,qp,mark)&&!empty(tr.stopAlgoId)){try{api.amendStop(tr.symbol,tr.stopAlgoId,Decimals.fmt(q));tr.currentStop=qp;tr.beArmed=true;tr.state=protectedUsd>0?"DOLLAR_LOCK":"BE";double actual=estimatedNetAtStop(tr,inst,qp,mark);if(actual>tr.protectedProfitUsdt)tr.protectedProfitUsdt=actual;log(String.format(Locale.US,"OKX LOCK %s peak=$%.2f target=$%.2f protected~$%.2f stop=%s",tr.symbol,tr.peakProfitUsdt,protectedUsd,tr.protectedProfitUsdt,Decimals.fmt(q)));return;}catch(Exception e){db.event("WARN","STOP_AMEND_FAIL",err(e),tr.symbol,tr.tradeId);}}
        double netNow=estimatedNetAtMark(tr,inst,mark);if(crossed&&netNow<=protectedUsd+LOCK_CATCHUP_TOLERANCE){marketCloseForLock(tr,inst,protectedUsd,netNow);}
    }

    private void marketCloseForLock(TradeState tr,Instrument inst,double target,double netNow)throws Exception{BigDecimal close=fullCloseQty(inst,tr.currentQty);if(close==null)return;String opposite="Buy".equals(tr.side)?"Sell":"Buy";double before=tr.currentQty;api.reducePosition("PL"+System.currentTimeMillis(),tr.symbol,opposite,Decimals.fmt(close));Position after=api.waitReduced(tr.symbol,before,8_000L);tr.currentQty=after==null?0:after.size;tr.beArmed=true;tr.state="PROFIT_LOCK_CATCHUP";db.event("WARN","PROFIT_LOCK_CATCHUP","target="+target+" netNow="+netNow,tr.symbol,tr.tradeId);log(String.format(Locale.US,"OKX LOCK CATCH-UP %s target=$%.2f netNow=$%.2f",tr.symbol,target,netNow));}

    private boolean maybeReduce(TradeState tr,Instrument inst,double r)throws Exception{if(tr.currentQty<=0)return false;String opposite="Buy".equals(tr.side)?"Sell":"Buy";if(r<=s.forceReduceR){BigDecimal close=fullCloseQty(inst,tr.currentQty);if(close==null)return true;double before=tr.currentQty;api.reducePosition("FX"+System.currentTimeMillis(),tr.symbol,opposite,Decimals.fmt(close));Position p=api.waitReduced(tr.symbol,before,8_000L);tr.currentQty=p==null?0:p.size;tr.reduced=true;tr.state="FORCE_EXIT";BotRuntime.reductions++;log(String.format(Locale.US,"FORCE EXIT %s %.3fR close=%s",tr.symbol,r,Decimals.fmt(close)));return true;}if(tr.reduced)return false;boolean fresh=tr.structureBreak&&(System.currentTimeMillis()-tr.structureBreakTimeMs)<=20*60_000L;if(r>s.reduceTriggerR||!fresh)return false;BigDecimal close=reduceQty(inst,tr.currentQty);if(close==null)return false;double before=tr.currentQty;api.reducePosition("R85"+System.currentTimeMillis(),tr.symbol,opposite,Decimals.fmt(close));Position p=api.waitReduced(tr.symbol,before,8_000L);tr.currentQty=p==null?0:p.size;tr.reduced=true;tr.state="REDUCED_85";BotRuntime.reductions++;log(String.format(Locale.US,"REDUCE85 %s %.3fR close=%s remain=%.8f",tr.symbol,r,Decimals.fmt(close),tr.currentQty));return p==null;}

    private void finalizeCycle(TradeState tr){long closed=System.currentTimeMillis();double[] tx=new double[]{0,0,0,0};try{Thread.sleep(300);tx=api.transactionSummary(tr.symbol,tr.openedAtMs,closed);}catch(Exception ignored){}db.closeTrade(tr,closed,tx[0],tx[1],tx[2],tx[3]);log(String.format(Locale.US,"CYCLE CLOSED %s net=%+.3f USDT peak=%+.2f protected~%+.2f",tr.symbol,tx[3],tr.peakProfitUsdt,tr.protectedProfitUsdt));db.deleteTrade(tr.symbol);trades.remove(tr.symbol);}

    private double estimatedNetAtMark(TradeState tr,Instrument inst,double mark){double bq=baseQty(inst,tr.currentQty,mark),gross="Buy".equals(tr.side)?(mark-tr.entryPrice)*bq:(tr.entryPrice-mark)*bq,cost=tr.entryPrice*(2*tr.takerFee)*bq+tr.spreadAtEntry*bq+2*inst.tickSize.doubleValue()*bq;return gross-cost;}
    private double estimatedNetAtStop(TradeState tr,Instrument inst,double stop,double mark){double bq=baseQty(inst,tr.currentQty,mark),gross="Buy".equals(tr.side)?(stop-tr.entryPrice)*bq:(tr.entryPrice-stop)*bq,cost=tr.entryPrice*(2*tr.takerFee)*bq+tr.spreadAtEntry*bq+2*inst.tickSize.doubleValue()*bq;return Math.max(0,gross-cost);}
    private double baseQty(Instrument inst,double contracts,double price){return contracts*inst.basePerContract(price>0?price:1);}
    private boolean stopImproves(TradeState tr,Instrument inst,double candidate,double mark){double min=inst.tickSize.doubleValue()*2;if("Buy".equals(tr.side))return candidate>tr.currentStop+min&&candidate<mark-inst.tickSize.doubleValue();return candidate<tr.currentStop-min&&candidate>mark+inst.tickSize.doubleValue();}
    private double priceR(TradeState tr,double price){if(tr.riskDistance<=0)return 0;return "Buy".equals(tr.side)?(price-tr.entryPrice)/tr.riskDistance:(tr.entryPrice-price)/tr.riskDistance;}
    private BigDecimal quantizeContracts(Instrument inst,double desired){BigDecimal q=Decimals.floorStep(Decimals.bd(desired),inst.qtyStep);if(q.compareTo(inst.minQty)<0)return null;if(q.compareTo(inst.maxMarketQty)>0)q=Decimals.floorStep(inst.maxMarketQty,inst.qtyStep);return q.signum()>0?q:null;}
    private BigDecimal reduceQty(Instrument inst,double current){BigDecimal cur=Decimals.floorStep(Decimals.bd(current),inst.qtyStep);if(cur.signum()<=0)return null;BigDecimal remain=Decimals.ceilStep(cur.multiply(Decimals.bd(1.0-s.reduceFraction)),inst.qtyStep);if(remain.compareTo(inst.minQty)<0)return cur;BigDecimal close=Decimals.floorStep(cur.subtract(remain),inst.qtyStep);return close.signum()>0?close:null;}
    private BigDecimal fullCloseQty(Instrument inst,double current){BigDecimal q=Decimals.floorStep(Decimals.bd(current),inst.qtyStep);return q.signum()>0?q:null;}
    private BigDecimal stopPrice(Instrument inst,String side,double raw){return "Buy".equals(side)?Decimals.floorTick(raw,inst.tickSize):Decimals.ceilTick(raw,inst.tickSize);}
    private static double positive(double...x){for(double v:x)if(v>0)return v;return 0;}private static boolean empty(String x){return x==null||x.trim().isEmpty();}private static String err(Throwable e){String m=e==null?null:e.getMessage();return m==null||m.trim().isEmpty()?String.valueOf(e):e.getClass().getSimpleName()+": "+m;}
    private void log(String x){BotRuntime.log(x);db.event("INFO","LOG",x,null,null);}
    @Override public void close(){try{db.close();}catch(Exception ignored){}try{api.close();}catch(Exception ignored){}}
}
