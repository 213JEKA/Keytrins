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
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
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
    private final OkxClient api;
    private final Db db;
    private final StrategyEngine strategy;
    private Map<String,Instrument> instruments = new HashMap<>();
    private Map<String,Ticker> tickers = new HashMap<>();
    private final Map<String,TradeState> trades;
    private List<String> universe = new ArrayList<>();
    private long lastUniverseRefresh = 0, lastScanClose = -1, lastManage = 0;

    public LiveResearchEngine(Context context, SettingsStore.Snapshot s) {
        this.s = s;
        this.api = new OkxClient(s);
        this.db = new Db(context.getApplicationContext());
        this.strategy = new StrategyEngine(s);
        this.trades = db.openTrades();
    }

    public static String doctor(Context context, SettingsStore.Snapshot s) throws Exception {
        try (OkxClient api = new OkxClient(s)) {
            long server = api.serverTimeMs();
            long skew = Math.abs(System.currentTimeMillis() - server);
            int inst = api.getInstruments().size();
            Map<String,Ticker> tick = api.getAllTickers();
            if (s.apiKey.isEmpty() || s.apiSecret.isEmpty() || s.apiPassphrase.isEmpty()) {
                return "OKX PUBLIC OK • swaps=" + inst + " • tickers=" + tick.size() + " • time skew=" + skew + "ms • API ключи не заданы";
            }
            String mode = api.positionMode();
            double bal = api.walletBalanceUsdt();
            Map<String,Position> pos = api.openPositions();
            if (!"net_mode".equals(mode)) {
                return "OKX API OK, но posMode=" + mode + ". Для робота нужен One-Way / net_mode.";
            }
            for (Position p : pos.values()) {
                if (p.positionIdx != 0) return "OKX API OK, но найдена не-net позиция. Для робота нужен One-Way / net_mode.";
            }
            return String.format(Locale.US, "OKX API OK • balance=%.2f USDT • positions=%d • swaps=%d • net_mode • skew=%dms",
                    bal, pos.size(), inst, skew);
        }
    }

    public void run(BooleanSupplier keepRunning, Consumer<String> notify) throws Exception {
        refreshUniverse(true);
        if (hasCredentials()) {
            try { BotRuntime.balance = api.walletBalanceUsdt(); } catch (Exception e) { log("Баланс недоступен: "+e.getMessage()); }
        }
        BotRuntime.status = s.live ? "OKX INVERSE LIVE • работает" : "OKX INVERSE OBSERVE • работает";
        notify.accept(BotRuntime.status);
        log("Universe="+universe.size()+" • риск="+s.riskUsdt+" USDT • INVERSE • "+(s.live?"LIVE":"OBSERVE"));

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
                log("LOOP ERROR: "+e.getMessage());
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
            Ticker t = tickers.get(inst.symbol);
            if (t == null || t.turnover24h < s.minTurnoverUsdt) continue;
            inst.turnover24h = t.turnover24h;
            candidates.add(inst);
        }
        candidates.sort((a,b) -> Double.compare(b.turnover24h, a.turnover24h));
        universe = new ArrayList<>();
        for (int i=0;i<Math.min(s.universeSize,candidates.size());i++) universe.add(candidates.get(i).symbol);
        lastUniverseRefresh = System.currentTimeMillis();
        BotRuntime.universe = universe.size();
        db.event("INFO","UNIVERSE",String.join(",",universe),null,null);
        log("OKX Universe: "+String.join(", ", universe));
    }

    private void scanOnce() {
        BotRuntime.scans++;
        try { tickers = api.getAllTickers(); }
        catch (Exception e) { log("Ticker scan fail: "+e.getMessage()); return; }

        Map<String,Position> exchangePositions = new HashMap<>();
        if (hasCredentials()) {
            try {
                if (s.live && !"net_mode".equals(api.positionMode())) {
                    log("BLOCK ENTRY: OKX account не One-Way / net_mode");
                    return;
                }
                exchangePositions = api.openPositions();
                for (Position p : exchangePositions.values()) {
                    if (p.positionIdx != 0) {
                        log("BLOCK ENTRY: "+p.symbol+" не net_mode");
                        if (s.live) return;
                    }
                }
            } catch (Exception e) {
                log("Positions read fail: "+e.getMessage());
                if (s.live) return;
            }
        }

        List<String> symbols = new ArrayList<>(universe);
        int signalCount=0;
        for (String symbol : symbols) {
            Instrument inst=instruments.get(symbol); if(inst==null) continue;
            try {
                if (exchangePositions.containsKey(symbol) || trades.containsKey(symbol)) continue;
                List<Candle> h1=api.getKlines(symbol,"60",260);
                List<Candle> m15=api.getKlines(symbol,"15",160);
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
        String symbol=sig.symbol;
        Instrument inst=instruments.get(symbol);
        if(inst==null||t==null)return;
        long age=Math.max(0,System.currentTimeMillis()-sig.signalTimeMs);
        if(age>180_000L){db.logSignal(symbol,"REJECT","STALE_SIGNAL",sig,0,0);return;}
        if(s.live && db.hasEntryForSignal(symbol,sig.signalTimeMs))return;

        // Strategy direction stays untouched. Only the actual OKX execution side is inverted.
        String side="LONG".equals(sig.direction)?"Sell":"Buy";
        double entry="Buy".equals(side)?positive(t.ask,t.last,sig.entryRef):positive(t.bid,t.last,sig.entryRef);

        // Mirror the original signal's stop distance around the actual inverse entry.
        double signalRiskDistance=Math.abs(sig.entryRef-sig.stopRef);
        if(signalRiskDistance<=0)return;
        double stop="Buy".equals(side)?entry-signalRiskDistance:entry+signalRiskDistance;
        double riskDistance=Math.abs(entry-stop);
        if(riskDistance<=0)return;

        double desiredBaseQty=s.riskUsdt/riskDistance;
        double desiredNotional=desiredBaseQty*entry;
        if(desiredNotional>s.maxNotionalUsdt){db.logSignal(symbol,"REJECT","NOTIONAL_CAP",sig,0,0);return;}
        BigDecimal contracts=quantizeEntryQty(inst,desiredBaseQty,entry);
        if(contracts==null){db.logSignal(symbol,"REJECT","QTY_MIN",sig,0,0);return;}

        double contractCount=contracts.doubleValue();
        double baseQty=contractCount*inst.contractValue.doubleValue();
        double actualRisk=baseQty*riskDistance;
        double taker=api.feeRate(symbol);
        double spread=Math.max(0,t.ask-t.bid);
        double notional=baseQty*entry;
        double estimatedCost=2.0*notional*taker+spread*baseQty;
        double costR=estimatedCost/s.riskUsdt;
        if(costR>s.maxCostR){db.logSignal(symbol,"REJECT","COST_R",sig,contractCount,costR);return;}

        BigDecimal stopQ=stopPrice(inst,side,stop);
        db.logSignal(symbol,s.live?"ENTRY_ATTEMPT":"WOULD_ENTER","INVERSE "+sig.direction+"->"+("Buy".equals(side)?"LONG":"SHORT")+" • "+sig.reason,sig,contractCount,costR);
        log(String.format(Locale.US,"SIGNAL %s → OKX %s %s contracts=%s base≈%.8f R=$%.2f cost/R=%.2f",
                sig.direction,"Buy".equals(side)?"LONG":"SHORT",symbol,Decimals.fmt(contracts),baseQty,actualRisk,costR));
        if(!s.live)return;

        String tradeId=("OKXI_"+symbol+"_"+(System.currentTimeMillis()/1000L));
        api.placeEntry(tradeId,symbol,side,Decimals.fmt(contracts),Decimals.fmt(stopQ));
        Position p=api.waitPosition(symbol,20_000L);
        double actualEntry=p.avgPrice>0?p.avgPrice:entry, actualContracts=p.size;
        TradeState tr=new TradeState();
        tr.tradeId=tradeId; tr.symbol=symbol; tr.side=side; tr.openedAtMs=System.currentTimeMillis();
        tr.entryPrice=actualEntry; tr.initialQty=actualContracts; tr.currentQty=actualContracts;
        tr.initialStop=stopQ.doubleValue(); tr.currentStop=stopQ.doubleValue();
        tr.riskDistance=Math.abs(actualEntry-tr.initialStop); tr.targetRiskUsdt=s.riskUsdt; tr.atr=sig.m15Atr; tr.takerFee=taker;
        tr.spreadAtEntry=spread; tr.costREst=costR; tr.state="OPEN"; tr.highWater=actualEntry; tr.lowWater=actualEntry;
        tr.reduced=false; tr.trailing=false;
        trades.put(symbol,tr); db.upsertTrade(tr); db.logSignal(symbol,"ENTRY","FILLED_INVERSE",sig,actualContracts,costR);
        BotRuntime.entries++;
        log("OKX LIVE ENTRY "+symbol+" signal="+sig.direction+" actual="+("Buy".equals(side)?"LONG":"SHORT")+" contracts="+actualContracts+" @ "+actualEntry+" SL="+tr.initialStop);
    }

    private void managePositions() {
        if(trades.isEmpty()||!hasCredentials())return;
        try {
            Map<String,Position> positions=api.openPositions();
            tickers=api.getAllTickers();
            BotRuntime.openPositions=positions.size();
            try { BotRuntime.balance=api.walletBalanceUsdt(); } catch(Exception ignored){}
            for(String symbol:new ArrayList<>(trades.keySet())){
                TradeState tr=trades.get(symbol);
                Position p=positions.get(symbol);
                Instrument inst=instruments.get(symbol);
                if(p==null){
                    long closedAt=System.currentTimeMillis();
                    double[] tx=new double[]{0,0,0,0};
                    try{Thread.sleep(350);tx=api.transactionSummary(symbol,tr.openedAtMs,closedAt);}catch(Exception ignored){}
                    db.closeTrade(tr,closedAt,tx[0],tx[1],tx[2],tx[3]);
                    log(String.format(Locale.US,"CLOSED %s net=%+.3f USDT (%+.3fR) fees=%.3f",symbol,tx[3],tr.targetRiskUsdt>0?tx[3]/tr.targetRiskUsdt:0,tx[1]));
                    db.event("INFO","CLOSED","OKX position absent",symbol,tr.tradeId);
                    db.deleteTrade(symbol);trades.remove(symbol);continue;
                }
                if(p.positionIdx!=0){log("WARNING "+symbol+": OKX не net_mode, управление остановлено");continue;}
                tr.currentQty=p.size;
                Ticker tk=tickers.get(symbol);
                double mark=tk==null?p.markPrice:positive(tk.mark,tk.last,p.markPrice);
                if(mark<=0)continue;
                tr.highWater=Math.max(tr.highWater,mark);
                tr.lowWater=Math.min(tr.lowWater,mark);
                double r=priceR(tr,mark);
                maybeBreakEven(tr,inst,r,mark);
                db.upsertTrade(tr);
            }
        }catch(Exception e){log("MANAGE ERROR: "+e.getMessage());}
    }

    private void maybeBreakEven(TradeState tr,Instrument inst,double r,double mark)throws Exception{
        if(inst==null||tr.beArmed||r<s.beTriggerR)return;
        double offset=tr.entryPrice*(2*tr.takerFee)+tr.spreadAtEntry+2*inst.tickSize.doubleValue();
        double candidate="Buy".equals(tr.side)?tr.entryPrice+offset:tr.entryPrice-offset;
        if(stopImproves(tr,inst,candidate,mark)){
            BigDecimal q=stopPrice(inst,tr.side,candidate);
            api.setStop(tr.tradeId,tr.symbol,Decimals.fmt(q));
            tr.currentStop=q.doubleValue();tr.beArmed=true;tr.state="BE";
            log(String.format(Locale.US,"BE %s %.3fR stop=%s",tr.symbol,r,Decimals.fmt(q)));
        }
    }

    private boolean stopImproves(TradeState tr,Instrument inst,double candidate,double mark){
        double min=inst.tickSize.doubleValue()*2;
        if("Buy".equals(tr.side))return candidate>tr.currentStop+min&&candidate<mark-inst.tickSize.doubleValue();
        return candidate<tr.currentStop-min&&candidate>mark+inst.tickSize.doubleValue();
    }

    private double priceR(TradeState tr,double price){
        if(tr.riskDistance<=0)return 0;
        return "Buy".equals(tr.side)?(price-tr.entryPrice)/tr.riskDistance:(tr.entryPrice-price)/tr.riskDistance;
    }

    private BigDecimal quantizeEntryQty(Instrument inst,double desiredBaseQty,double price){
        if(inst.contractValue.signum()<=0)return null;
        BigDecimal raw=Decimals.bd(desiredBaseQty).divide(inst.contractValue,18,RoundingMode.DOWN);
        BigDecimal q=Decimals.floorStep(raw,inst.qtyStep);
        if(q.compareTo(inst.minQty)<0)return null;
        if(q.compareTo(inst.maxMarketQty)>0)q=Decimals.floorStep(inst.maxMarketQty,inst.qtyStep);
        BigDecimal base=q.multiply(inst.contractValue);
        if(base.multiply(Decimals.bd(price)).compareTo(inst.minNotional)<0)return null;
        return q.signum()>0?q:null;
    }

    private BigDecimal stopPrice(Instrument inst,String side,double raw){
        return "Buy".equals(side)?Decimals.floorTick(raw,inst.tickSize):Decimals.ceilTick(raw,inst.tickSize);
    }

    private boolean hasCredentials(){return !s.apiKey.isEmpty()&&!s.apiSecret.isEmpty()&&!s.apiPassphrase.isEmpty();}
    private static double positive(double...x){for(double v:x)if(v>0)return v;return 0;}
    private void log(String x){BotRuntime.log(x);db.event("INFO","LOG",x,null,null);}
    @Override public void close(){try{db.close();}catch(Exception ignored){}try{api.close();}catch(Exception ignored){}}
}
