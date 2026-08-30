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
import com.keytrins.liveresearch.net.BybitClient;
import com.keytrins.liveresearch.storage.Db;
import com.keytrins.liveresearch.strategy.StrategyEngine;
import com.keytrins.liveresearch.util.Decimals;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class LiveResearchEngine implements AutoCloseable {
    private static final Set<String> STABLE_BASES = new HashSet<>(Arrays.asList(
            "USDT","USDC","USDE","FDUSD","TUSD","DAI","USDD","PYUSD","USD1","USDP"));

    private final SettingsStore.Snapshot s;
    private final BybitClient api;
    private final Db db;
    private final StrategyEngine strategy;
    private Map<String,Instrument> instruments = new HashMap<>();
    private Map<String,Ticker> tickers = new HashMap<>();
    private final Map<String,TradeState> trades;
    private List<String> universe = new ArrayList<>();
    private long lastUniverseRefresh = 0, lastScanClose = -1, lastManage = 0;

    public LiveResearchEngine(Context context, SettingsStore.Snapshot s) {
        this.s = s;
        this.api = new BybitClient(s);
        this.db = new Db(context.getApplicationContext());
        this.strategy = new StrategyEngine(s);
        this.trades = db.openTrades();
    }

    public static String doctor(Context context, SettingsStore.Snapshot s) throws Exception {
        try (BybitClient api = new BybitClient(s)) {
            long server = api.serverTimeMs();
            long skew = Math.abs(System.currentTimeMillis() - server);
            int inst = api.getInstruments().size();
            Map<String,Ticker> tick = api.getAllTickers();
            if (s.apiKey.isEmpty() || s.apiSecret.isEmpty()) {
                return "PUBLIC OK • symbols=" + inst + " • tickers=" + tick.size() + " • time skew=" + skew + "ms • API ключи не заданы";
            }
            double bal = api.walletBalanceUsdt();
            Map<String,Position> pos = api.openPositions();
            for (Position p : pos.values()) {
                if (p.positionIdx != 0) return "API OK, но найден Hedge Mode (positionIdx="+p.positionIdx+"). Для робота нужен One-Way.";
            }
            return String.format(Locale.US, "API OK • balance=%.2f USDT • positions=%d • symbols=%d • time skew=%dms",
                    bal, pos.size(), inst, skew);
        }
    }

    public void run(BooleanSupplier keepRunning, Consumer<String> notify) throws Exception {
        refreshUniverse(true);
        if (!s.apiKey.isEmpty() && !s.apiSecret.isEmpty()) {
            try { BotRuntime.balance = api.walletBalanceUsdt(); } catch (Exception e) { log("Баланс недоступен: "+err(e)); }
        }
        BotRuntime.status = s.live ? "LIVE • работает" : "OBSERVE • работает";
        notify.accept(BotRuntime.status);
        log("Universe="+universe.size()+" • риск="+s.riskUsdt+" USDT • "+(s.live?"LIVE":"OBSERVE"));

        while (keepRunning.getAsBoolean()) {
            long now = System.currentTimeMillis();
            try {
                if (now - lastUniverseRefresh >= 60 * 60_000L) refreshUniverse(false);
                if (now - lastManage >= 5_000L) {
                    managePositions();
                    lastManage = now;
                }
                long close = (now / (15 * 60_000L)) * (15 * 60_000L);
                if (now - close >= 8_000L && close != lastScanClose) {
                    scanOnce();
                    lastScanClose = close;
                }
                BotRuntime.openPositions = trades.size();
                notify.accept(BotRuntime.status + " • pos " + trades.size() + " • uni " + universe.size());
            } catch (Exception e) {
                log("LOOP ERROR: "+err(e));
            }
            Thread.sleep(1000L);
        }
    }

    private void refreshUniverse(boolean force) throws Exception {
        if (!force && System.currentTimeMillis() - lastUniverseRefresh < 60 * 60_000L && !universe.isEmpty()) return;
        instruments = api.getInstruments();
        tickers = api.getAllTickers();
        List<Instrument> candidates = new ArrayList<>();
        for (Instrument inst : instruments.values()) {
            if (STABLE_BASES.contains(inst.baseCoin)) continue;
            Ticker t = tickers.get(inst.symbol); if (t == null || t.turnover24h < s.minTurnoverUsdt) continue;
            inst.turnover24h = t.turnover24h; candidates.add(inst);
        }
        candidates.sort((a,b) -> Double.compare(b.turnover24h, a.turnover24h));
        universe = new ArrayList<>();
        for (int i=0;i<Math.min(s.universeSize,candidates.size());i++) universe.add(candidates.get(i).symbol);
        lastUniverseRefresh = System.currentTimeMillis();
        BotRuntime.universe = universe.size();
        db.event("INFO","UNIVERSE",String.join(",",universe),null,null);
        log("Universe: "+String.join(", ", universe));
    }

    private void scanOnce() {
        BotRuntime.scans++;
        try { tickers = api.getAllTickers(); }
        catch (Exception e) { log("Ticker scan fail: "+err(e)); return; }

        Map<String,Position> exchangePositions = new HashMap<>();
        if (!s.apiKey.isEmpty() && !s.apiSecret.isEmpty()) {
            try {
                exchangePositions = api.openPositions();
                for (Position p : exchangePositions.values()) {
                    if (p.positionIdx != 0) {
                        log("BLOCK ENTRY: "+p.symbol+" не One-Way mode");
                        if (s.live) return;
                    }
                }
            } catch (Exception e) {
                log("Positions read fail: "+err(e));
                if (s.live) return;
            }
        }

        List<String> symbols = new ArrayList<>(universe);
        for (String x : trades.keySet()) if (!symbols.contains(x)) symbols.add(x);
        int signalCount=0;
        for (String symbol : symbols) {
            Instrument inst=instruments.get(symbol); if(inst==null) continue;
            try {
                List<Candle> h1=api.getKlines(symbol,"60",260);
                List<Candle> m15=api.getKlines(symbol,"15",160);
                TradeState tr=trades.get(symbol);
                if(tr!=null){
                    StrategyEngine.BreakResult br=strategy.structuralBreak(tr.side,m15);
                    tr.structureBreak=br.broken; tr.structureBreakTimeMs=br.broken?br.endMs:0;
                    if(br.atr>0&&!Double.isNaN(br.atr))tr.atr=br.atr;
                    db.upsertTrade(tr);
                }
                if(exchangePositions.containsKey(symbol)||trades.containsKey(symbol)) continue;
                SignalResult sr=strategy.buildSignal(symbol,h1,m15);
                if(sr.signal==null) continue;
                signalCount++; BotRuntime.signals++;
                evaluateEntry(sr.signal, tickers.get(symbol));
            } catch(Exception e){ db.event("ERROR","SCAN",e.toString(),symbol,null); }
            try { Thread.sleep(60); } catch(InterruptedException e){ Thread.currentThread().interrupt(); return; }
        }
        log("Скан завершён: сигналов "+signalCount+" / "+universe.size());
    }

    private void evaluateEntry(Signal sig, Ticker t) throws Exception {
        String symbol=sig.symbol; Instrument inst=instruments.get(symbol); if(inst==null||t==null)return;
        long age=Math.max(0,System.currentTimeMillis()-sig.signalTimeMs);
        if(age>180_000L){db.logSignal(symbol,"REJECT","STALE_SIGNAL",sig,0,0);return;}
        if(s.live && db.hasEntryForSignal(symbol,sig.signalTimeMs))return;

        double entry="LONG".equals(sig.direction)?positive(t.ask,t.last,sig.entryRef):positive(t.bid,t.last,sig.entryRef);
        double stop=sig.stopRef;
        if(("LONG".equals(sig.direction)&&stop>=entry)||("SHORT".equals(sig.direction)&&stop<=entry))return;
        double riskDistance=Math.abs(entry-stop); if(riskDistance<=0)return;
        double desiredQty=s.riskUsdt/riskDistance;
        double desiredNotional=desiredQty*entry;
        if(desiredNotional>s.maxNotionalUsdt){db.logSignal(symbol,"REJECT","NOTIONAL_CAP",sig,0,0);return;}
        BigDecimal qty=quantizeEntryQty(inst,desiredQty,entry); if(qty==null){db.logSignal(symbol,"REJECT","QTY_MIN",sig,0,0);return;}
        double q=qty.doubleValue(); double actualRisk=q*riskDistance;
        double taker=api.feeRate(symbol); double spread=Math.max(0,t.ask-t.bid); double notional=q*entry;
        double estimatedCost=2.0*notional*taker+spread*q; double costR=estimatedCost/s.riskUsdt;
        if(costR>s.maxCostR){db.logSignal(symbol,"REJECT","COST_R",sig,q,costR);return;}

        String side="LONG".equals(sig.direction)?"Buy":"Sell";
        BigDecimal stopQ=stopPrice(inst,side,stop);
        db.logSignal(symbol,s.live?"ENTRY_ATTEMPT":"WOULD_ENTER",sig.reason,sig,q,costR);
        log(String.format(Locale.US,"%s %s qty=%s R=$%.2f cost/R=%.2f score=%.1f",sig.direction,symbol,Decimals.fmt(qty),actualRisk,costR,sig.trendScore));
        if(!s.live)return;

        String tradeId=("LRA_"+symbol+"_"+(System.currentTimeMillis()/1000L)); if(tradeId.length()>36)tradeId=tradeId.substring(0,36);
        api.placeEntry(tradeId,symbol,side,Decimals.fmt(qty),Decimals.fmt(stopQ));
        Position p=api.waitPosition(symbol,20_000L);
        double actualEntry=p.avgPrice>0?p.avgPrice:entry, actualQty=p.size;
        TradeState tr=new TradeState(); tr.tradeId=tradeId; tr.symbol=symbol; tr.side=side; tr.openedAtMs=System.currentTimeMillis();
        tr.entryPrice=actualEntry; tr.initialQty=actualQty; tr.currentQty=actualQty; tr.initialStop=stopQ.doubleValue(); tr.currentStop=stopQ.doubleValue();
        tr.riskDistance=Math.abs(actualEntry-tr.initialStop); tr.targetRiskUsdt=s.riskUsdt; tr.atr=sig.m15Atr; tr.takerFee=taker;
        tr.spreadAtEntry=spread; tr.costREst=costR; tr.state="OPEN"; tr.highWater=actualEntry; tr.lowWater=actualEntry;
        trades.put(symbol,tr); db.upsertTrade(tr); db.logSignal(symbol,"ENTRY","FILLED",sig,actualQty,costR);
        BotRuntime.entries++; log("LIVE ENTRY "+symbol+" "+side+" qty="+actualQty+" @ "+actualEntry+" SL="+tr.initialStop);
    }

    private void managePositions() {
        if(trades.isEmpty()||s.apiKey.isEmpty()||s.apiSecret.isEmpty())return;
        try {
            Map<String,Position> positions=api.openPositions(); tickers=api.getAllTickers();
            BotRuntime.openPositions=positions.size();
            try { BotRuntime.balance=api.walletBalanceUsdt(); } catch(Exception ignored){}
            for(String symbol:new ArrayList<>(trades.keySet())){
                TradeState tr=trades.get(symbol); Position p=positions.get(symbol); Instrument inst=instruments.get(symbol);
                if(p==null){
                    long closedAt = System.currentTimeMillis();
                    double[] tx = new double[]{0,0,0,0};
                    try { Thread.sleep(350); tx = api.transactionSummary(symbol, tr.openedAtMs, closedAt); } catch (Exception ignored) {}
                    db.closeTrade(tr, closedAt, tx[0], tx[1], tx[2], tx[3]);
                    log(String.format(Locale.US, "CLOSED %s net=%+.3f USDT (%+.3fR) fees=%.3f funding=%+.3f", symbol, tx[3], tr.targetRiskUsdt>0?tx[3]/tr.targetRiskUsdt:0, tx[1], tx[2]));
                    db.event("INFO","CLOSED","position absent",symbol,tr.tradeId); db.deleteTrade(symbol); trades.remove(symbol); continue;
                }
                if(p.positionIdx!=0){log("WARNING "+symbol+": Hedge mode, управление остановлено");continue;}
                tr.currentQty=p.size; Ticker tk=tickers.get(symbol); double mark=tk==null?p.markPrice:positive(tk.mark,tk.last,p.markPrice); if(mark<=0)continue;
                tr.highWater=Math.max(tr.highWater,mark); tr.lowWater=Math.min(tr.lowWater,mark);
                double r=priceR(tr,mark); maybeReduce(tr,inst,r); maybeBeTrail(tr,inst,r,mark); db.upsertTrade(tr);
            }
        } catch(Exception e){log("MANAGE ERROR: "+err(e));}
    }

    private void maybeReduce(TradeState tr,Instrument inst,double r)throws Exception{
        if(tr.reduced||inst==null)return;
        boolean fresh=tr.structureBreak&&(System.currentTimeMillis()-tr.structureBreakTimeMs)<=20*60_000L;
        String reason=null; if(r<=s.forceReduceR)reason="FORCE";else if(r<=s.reduceTriggerR&&fresh)reason="STRUCTURE"; if(reason==null)return;
        BigDecimal close=reduceQty(inst,tr.currentQty);if(close==null){log("REDUCE invalid qty "+tr.symbol);return;}
        String opposite="Buy".equals(tr.side)?"Sell":"Buy";
        api.reducePosition(tr.tradeId,tr.symbol,opposite,Decimals.fmt(close));
        Position p=api.waitReduced(tr.symbol,tr.currentQty,8_000L); tr.currentQty=p==null?0:p.size; tr.reduced=true; tr.state="REDUCED";
        BotRuntime.reductions++; db.event("INFO","REDUCE_75",reason+" r="+r+" close="+close,tr.symbol,tr.tradeId); log(String.format(Locale.US,"REDUCE %s %.3fR close=%s remain=%.8f",tr.symbol,r,Decimals.fmt(close),tr.currentQty));
    }

    private void maybeBeTrail(TradeState tr,Instrument inst,double r,double mark)throws Exception{
        if(inst==null)return;
        if(!tr.beArmed&&r>=s.beTriggerR){
            double offset=tr.entryPrice*(2*tr.takerFee)+tr.spreadAtEntry+2*inst.tickSize.doubleValue();
            double candidate="Buy".equals(tr.side)?tr.entryPrice+offset:tr.entryPrice-offset;
            if(stopImproves(tr,inst,candidate,mark)){
                BigDecimal q=stopPrice(inst,tr.side,candidate);api.setStop(tr.symbol,Decimals.fmt(q));tr.currentStop=q.doubleValue();tr.beArmed=true;tr.state="BE";log("BE "+tr.symbol+" stop="+q);
            }
        }

        if(r>=s.trailTriggerR){tr.trailing=true;tr.state="TRAILING";}
        if(!tr.trailing)return;

        double floorR=0.0;
        if(r>=3.0)floorR=2.25;
        else if(r>=2.5)floorR=2.0;
        else if(r>=2.0)floorR=1.0;

        double trailMult=r>=2.5?1.6:s.trailAtrMult;
        double candidate=tr.currentStop;
        if(tr.atr>0){
            candidate="Buy".equals(tr.side)?tr.highWater-trailMult*tr.atr:tr.lowWater+trailMult*tr.atr;
        }
        if(floorR>0&&tr.riskDistance>0){
            double floor="Buy".equals(tr.side)?tr.entryPrice+floorR*tr.riskDistance:tr.entryPrice-floorR*tr.riskDistance;
            candidate="Buy".equals(tr.side)?Math.max(candidate,floor):Math.min(candidate,floor);
        }

        if(stopImproves(tr,inst,candidate,mark)){
            BigDecimal q=stopPrice(inst,tr.side,candidate);api.setStop(tr.symbol,Decimals.fmt(q));tr.currentStop=q.doubleValue();
            log(String.format(Locale.US,"PROFIT LOCK %s %.3fR floor=%.2fR trail=%.1fATR stop=%s",tr.symbol,r,floorR,trailMult,Decimals.fmt(q)));
        }
    }

    private boolean stopImproves(TradeState tr,Instrument inst,double candidate,double mark){
        double min=inst.tickSize.doubleValue()*2;
        if("Buy".equals(tr.side))return candidate>tr.currentStop+min&&candidate<mark-inst.tickSize.doubleValue();
        return candidate<tr.currentStop-min&&candidate>mark+inst.tickSize.doubleValue();
    }

    private double priceR(TradeState tr,double price){if(tr.riskDistance<=0)return 0;return "Buy".equals(tr.side)?(price-tr.entryPrice)/tr.riskDistance:(tr.entryPrice-price)/tr.riskDistance;}

    private BigDecimal quantizeEntryQty(Instrument inst,double desired,double price){
        BigDecimal q=Decimals.floorStep(Decimals.bd(desired),inst.qtyStep);if(q.compareTo(inst.minQty)<0)return null;
        if(q.compareTo(inst.maxMarketQty)>0)q=Decimals.floorStep(inst.maxMarketQty,inst.qtyStep);
        if(q.multiply(Decimals.bd(price)).compareTo(inst.minNotional)<0)return null;
        BigDecimal remain=Decimals.ceilStep(q.multiply(Decimals.bd(1.0-s.reduceFraction)),inst.qtyStep);if(remain.compareTo(inst.minQty)<0)return null;return q;
    }

    private BigDecimal reduceQty(Instrument inst,double current){
        BigDecimal cur=Decimals.bd(current); BigDecimal remain=Decimals.ceilStep(cur.multiply(Decimals.bd(1.0-s.reduceFraction)),inst.qtyStep);
        if(remain.compareTo(inst.minQty)<0)remain=inst.minQty; BigDecimal close=Decimals.floorStep(cur.subtract(remain),inst.qtyStep);
        if(close.signum()<=0||close.compareTo(cur)>=0)return null;return close;
    }

    private BigDecimal stopPrice(Instrument inst,String side,double raw){return "Buy".equals(side)?Decimals.floorTick(raw,inst.tickSize):Decimals.ceilTick(raw,inst.tickSize);}
    private static double positive(double...x){for(double v:x)if(v>0)return v;return 0;}
    private static String err(Throwable e){String m=e==null?null:e.getMessage();return m==null||m.trim().isEmpty()?String.valueOf(e):e.getClass().getSimpleName()+": "+m;}
    private void log(String x){BotRuntime.log(x);db.event("INFO","LOG",x,null,null);}
    @Override public void close(){try{db.close();}catch(Exception ignored){}try{api.close();}catch(Exception ignored){}}
}