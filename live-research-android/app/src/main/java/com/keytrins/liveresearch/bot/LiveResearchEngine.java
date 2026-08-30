package com.keytrins.liveresearch.bot;

import android.content.Context;

import com.keytrins.liveresearch.BotRuntime;
import com.keytrins.liveresearch.SettingsStore;
import com.keytrins.liveresearch.model.Candle;
import com.keytrins.liveresearch.model.HedgeState;
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
    private static final double DOLLAR_LOCK_STEP_USDT = 0.50;
    private static final double DOLLAR_LOCK_LAG_USDT = 0.50;
    private static final double HEDGE_TRIGGER_ATR = 0.15;
    private static final double HEDGE_TRIGGER_R_BACKSTOP = -0.15;
    private static final double HEDGE_EMERGENCY_STOP_ATR = 0.20;
    private static final long HEDGE_UNKNOWN_WAIT_MS = 60_000L;
    private static final long HEDGE_RETRY_MS = 5_000L;
    private static final long HEDGE_OPEN_GRACE_MS = 8_000L;
    private static final long MANAGE_INTERVAL_MS = 1_000L;
    private static final long BALANCE_REFRESH_MS = 5_000L;

    private final SettingsStore.Snapshot s;
    private final BybitClient api;
    private final Db db;
    private final StrategyEngine strategy;
    private Map<String,Instrument> instruments = new HashMap<>();
    private Map<String,Ticker> tickers = new HashMap<>();
    private final Map<String,TradeState> trades;
    private final Map<String,HedgeState> hedges;
    private List<String> universe = new ArrayList<>();
    private long lastUniverseRefresh = 0, lastScanClose = -1, lastManage = 0, lastBalanceRefresh = 0;

    public LiveResearchEngine(Context context, SettingsStore.Snapshot s) {
        this.s = s;
        this.api = new BybitClient(s);
        this.db = new Db(context.getApplicationContext());
        this.strategy = new StrategyEngine(s);
        this.trades = db.openTrades();
        this.hedges = db.openHedges();
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
            int hedgeLegs=0, oneWayLegs=0;
            for (Position p : pos.values()) { if(p.positionIdx==0)oneWayLegs++; else hedgeLegs++; }
            return String.format(Locale.US, "API OK • balance=%.2f USDT • positions=%d • symbols=%d • time skew=%dms",
                    bal, pos.size(), inst, skew) + " • hedge legs="+hedgeLegs+" • one-way legs="+oneWayLegs;
        }
    }

    public void run(BooleanSupplier keepRunning, Consumer<String> notify) throws Exception {
        refreshUniverse(true);
        if (!s.apiKey.isEmpty() && !s.apiSecret.isEmpty()) {
            try { BotRuntime.balance = api.walletBalanceUsdt(); lastBalanceRefresh = System.currentTimeMillis(); } catch (Exception e) { log("Баланс недоступен: "+err(e)); }
        }
        BotRuntime.status = s.live ? "LIVE • работает" : "OBSERVE • работает";
        notify.accept(BotRuntime.status);
        log("Universe="+universe.size()+" • риск="+s.riskUsdt+" USDT • "+(s.live?"LIVE":"OBSERVE"));

        while (keepRunning.getAsBoolean()) {
            long now = System.currentTimeMillis();
            try {
                if (now - lastUniverseRefresh >= 60 * 60_000L) refreshUniverse(false);
                if (now - lastManage >= MANAGE_INTERVAL_MS) {
                    managePositions();
                    lastManage = System.currentTimeMillis();
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
            Thread.sleep(200L);
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
            } catch (Exception e) {
                log("Positions read fail: "+err(e));
                if (s.live) return;
            }
        }

        List<String> symbols = new ArrayList<>(universe);
        for (String x : trades.keySet()) if (!symbols.contains(x)) symbols.add(x);
        int signalCount=0;
        for (String symbol : symbols) {
            Instrument inst=instruments.get(symbol);
            try {
                if(inst==null) continue;
                List<Candle> h1=api.getKlines(symbol,"60",260);
                List<Candle> m15=api.getKlines(symbol,"15",160);
                TradeState tr=trades.get(symbol);
                if(tr!=null){
                    StrategyEngine.BreakResult br=strategy.structuralBreak(tr.side,m15);
                    tr.structureBreak=br.broken; tr.structureBreakTimeMs=br.broken?br.endMs:0;
                    if(br.atr>0&&!Double.isNaN(br.atr))tr.atr=br.atr;
                    db.upsertTrade(tr);
                }
                if(hasAnyPosition(exchangePositions,symbol)||trades.containsKey(symbol)) continue;
                SignalResult sr=strategy.buildSignal(symbol,h1,m15);
                if(sr.signal==null) continue;
                signalCount++; BotRuntime.signals++;
                evaluateEntry(sr.signal, tickers.get(symbol));
            } catch(Exception e){
                db.event("ERROR","SCAN",e.toString(),symbol,null);
            } finally {
                // continue/return paths inside symbol analysis still execute this protection pass.
                // No concurrent Bybit calls are introduced: scan and protection remain serialized.
                long now=System.currentTimeMillis();
                if(now-lastManage>=MANAGE_INTERVAL_MS){
                    managePositions();
                    lastManage=System.currentTimeMillis();
                }
            }
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

        HedgeState staleHedge=hedges.remove(symbol); if(staleHedge!=null) db.deleteHedge(symbol);
        try { api.switchToHedgeMode(symbol); }
        catch(Exception e){ db.logSignal(symbol,"REJECT","HEDGE_MODE_FAIL",sig,q,costR); log("ENTRY BLOCK "+symbol+": Hedge Mode недоступен: "+err(e)); return; }
        int primaryIdx="Buy".equals(side)?1:2;
        String tradeId=("LRA_"+symbol+"_"+(System.currentTimeMillis()/1000L)); if(tradeId.length()>36)tradeId=tradeId.substring(0,36);
        api.placeEntry(tradeId,symbol,side,Decimals.fmt(qty),Decimals.fmt(stopQ),primaryIdx);
        Position p=api.waitPosition(symbol,primaryIdx,20_000L);
        double actualEntry=p.avgPrice>0?p.avgPrice:entry, actualQty=p.size;
        TradeState tr=new TradeState(); tr.tradeId=tradeId; tr.symbol=symbol; tr.side=side; tr.openedAtMs=System.currentTimeMillis();
        tr.entryPrice=actualEntry; tr.initialQty=actualQty; tr.currentQty=actualQty; tr.initialStop=stopQ.doubleValue(); tr.currentStop=stopQ.doubleValue();
        tr.riskDistance=Math.abs(actualEntry-tr.initialStop); tr.targetRiskUsdt=s.riskUsdt; tr.atr=sig.m15Atr; tr.entryAtr=sig.m15Atr; tr.takerFee=taker;
        tr.spreadAtEntry=spread; tr.costREst=costR; tr.state="OPEN"; tr.highWater=actualEntry; tr.lowWater=actualEntry;
        tr.peakProfitUsdt=0; tr.protectedProfitUsdt=0;
        trades.put(symbol,tr); db.upsertTrade(tr); db.logSignal(symbol,"ENTRY","FILLED",sig,actualQty,costR);
        BotRuntime.entries++; log("LIVE ENTRY "+symbol+" "+side+" idx="+primaryIdx+" qty="+actualQty+" @ "+actualEntry+" SL="+tr.initialStop);
    }

    private void managePositions() {
        if(trades.isEmpty()||s.apiKey.isEmpty()||s.apiSecret.isEmpty())return;
        try {
            Map<String,Position> positions=api.openPositions(); tickers=api.getAllTickers();
            BotRuntime.openPositions=positions.size();
            long now=System.currentTimeMillis();
            if(now-lastBalanceRefresh>=BALANCE_REFRESH_MS){
                try { BotRuntime.balance=api.walletBalanceUsdt(); lastBalanceRefresh=now; } catch(Exception ignored){}
            }
            for(String symbol:new ArrayList<>(trades.keySet())){
                TradeState tr=trades.get(symbol); Instrument inst=instruments.get(symbol); Ticker tk=tickers.get(symbol);
                Position primary=findPositionBySide(positions,symbol,tr.side);
                HedgeState hedge=hedges.get(symbol);

                if(primary!=null){
                    tr.currentQty=primary.size;
                    double mark=tk==null?primary.markPrice:positive(tk.mark,tk.last,primary.markPrice); if(mark<=0)continue;
                    tr.highWater=Math.max(tr.highWater,mark); tr.lowWater=Math.min(tr.lowWater,mark);
                    double markGross="Buy".equals(tr.side)?(mark-tr.entryPrice)*tr.currentQty:(tr.entryPrice-mark)*tr.currentQty;
                    double observedGross=primary.unrealisedPnl;
                    if(Double.isNaN(observedGross)||Double.isInfinite(observedGross))observedGross=markGross;
                    if(markGross>observedGross)observedGross=markGross;
                    if(observedGross>tr.peakProfitUsdt)tr.peakProfitUsdt=observedGross;
                    double legacyPeak="Buy".equals(tr.side)
                            ? Math.max(0.0,tr.highWater-tr.entryPrice)*tr.currentQty
                            : Math.max(0.0,tr.entryPrice-tr.lowWater)*tr.currentQty;
                    if(legacyPeak>tr.peakProfitUsdt)tr.peakProfitUsdt=legacyPeak;
                    if(inst!=null&&primary.stopLoss>0&&stopIsMoreProtective(tr,primary.stopLoss))tr.currentStop=primary.stopLoss;
                    if(inst!=null&&tr.currentStop>0){
                        double existingProtection=estimatedProtectedProfitAtStop(tr,inst,tr.currentStop);
                        if(existingProtection>tr.protectedProfitUsdt)tr.protectedProfitUsdt=existingProtection;
                    }

                    String expectedHedgeSide="Buy".equals(tr.side)?"Sell":"Buy";
                    int expectedHedgeIdx="Buy".equals(expectedHedgeSide)?1:2;
                    Position exchangeHedge=findPosition(positions,symbol,expectedHedgeIdx,expectedHedgeSide);
                    if(exchangeHedge!=null){
                        if(hedge==null || !tr.tradeId.equals(hedge.primaryTradeId) || !hedgeIsPotentiallyAlive(hedge)){
                            hedge=recoverHedgeState(tr,exchangeHedge);
                        }
                    } else if(hedgeAttemptAllowed(tr,hedge)){
                        maybeOpenHedge(tr,inst,primary,tk,mark);
                        hedge=hedges.get(symbol);
                    }
                    if(exchangeHedge!=null && hedge!=null && primaryEstimatedNet(tr,inst,mark)>0){
                        maybeCloseHedgeOnPrimaryRecovery(tr,hedge,inst,exchangeHedge);
                    }

                    double r=priceR(tr,mark);
                    boolean riskExit=maybeReduce(tr,inst,r,primary.positionIdx);
                    if(!riskExit)maybeBeTrail(tr,inst,r,mark,primary.positionIdx);
                } else {
                    tr.currentQty=0;
                    if(hedge!=null && hedgeIsPotentiallyAlive(hedge)) tr.state="PRIMARY_CLOSED_HEDGE_RUNNING";
                    else tr.state="PRIMARY_CLOSED";
                }

                hedge=hedges.get(symbol);
                if(hedge!=null){
                    Position hp=findPosition(positions,symbol,hedge.positionIdx,hedge.side);
                    manageHedge(tr,hedge,inst,tk,hp);
                }

                boolean primaryAlive=findPositionBySide(positions,symbol,tr.side)!=null;
                HedgeState latest=hedges.get(symbol);
                Position hedgeAlive=latest==null?null:findPosition(positions,symbol,latest.positionIdx,latest.side);
                boolean waitUnknown=latest!=null && ("PENDING".equals(latest.state)||"OPEN_UNKNOWN".equals(latest.state)||"CLOSE_UNKNOWN".equals(latest.state))
                        && System.currentTimeMillis()-latest.lastAttemptMs < HEDGE_UNKNOWN_WAIT_MS;
                if(!primaryAlive && hedgeAlive==null && !waitUnknown){
                    finalizeCycle(tr);
                    continue;
                }
                db.upsertTrade(tr);
            }
        } catch(Exception e){log("MANAGE ERROR: "+err(e));}
    }

    private void maybeOpenHedge(TradeState tr, Instrument inst, Position primary, Ticker tk, double mark) throws Exception {
        if(inst==null||primary==null||tr.currentQty<=0)return;
        double triggerAtr=tr.entryAtr>0?tr.entryAtr:tr.atr; if(!(triggerAtr>0))return;
        double adverse="Buy".equals(tr.side)?tr.entryPrice-mark:mark-tr.entryPrice;
        double rNow=priceR(tr,mark);
        boolean atrCross=adverse+1e-12 >= HEDGE_TRIGGER_ATR*triggerAtr;
        boolean rBackstop=rNow<=HEDGE_TRIGGER_R_BACKSTOP;
        if(!atrCross&&!rBackstop)return;
        db.event("INFO","HEDGE_TRIGGER","adverse="+adverse+" entryATR="+triggerAtr+" r="+rNow,tr.symbol,tr.tradeId);
        if(primary.positionIdx==0){
            HedgeState h=new HedgeState(); h.primaryTradeId=tr.tradeId; h.symbol=tr.symbol; h.side="Buy".equals(tr.side)?"Sell":"Buy";
            h.state="UNAVAILABLE_ONE_WAY"; h.lastAttemptMs=System.currentTimeMillis(); hedges.put(tr.symbol,h); db.upsertHedge(h);
            log("HEDGE unavailable for legacy One-Way position "+tr.symbol+"; new entries use Hedge Mode");
            return;
        }

        BigDecimal qty=Decimals.floorStep(Decimals.bd(primary.size),inst.qtyStep);
        if(qty.signum()<=0||qty.compareTo(inst.minQty)<0)return;
        String hedgeSide="Buy".equals(tr.side)?"Sell":"Buy";
        int hedgeIdx="Buy".equals(hedgeSide)?1:2;
        double entryRef="Buy".equals(hedgeSide)?positive(tk==null?0:tk.ask,tk==null?0:tk.last,mark):positive(tk==null?0:tk.bid,tk==null?0:tk.last,mark);
        if(entryRef<=0)return;
        if(qty.multiply(Decimals.bd(entryRef)).compareTo(inst.minNotional)<0)return;
        double rawStop="Buy".equals(hedgeSide)?entryRef-HEDGE_EMERGENCY_STOP_ATR*triggerAtr:entryRef+HEDGE_EMERGENCY_STOP_ATR*triggerAtr;
        BigDecimal stopQ=stopPrice(inst,hedgeSide,rawStop);

        HedgeState h=new HedgeState();
        h.primaryTradeId=tr.tradeId; h.symbol=tr.symbol; h.side=hedgeSide; h.positionIdx=hedgeIdx; h.state="PENDING";
        h.openedAtMs=System.currentTimeMillis(); h.lastAttemptMs=h.openedAtMs; h.entryPrice=entryRef; h.initialQty=qty.doubleValue(); h.currentQty=qty.doubleValue();
        h.initialStop=stopQ.doubleValue(); h.currentStop=stopQ.doubleValue(); h.atr=triggerAtr; h.takerFee=tr.takerFee; h.spreadAtEntry=tr.spreadAtEntry;
        h.highWater=entryRef; h.lowWater=entryRef;
        hedges.put(tr.symbol,h); db.upsertHedge(h);

        String hedgeId=("HDG_"+tr.symbol+"_"+(h.openedAtMs/1000L)); if(hedgeId.length()>36)hedgeId=hedgeId.substring(0,36);
        try {
            api.placeEntry(hedgeId,tr.symbol,hedgeSide,Decimals.fmt(qty),Decimals.fmt(stopQ),hedgeIdx);
            Position hp=api.waitPosition(tr.symbol,hedgeIdx,12_000L);
            h.entryPrice=hp.avgPrice>0?hp.avgPrice:entryRef; h.initialQty=hp.size; h.currentQty=hp.size;
            h.currentStop=hp.stopLoss>0?hp.stopLoss:stopQ.doubleValue(); h.initialStop=h.currentStop;
            h.highWater=h.entryPrice; h.lowWater=h.entryPrice; h.state="OPEN";
            db.upsertHedge(h);
            db.event("INFO","HEDGE_OPEN","trigger=-0.15ATR qty="+Decimals.fmt(qty)+" idx="+hedgeIdx,tr.symbol,tr.tradeId);
            log(String.format(Locale.US,"HEDGE OPEN %s %s idx=%d qty=%s @ %.8f emergencySL=%.8f trigger=%.3fATR",
                    tr.symbol,hedgeSide,hedgeIdx,Decimals.fmt(qty),h.entryPrice,h.currentStop,adverse/tr.atr));
        } catch(BybitClient.ApiException e){
            h.state="REJECTED"; db.upsertHedge(h);
            log("HEDGE REJECTED "+tr.symbol+": "+err(e));
        } catch(Exception e){
            h.state="OPEN_UNKNOWN"; db.upsertHedge(h);
            try {
                Position hp=api.position(tr.symbol,hedgeIdx);
                if(hp!=null){ h.entryPrice=hp.avgPrice; h.initialQty=hp.size; h.currentQty=hp.size; h.currentStop=hp.stopLoss; h.state="OPEN"; db.upsertHedge(h); }
            } catch(Exception ignored){}
            log("HEDGE OPEN UNKNOWN "+tr.symbol+": "+err(e)+"; blind retry disabled");
        }
    }

    private void maybeCloseHedgeOnPrimaryRecovery(TradeState tr,HedgeState h,Instrument inst,Position hp) throws Exception {
        if(h==null||inst==null||hp==null||hp.size<=0)return;
        if("CLOSE_PENDING".equals(h.state)||"CLOSE_UNKNOWN".equals(h.state))return;
        BigDecimal close=Decimals.floorStep(Decimals.bd(hp.size),inst.qtyStep); if(close.signum()<=0)return;
        String closeSide="Buy".equals(h.side)?"Sell":"Buy";
        h.state="CLOSE_PENDING"; h.lastAttemptMs=System.currentTimeMillis(); db.upsertHedge(h);
        try {
            api.reducePosition("HREC"+System.currentTimeMillis(),h.symbol,closeSide,Decimals.fmt(close),h.positionIdx);
            Position after=api.waitReduced(h.symbol,h.positionIdx,hp.size,8_000L);
            h.currentQty=after==null?0:after.size; h.state=after==null?"CLOSED_RECOVERY":"OPEN"; db.upsertHedge(h);
            db.event("INFO","HEDGE_RECOVERY_CLOSE","primary net positive; close="+Decimals.fmt(close),h.symbol,tr.tradeId);
            log("HEDGE CLOSE ON PRIMARY + "+h.symbol+" close="+Decimals.fmt(close)+" remain="+h.currentQty);
        } catch(Exception e){
            h.state="CLOSE_UNKNOWN"; db.upsertHedge(h);
            log("HEDGE RECOVERY CLOSE UNKNOWN "+h.symbol+": "+err(e)+"; blind retry disabled, emergency SL remains");
        }
    }

    private void manageHedge(TradeState tr, HedgeState h, Instrument inst, Ticker tk, Position hp) throws Exception {
        if(h==null)return;
        if(hp==null){
            long age=System.currentTimeMillis()-h.lastAttemptMs;
            if(age < HEDGE_OPEN_GRACE_MS)return;
            if(("PENDING".equals(h.state)||"OPEN_UNKNOWN".equals(h.state)||"CLOSE_UNKNOWN".equals(h.state)) && age < HEDGE_UNKNOWN_WAIT_MS)return;
            if(!"UNAVAILABLE_ONE_WAY".equals(h.state)&&!"REJECTED".equals(h.state)){
                h.currentQty=0; if(!"CLOSED_RECOVERY".equals(h.state))h.state="CLOSED"; db.upsertHedge(h);
                db.event("INFO","HEDGE_CLOSED","exchange hedge position absent",h.symbol,tr.tradeId);
                log(String.format(Locale.US,"HEDGE CLOSED %s peak=$%.3f protected~$%.3f",h.symbol,h.peakProfitUsdt,h.protectedProfitUsdt));
            }
            return;
        }
        if(inst==null)return;
        if(!"BE".equals(h.state)&&!"DOLLAR_LOCK".equals(h.state)&&!"CLOSE_PENDING".equals(h.state)&&!"CLOSE_UNKNOWN".equals(h.state))h.state="OPEN";
        h.positionIdx=hp.positionIdx; h.currentQty=hp.size;
        if(h.entryPrice<=0)h.entryPrice=hp.avgPrice; if(h.initialQty<=0)h.initialQty=hp.size;
        double mark=tk==null?hp.markPrice:positive(tk.mark,tk.last,hp.markPrice); if(mark<=0)return;
        h.highWater=Math.max(h.highWater>0?h.highWater:h.entryPrice,mark); h.lowWater=h.lowWater>0?Math.min(h.lowWater,mark):mark;
        double markGross="Buy".equals(h.side)?(mark-h.entryPrice)*h.currentQty:(h.entryPrice-mark)*h.currentQty;
        double observed=hp.unrealisedPnl;
        if(Double.isNaN(observed)||Double.isInfinite(observed))observed=markGross;
        if(markGross>observed)observed=markGross;
        if(observed>h.peakProfitUsdt)h.peakProfitUsdt=observed;
        if(hp.stopLoss>0 && hedgeStopIsMoreProtective(h,hp.stopLoss))h.currentStop=hp.stopLoss;
        if(h.currentStop>0){ double ep=estimatedHedgeProtectedAtStop(h,inst,h.currentStop); if(ep>h.protectedProfitUsdt)h.protectedProfitUsdt=ep; }

        double steps=Math.floor((Math.max(0,h.peakProfitUsdt)+1e-9)/DOLLAR_LOCK_STEP_USDT);
        if(steps>=1.0 && h.currentQty>0){
            double protectedUsd=Math.max(0.0,steps*DOLLAR_LOCK_STEP_USDT-DOLLAR_LOCK_LAG_USDT);
            double costPerUnit=h.entryPrice*(2*h.takerFee)+h.spreadAtEntry+2*inst.tickSize.doubleValue();
            double move=protectedUsd/h.currentQty+costPerUnit;
            double candidate="Buy".equals(h.side)?h.entryPrice+move:h.entryPrice-move;
            if(hedgeStopImproves(h,inst,candidate,mark)){
                BigDecimal q=stopPrice(inst,h.side,candidate);
                api.setStop(h.symbol,Decimals.fmt(q),h.positionIdx);
                h.currentStop=q.doubleValue(); h.state=protectedUsd>0?"DOLLAR_LOCK":"BE";
                double actual=estimatedHedgeProtectedAtStop(h,inst,h.currentStop); if(actual>h.protectedProfitUsdt)h.protectedProfitUsdt=actual;
                log(String.format(Locale.US,"HEDGE LOCK %s peak=$%.3f target=$%.2f protected~$%.3f stop=%s",
                        h.symbol,h.peakProfitUsdt,protectedUsd,h.protectedProfitUsdt,Decimals.fmt(q)));
            } else if(hedgeFloorAlreadyCrossed(h,candidate,mark)){
                double netNow=estimatedHedgeNetAtMark(h,inst,mark);
                if(netNow<=protectedUsd+0.05){
                    marketCloseForMissedHedgeLock(tr,h,inst,hp,protectedUsd,netNow);
                    return;
                }
            }
        }
        db.upsertHedge(h);
    }

    private double primaryEstimatedNet(TradeState tr,Instrument inst,double mark){
        if(inst==null||tr.currentQty<=0)return -1;
        double gross="Buy".equals(tr.side)?(mark-tr.entryPrice)*tr.currentQty:(tr.entryPrice-mark)*tr.currentQty;
        double costPerUnit=tr.entryPrice*(2*tr.takerFee)+tr.spreadAtEntry+2*inst.tickSize.doubleValue();
        return gross-costPerUnit*tr.currentQty;
    }

    private void finalizeCycle(TradeState tr) {
        long closedAt=System.currentTimeMillis(); double[] tx=new double[]{0,0,0,0};
        try { Thread.sleep(350); tx=api.transactionSummary(tr.symbol,tr.openedAtMs,closedAt); } catch(Exception ignored){}
        db.closeTrade(tr,closedAt,tx[0],tx[1],tx[2],tx[3]);
        HedgeState h=hedges.get(tr.symbol);
        log(String.format(Locale.US,"CYCLE CLOSED %s net=%+.3f USDT (%+.3fR) fees=%.3f funding=%+.3f primaryPeak=%+.2f hedgePeak=%+.2f",
                tr.symbol,tx[3],tr.targetRiskUsdt>0?tx[3]/tr.targetRiskUsdt:0,tx[1],tx[2],tr.peakProfitUsdt,h==null?0:h.peakProfitUsdt));
        db.event("INFO","CLOSED","primary+hedge flat",tr.symbol,tr.tradeId);
        db.deleteHedge(tr.symbol); hedges.remove(tr.symbol); db.deleteTrade(tr.symbol); trades.remove(tr.symbol);
    }

    private static boolean hasAnyPosition(Map<String,Position> positions,String symbol){
        for(Position p:positions.values())if(symbol.equals(p.symbol))return true; return false;
    }

    private static Position findPositionBySide(Map<String,Position> positions,String symbol,String side){
        for(Position p:positions.values())if(symbol.equals(p.symbol)&&side.equals(p.side))return p; return null;
    }

    private static Position findPosition(Map<String,Position> positions,String symbol,int idx,String side){
        for(Position p:positions.values())if(symbol.equals(p.symbol)&&(idx<0||p.positionIdx==idx)&&(side==null||side.equals(p.side)))return p; return null;
    }

    private static boolean hedgeIsPotentiallyAlive(HedgeState h){
        return h!=null && !("CLOSED".equals(h.state)||"CLOSED_RECOVERY".equals(h.state)||"REJECTED".equals(h.state)||"UNAVAILABLE_ONE_WAY".equals(h.state));
    }

    private boolean hedgeAttemptAllowed(TradeState tr,HedgeState h){
        if(h==null)return true;
        if(!tr.tradeId.equals(h.primaryTradeId))return true;
        String st=h.state==null?"":h.state;
        if("PENDING".equals(st)||"OPEN_UNKNOWN".equals(st)||"CLOSE_PENDING".equals(st)||"CLOSE_UNKNOWN".equals(st))return false;
        if("UNAVAILABLE_ONE_WAY".equals(st))return false;
        if("OPEN".equals(st)||"BE".equals(st)||"DOLLAR_LOCK".equals(st)||"OPEN_RECOVERED".equals(st))return false;
        return System.currentTimeMillis()-h.lastAttemptMs>=HEDGE_RETRY_MS;
    }

    private HedgeState recoverHedgeState(TradeState tr,Position hp){
        HedgeState h=new HedgeState();
        h.primaryTradeId=tr.tradeId; h.symbol=tr.symbol; h.side=hp.side; h.positionIdx=hp.positionIdx; h.state="OPEN_RECOVERED";
        h.openedAtMs=System.currentTimeMillis(); h.lastAttemptMs=h.openedAtMs; h.entryPrice=hp.avgPrice; h.initialQty=hp.size; h.currentQty=hp.size;
        h.initialStop=hp.stopLoss; h.currentStop=hp.stopLoss; h.atr=tr.entryAtr>0?tr.entryAtr:tr.atr; h.takerFee=tr.takerFee; h.spreadAtEntry=tr.spreadAtEntry;
        h.highWater=hp.avgPrice; h.lowWater=hp.avgPrice; hedges.put(tr.symbol,h); db.upsertHedge(h);
        db.event("WARN","HEDGE_RECOVERED","exchange hedge existed without live local state idx="+hp.positionIdx,tr.symbol,tr.tradeId);
        log("HEDGE RECOVERED "+tr.symbol+" idx="+hp.positionIdx+" qty="+hp.size);
        return h;
    }

    private boolean hedgeStopImproves(HedgeState h,Instrument inst,double candidate,double mark){
        double min=inst.tickSize.doubleValue()*2;
        if("Buy".equals(h.side))return candidate>h.currentStop+min&&candidate<mark-inst.tickSize.doubleValue();
        return candidate<h.currentStop-min&&candidate>mark+inst.tickSize.doubleValue();
    }

    private boolean hedgeStopIsMoreProtective(HedgeState h,double stop){
        if(stop<=0)return false; if(h.currentStop<=0)return true; return "Buy".equals(h.side)?stop>h.currentStop:stop<h.currentStop;
    }

    private double estimatedHedgeProtectedAtStop(HedgeState h,Instrument inst,double stop){
        if(h.currentQty<=0||stop<=0)return 0;
        double gross="Buy".equals(h.side)?(stop-h.entryPrice)*h.currentQty:(h.entryPrice-stop)*h.currentQty;
        double costPerUnit=h.entryPrice*(2*h.takerFee)+h.spreadAtEntry+2*inst.tickSize.doubleValue();
        return Math.max(0.0,gross-costPerUnit*h.currentQty);
    }

    private boolean dollarFloorAlreadyCrossed(TradeState tr,double candidate,double mark){
        return "Buy".equals(tr.side)?mark<=candidate:mark>=candidate;
    }

    private boolean hedgeFloorAlreadyCrossed(HedgeState h,double candidate,double mark){
        return "Buy".equals(h.side)?mark<=candidate:mark>=candidate;
    }

    private double estimatedHedgeNetAtMark(HedgeState h,Instrument inst,double mark){
        if(h.currentQty<=0)return 0;
        double gross="Buy".equals(h.side)?(mark-h.entryPrice)*h.currentQty:(h.entryPrice-mark)*h.currentQty;
        double costPerUnit=h.entryPrice*(2*h.takerFee)+h.spreadAtEntry+2*inst.tickSize.doubleValue();
        return gross-costPerUnit*h.currentQty;
    }

    private void marketCloseForMissedPrimaryLock(TradeState tr,Instrument inst,int positionIdx,double peak,double target,double netNow)throws Exception{
        BigDecimal close=fullCloseQty(inst,tr.currentQty); if(close==null)return;
        String opposite="Buy".equals(tr.side)?"Sell":"Buy"; double before=tr.currentQty;
        api.reducePosition("PLC"+System.currentTimeMillis(),tr.symbol,opposite,Decimals.fmt(close),positionIdx);
        Position after=api.waitReduced(tr.symbol,positionIdx,before,8_000L); tr.currentQty=after==null?0:after.size;
        tr.state=after==null?"PROFIT_LOCK_CATCHUP_EXIT":"PROFIT_LOCK_CATCHUP"; tr.beArmed=true;
        db.event("WARN","PROFIT_LOCK_CATCHUP","peak="+peak+" target="+target+" netNow="+netNow,tr.symbol,tr.tradeId);
        log(String.format(Locale.US,"PROFIT LOCK CATCH-UP %s peak=$%.2f target=$%.2f netNow=$%.2f remain=%.8f",tr.symbol,peak,target,netNow,tr.currentQty));
    }

    private void marketCloseForMissedHedgeLock(TradeState tr,HedgeState h,Instrument inst,Position hp,double target,double netNow)throws Exception{
        BigDecimal close=Decimals.floorStep(Decimals.bd(hp.size),inst.qtyStep); if(close.signum()<=0)return;
        String closeSide="Buy".equals(h.side)?"Sell":"Buy"; double before=hp.size;
        api.reducePosition("HLC"+System.currentTimeMillis(),h.symbol,closeSide,Decimals.fmt(close),h.positionIdx);
        Position after=api.waitReduced(h.symbol,h.positionIdx,before,8_000L); h.currentQty=after==null?0:after.size; h.state=after==null?"CLOSED_LOCK_CATCHUP":"OPEN"; h.lastAttemptMs=System.currentTimeMillis(); db.upsertHedge(h);
        db.event("WARN","HEDGE_LOCK_CATCHUP","target="+target+" netNow="+netNow,h.symbol,tr.tradeId);
        log(String.format(Locale.US,"HEDGE LOCK CATCH-UP %s target=$%.2f netNow=$%.2f remain=%.8f",h.symbol,target,netNow,h.currentQty));
    }

    private boolean maybeReduce(TradeState tr,Instrument inst,double r,int positionIdx)throws Exception{
        if(inst==null||tr.currentQty<=0)return false;
        String opposite="Buy".equals(tr.side)?"Sell":"Buy";

        if(r<=s.forceReduceR){
            BigDecimal close=fullCloseQty(inst,tr.currentQty);
            if(close==null){log("FORCE EXIT invalid qty "+tr.symbol);return true;}
            double before=tr.currentQty;
            api.reducePosition("FX"+System.currentTimeMillis(),tr.symbol,opposite,Decimals.fmt(close),positionIdx);
            Position p=api.waitReduced(tr.symbol,positionIdx,before,8_000L);
            tr.currentQty=p==null?0:p.size;
            tr.reduced=true;
            tr.state=p==null?"EXITING":"FORCE_EXIT";
            BotRuntime.reductions++;
            db.event("INFO","FORCE_EXIT_100","r="+r+" close="+close,tr.symbol,tr.tradeId);
            log(String.format(Locale.US,"FORCE EXIT %s %.3fR close=%s remain=%.8f",tr.symbol,r,Decimals.fmt(close),tr.currentQty));
            return true;
        }

        if(tr.reduced)return false;
        boolean fresh=tr.structureBreak&&(System.currentTimeMillis()-tr.structureBreakTimeMs)<=20*60_000L;
        if(r>s.reduceTriggerR||!fresh)return false;

        BigDecimal close=reduceQty(inst,tr.currentQty);
        if(close==null){log("REDUCE invalid qty "+tr.symbol);return false;}
        double before=tr.currentQty;
        api.reducePosition("R85"+System.currentTimeMillis(),tr.symbol,opposite,Decimals.fmt(close),positionIdx);
        Position p=api.waitReduced(tr.symbol,positionIdx,before,8_000L);
        tr.currentQty=p==null?0:p.size;
        tr.reduced=true;
        tr.state=p==null?"EXITING":"REDUCED_85";
        BotRuntime.reductions++;
        db.event("INFO","REDUCE_85","STRUCTURE r="+r+" close="+close,tr.symbol,tr.tradeId);
        log(String.format(Locale.US,"REDUCE85 %s %.3fR close=%s remain=%.8f",tr.symbol,r,Decimals.fmt(close),tr.currentQty));
        return p==null;
    }

    private void maybeBeTrail(TradeState tr,Instrument inst,double r,double mark,int positionIdx)throws Exception{
        if(inst==null||tr.currentQty<=0)return;

        // Primary profit protection: a persisted observed PnL peak advances in $0.50 steps.
        // +$0.50 => BE+costs, +$1.00 => protect about +$0.50, +$1.50 => about +$1.00, etc.
        // peakProfitUsdt is monotonic and survives process/app restart through SQLite.
        double qty=tr.currentQty;
        double peakGrossUsd=Math.max(0.0,tr.peakProfitUsdt);
        double steps=Math.floor((peakGrossUsd+1e-9)/DOLLAR_LOCK_STEP_USDT);
        if(steps>=1.0){
            double protectedUsd=Math.max(0.0,steps*DOLLAR_LOCK_STEP_USDT-DOLLAR_LOCK_LAG_USDT);
            double costPerUnit=tr.entryPrice*(2*tr.takerFee)+tr.spreadAtEntry+2*inst.tickSize.doubleValue();
            double movePerUnit=protectedUsd/qty+costPerUnit;
            double dollarCandidate="Buy".equals(tr.side)?tr.entryPrice+movePerUnit:tr.entryPrice-movePerUnit;
            if(stopImproves(tr,inst,dollarCandidate,mark)){
                BigDecimal q=stopPrice(inst,tr.side,dollarCandidate);
                api.setStop(tr.symbol,Decimals.fmt(q),positionIdx);
                tr.currentStop=q.doubleValue();
                tr.beArmed=true;
                tr.state=protectedUsd>0?"DOLLAR_LOCK":"BE";
                double actualProtected=estimatedProtectedProfitAtStop(tr,inst,tr.currentStop);
                if(actualProtected>tr.protectedProfitUsdt)tr.protectedProfitUsdt=actualProtected;
                log(String.format(Locale.US,
                        "DOLLAR LOCK %s peak=$%.3f target=$%.2f protected~$%.3f stop=%s",
                        tr.symbol,peakGrossUsd,protectedUsd,tr.protectedProfitUsdt,Decimals.fmt(q)));
            } else if(dollarFloorAlreadyCrossed(tr,dollarCandidate,mark)){
                double netNow=primaryEstimatedNet(tr,inst,mark);
                if(netNow<=protectedUsd+0.05){
                    marketCloseForMissedPrimaryLock(tr,inst,positionIdx,peakGrossUsd,protectedUsd,netNow);
                    return;
                }
            }
        }

        // Existing high-R ATR/R-floor logic remains only as an additional protection layer.
        // stopImproves() prevents either layer from loosening the exchange stop.
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
            BigDecimal q=stopPrice(inst,tr.side,candidate);
            api.setStop(tr.symbol,Decimals.fmt(q),positionIdx);
            tr.currentStop=q.doubleValue();
            double actualProtected=estimatedProtectedProfitAtStop(tr,inst,tr.currentStop);
            if(actualProtected>tr.protectedProfitUsdt)tr.protectedProfitUsdt=actualProtected;
            log(String.format(Locale.US,"PROFIT LOCK %s %.3fR floor=%.2fR trail=%.1fATR protected~$%.3f stop=%s",
                    tr.symbol,r,floorR,trailMult,tr.protectedProfitUsdt,Decimals.fmt(q)));
        }
    }

    private boolean stopImproves(TradeState tr,Instrument inst,double candidate,double mark){
        double min=inst.tickSize.doubleValue()*2;
        if("Buy".equals(tr.side))return candidate>tr.currentStop+min&&candidate<mark-inst.tickSize.doubleValue();
        return candidate<tr.currentStop-min&&candidate>mark+inst.tickSize.doubleValue();
    }

    private boolean stopIsMoreProtective(TradeState tr,double exchangeStop){
        if(exchangeStop<=0)return false;
        if(tr.currentStop<=0)return true;
        return "Buy".equals(tr.side)?exchangeStop>tr.currentStop:exchangeStop<tr.currentStop;
    }

    private double estimatedProtectedProfitAtStop(TradeState tr,Instrument inst,double stop){
        if(inst==null||tr.currentQty<=0||stop<=0)return 0;
        double gross="Buy".equals(tr.side)?(stop-tr.entryPrice)*tr.currentQty:(tr.entryPrice-stop)*tr.currentQty;
        double costPerUnit=tr.entryPrice*(2*tr.takerFee)+tr.spreadAtEntry+2*inst.tickSize.doubleValue();
        return Math.max(0.0,gross-costPerUnit*tr.currentQty);
    }

    private double priceR(TradeState tr,double price){if(tr.riskDistance<=0)return 0;return "Buy".equals(tr.side)?(price-tr.entryPrice)/tr.riskDistance:(tr.entryPrice-price)/tr.riskDistance;}

    private BigDecimal quantizeEntryQty(Instrument inst,double desired,double price){
        BigDecimal q=Decimals.floorStep(Decimals.bd(desired),inst.qtyStep);if(q.compareTo(inst.minQty)<0)return null;
        if(q.compareTo(inst.maxMarketQty)>0)q=Decimals.floorStep(inst.maxMarketQty,inst.qtyStep);
        if(q.multiply(Decimals.bd(price)).compareTo(inst.minNotional)<0)return null;
        return q;
    }

    private BigDecimal reduceQty(Instrument inst,double current){
        BigDecimal cur=Decimals.floorStep(Decimals.bd(current),inst.qtyStep);
        if(cur.signum()<=0)return null;
        BigDecimal remain=Decimals.ceilStep(cur.multiply(Decimals.bd(1.0-s.reduceFraction)),inst.qtyStep);
        if(remain.compareTo(inst.minQty)<0)return cur;
        BigDecimal close=Decimals.floorStep(cur.subtract(remain),inst.qtyStep);
        if(close.signum()<=0)return null;
        return close.compareTo(cur)>0?cur:close;
    }

    private BigDecimal fullCloseQty(Instrument inst,double current){
        BigDecimal cur=Decimals.floorStep(Decimals.bd(current),inst.qtyStep);
        return cur.signum()>0?cur:null;
    }

    private BigDecimal stopPrice(Instrument inst,String side,double raw){return "Buy".equals(side)?Decimals.floorTick(raw,inst.tickSize):Decimals.ceilTick(raw,inst.tickSize);}
    private static double positive(double...x){for(double v:x)if(v>0)return v;return 0;}
    private static String err(Throwable e){String m=e==null?null:e.getMessage();return m==null||m.trim().isEmpty()?String.valueOf(e):e.getClass().getSimpleName()+": "+m;}
    private void log(String x){BotRuntime.log(x);db.event("INFO","LOG",x,null,null);}
    @Override public void close(){try{db.close();}catch(Exception ignored){}try{api.close();}catch(Exception ignored){}}
}
