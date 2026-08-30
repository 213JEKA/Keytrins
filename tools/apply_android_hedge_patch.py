from pathlib import Path
import re

ROOT = Path('live-research-android')


def read(rel):
    return (ROOT / rel).read_text(encoding='utf-8')


def write(rel, text):
    p = ROOT / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf-8')


def replace_once(rel, old, new):
    text = read(rel)
    if old not in text:
        raise SystemExit(f'Expected text not found in {rel}: {old[:120]!r}')
    write(rel, text.replace(old, new, 1))


def sub_once(rel, pattern, repl):
    text = read(rel)
    new, n = re.subn(pattern, repl, text, count=1, flags=re.S)
    if n != 1:
        raise SystemExit(f'Expected one regex match in {rel}, got {n}: {pattern[:120]}')
    write(rel, new)


# -----------------------------------------------------------------------------
# Persisted hedge state.
# -----------------------------------------------------------------------------
write('app/src/main/java/com/keytrins/liveresearch/model/HedgeState.java', '''package com.keytrins.liveresearch.model;

public final class HedgeState {
    public String primaryTradeId, symbol, side, state;
    public int positionIdx;
    public long openedAtMs, lastAttemptMs;
    public double entryPrice, initialQty, currentQty, initialStop, currentStop;
    public double atr, takerFee, spreadAtEntry, highWater, lowWater;
    public double peakProfitUsdt, protectedProfitUsdt;

    public HedgeState() {}
}
''')

# -----------------------------------------------------------------------------
# SQLite persistence for hedge leg.
# -----------------------------------------------------------------------------
replace_once('app/src/main/java/com/keytrins/liveresearch/storage/Db.java',
             'import com.keytrins.liveresearch.model.Signal;\nimport com.keytrins.liveresearch.model.TradeState;',
             'import com.keytrins.liveresearch.model.HedgeState;\nimport com.keytrins.liveresearch.model.Signal;\nimport com.keytrins.liveresearch.model.TradeState;')
replace_once('app/src/main/java/com/keytrins/liveresearch/storage/Db.java',
             'private static final int VERSION = 3;', 'private static final int VERSION = 4;')
replace_once('app/src/main/java/com/keytrins/liveresearch/storage/Db.java',
             '        db.execSQL("CREATE TABLE closed_trades(id INTEGER PRIMARY KEY AUTOINCREMENT, trade_id TEXT, symbol TEXT, side TEXT, opened_at INTEGER, closed_at INTEGER, entry REAL, initial_qty REAL, target_risk REAL, gross REAL, fees REAL, funding REAL, net REAL, net_r REAL, peak_profit REAL DEFAULT 0, protected_profit REAL DEFAULT 0, reduced INTEGER, be_armed INTEGER, trailing INTEGER, balance_open REAL DEFAULT 0, balance_close REAL DEFAULT 0)");',
             '        db.execSQL("CREATE TABLE hedges(primary_trade_id TEXT PRIMARY KEY, symbol TEXT UNIQUE NOT NULL, side TEXT, position_idx INTEGER, state TEXT, opened_at INTEGER, last_attempt INTEGER, entry REAL, initial_qty REAL, current_qty REAL, initial_stop REAL, current_stop REAL, atr REAL, taker_fee REAL, spread REAL, high_water REAL, low_water REAL, peak_profit REAL DEFAULT 0, protected_profit REAL DEFAULT 0)");\n        db.execSQL("CREATE TABLE closed_trades(id INTEGER PRIMARY KEY AUTOINCREMENT, trade_id TEXT, symbol TEXT, side TEXT, opened_at INTEGER, closed_at INTEGER, entry REAL, initial_qty REAL, target_risk REAL, gross REAL, fees REAL, funding REAL, net REAL, net_r REAL, peak_profit REAL DEFAULT 0, protected_profit REAL DEFAULT 0, reduced INTEGER, be_armed INTEGER, trailing INTEGER, balance_open REAL DEFAULT 0, balance_close REAL DEFAULT 0)");')
replace_once('app/src/main/java/com/keytrins/liveresearch/storage/Db.java',
'''        if (oldVersion < 3) {
            try { db.execSQL("ALTER TABLE trades ADD COLUMN peak_profit REAL DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE trades ADD COLUMN protected_profit REAL DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE closed_trades ADD COLUMN peak_profit REAL DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE closed_trades ADD COLUMN protected_profit REAL DEFAULT 0"); } catch (Exception ignored) {}
        }
''',
'''        if (oldVersion < 3) {
            try { db.execSQL("ALTER TABLE trades ADD COLUMN peak_profit REAL DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE trades ADD COLUMN protected_profit REAL DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE closed_trades ADD COLUMN peak_profit REAL DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE closed_trades ADD COLUMN protected_profit REAL DEFAULT 0"); } catch (Exception ignored) {}
        }
        if (oldVersion < 4) {
            db.execSQL("CREATE TABLE IF NOT EXISTS hedges(primary_trade_id TEXT PRIMARY KEY, symbol TEXT UNIQUE NOT NULL, side TEXT, position_idx INTEGER, state TEXT, opened_at INTEGER, last_attempt INTEGER, entry REAL, initial_qty REAL, current_qty REAL, initial_stop REAL, current_stop REAL, atr REAL, taker_fee REAL, spread REAL, high_water REAL, low_water REAL, peak_profit REAL DEFAULT 0, protected_profit REAL DEFAULT 0)");
        }
''')

hedge_db_methods = '''
    public synchronized void upsertHedge(HedgeState h) {
        ContentValues v = new ContentValues();
        v.put("primary_trade_id", h.primaryTradeId); v.put("symbol", h.symbol); v.put("side", h.side);
        v.put("position_idx", h.positionIdx); v.put("state", h.state); v.put("opened_at", h.openedAtMs); v.put("last_attempt", h.lastAttemptMs);
        v.put("entry", h.entryPrice); v.put("initial_qty", h.initialQty); v.put("current_qty", h.currentQty);
        v.put("initial_stop", h.initialStop); v.put("current_stop", h.currentStop); v.put("atr", h.atr);
        v.put("taker_fee", h.takerFee); v.put("spread", h.spreadAtEntry); v.put("high_water", h.highWater); v.put("low_water", h.lowWater);
        v.put("peak_profit", h.peakProfitUsdt); v.put("protected_profit", h.protectedProfitUsdt);
        getWritableDatabase().insertWithOnConflict("hedges", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized Map<String, HedgeState> openHedges() {
        Map<String, HedgeState> out = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM hedges", null)) {
            while (c.moveToNext()) {
                HedgeState h = new HedgeState();
                h.primaryTradeId = s(c,"primary_trade_id"); h.symbol = s(c,"symbol"); h.side = s(c,"side"); h.state = s(c,"state");
                h.positionIdx = i(c,"position_idx"); h.openedAtMs = l(c,"opened_at"); h.lastAttemptMs = l(c,"last_attempt");
                h.entryPrice = d(c,"entry"); h.initialQty = d(c,"initial_qty"); h.currentQty = d(c,"current_qty");
                h.initialStop = d(c,"initial_stop"); h.currentStop = d(c,"current_stop"); h.atr = d(c,"atr");
                h.takerFee = d(c,"taker_fee"); h.spreadAtEntry = d(c,"spread"); h.highWater = d(c,"high_water"); h.lowWater = d(c,"low_water");
                h.peakProfitUsdt = d(c,"peak_profit"); h.protectedProfitUsdt = d(c,"protected_profit");
                out.put(h.symbol,h);
            }
        }
        return out;
    }

    public synchronized void deleteHedge(String symbol) {
        getWritableDatabase().delete("hedges", "symbol=?", new String[]{symbol});
    }

'''
replace_once('app/src/main/java/com/keytrins/liveresearch/storage/Db.java',
             '    public synchronized String recentClosedTradesText(int limit) {',
             hedge_db_methods + '    public synchronized String recentClosedTradesText(int limit) {')

# -----------------------------------------------------------------------------
# Bybit V5: hedge mode + positionIdx-aware entry/reduce/stop/wait.
# -----------------------------------------------------------------------------
replace_once('app/src/main/java/com/keytrins/liveresearch/net/BybitClient.java',
             '            out.put(p.symbol,p);',
             '            out.put(positionKey(p.symbol,p.positionIdx),p);')
sub_once('app/src/main/java/com/keytrins/liveresearch/net/BybitClient.java',
         r'    public Position position\(String symbol\) throws Exception \{.*?\n    \}\n\n    public double\[\] transactionSummary',
'''    public static String positionKey(String symbol, int positionIdx) {
        return positionIdx == 0 ? symbol : symbol + "#" + positionIdx;
    }

    public Position position(String symbol) throws Exception { return position(symbol, -1); }

    public Position position(String symbol, int positionIdx) throws Exception {
        LinkedHashMap<String,String> q = new LinkedHashMap<>(); q.put("category","linear"); q.put("symbol",symbol);
        JSONObject r = privateGet("/v5/position/list", q);
        JSONArray list = result(r).getJSONArray("list");
        for (int i=0;i<list.length();i++) {
            JSONObject x = list.getJSONObject(i); double size=d(x,"size"); if(size<=0) continue;
            int idx=x.optInt("positionIdx",0); if(positionIdx>=0 && idx!=positionIdx) continue;
            return new Position(symbol,x.optString("side"),size,d(x,"avgPrice"),d(x,"markPrice"),
                    d(x,"stopLoss"),d(x,"unrealisedPnl"),idx);
        }
        return null;
    }

    public double[] transactionSummary''')
sub_once('app/src/main/java/com/keytrins/liveresearch/net/BybitClient.java',
         r'    public String placeEntry\(String tradeId, String symbol, String side, String qty, String stopLoss\) throws Exception \{.*?\n    \}\n\n    public String reducePosition',
'''    public void switchToHedgeMode(String symbol) throws Exception {
        LinkedHashMap<String,Object> b = new LinkedHashMap<>();
        b.put("category","linear"); b.put("symbol",symbol); b.put("mode",3);
        try { privatePost("/v5/position/switch-mode", b); }
        catch (ApiException e) {
            String m=e.getMessage()==null?"":e.getMessage().toLowerCase(Locale.US);
            if (e.code != 110025 && !m.contains("not modified")) throw e;
        }
    }

    public String placeEntry(String tradeId, String symbol, String side, String qty, String stopLoss) throws Exception {
        return placeEntry(tradeId,symbol,side,qty,stopLoss,0);
    }

    public String placeEntry(String tradeId, String symbol, String side, String qty, String stopLoss, int positionIdx) throws Exception {
        setLeverage(symbol, s.leverage);
        LinkedHashMap<String,Object> b = new LinkedHashMap<>();
        b.put("category","linear"); b.put("symbol",symbol); b.put("side",side); b.put("orderType","Market");
        b.put("qty",qty); b.put("positionIdx",positionIdx); b.put("reduceOnly",false); b.put("orderLinkId",shortId(tradeId));
        b.put("stopLoss",stopLoss); b.put("slTriggerBy","MarkPrice"); b.put("tpslMode","Full"); b.put("slOrderType","Market");
        JSONObject r = privatePost("/v5/order/create", b);
        return result(r).optString("orderId", "");
    }

    public String reducePosition''')
sub_once('app/src/main/java/com/keytrins/liveresearch/net/BybitClient.java',
         r'    public String reducePosition\(String tradeId, String symbol, String side, String qty\) throws Exception \{.*?\n    \}\n\n    public void setStop',
'''    public String reducePosition(String tradeId, String symbol, String side, String qty) throws Exception {
        return reducePosition(tradeId,symbol,side,qty,0);
    }

    public String reducePosition(String tradeId, String symbol, String side, String qty, int positionIdx) throws Exception {
        LinkedHashMap<String,Object> b = new LinkedHashMap<>();
        b.put("category","linear"); b.put("symbol",symbol); b.put("side",side); b.put("orderType","Market");
        b.put("qty",qty); b.put("positionIdx",positionIdx); b.put("reduceOnly",true); b.put("orderLinkId",shortId(tradeId+"_RED"));
        JSONObject r = privatePost("/v5/order/create", b);
        return result(r).optString("orderId", "");
    }

    public void setStop''')
sub_once('app/src/main/java/com/keytrins/liveresearch/net/BybitClient.java',
         r'    public void setStop\(String symbol, String stopLoss\) throws Exception \{.*?\n    \}\n\n    public Position waitPosition',
'''    public void setStop(String symbol, String stopLoss) throws Exception { setStop(symbol,stopLoss,0); }

    public void setStop(String symbol, String stopLoss, int positionIdx) throws Exception {
        LinkedHashMap<String,Object> b = new LinkedHashMap<>();
        b.put("category","linear"); b.put("symbol",symbol); b.put("tpslMode","Full"); b.put("positionIdx",positionIdx);
        b.put("stopLoss",stopLoss); b.put("slTriggerBy","MarkPrice");
        privatePost("/v5/position/trading-stop", b);
    }

    public Position waitPosition''')
sub_once('app/src/main/java/com/keytrins/liveresearch/net/BybitClient.java',
         r'    public Position waitPosition\(String symbol, long timeoutMs\) throws Exception \{.*?\n    \}\n\n    public Position waitReduced\(String symbol, double before, long timeoutMs\) throws Exception \{.*?\n    \}',
'''    public Position waitPosition(String symbol, long timeoutMs) throws Exception { return waitPosition(symbol,-1,timeoutMs); }

    public Position waitPosition(String symbol, int positionIdx, long timeoutMs) throws Exception {
        long end = System.currentTimeMillis()+timeoutMs;
        while(System.currentTimeMillis()<end) {
            Position p=position(symbol,positionIdx); if(p!=null && p.size>0) return p;
            Thread.sleep(350);
        }
        throw new IllegalStateException("Позиция не появилась после подтверждения ордера: "+symbol+" idx="+positionIdx);
    }

    public Position waitReduced(String symbol, double before, long timeoutMs) throws Exception { return waitReduced(symbol,-1,before,timeoutMs); }

    public Position waitReduced(String symbol, int positionIdx, double before, long timeoutMs) throws Exception {
        long end=System.currentTimeMillis()+timeoutMs;
        while(System.currentTimeMillis()<end) {
            Position p=position(symbol,positionIdx); if(p==null || p.size < before) return p;
            Thread.sleep(250);
        }
        throw new IllegalStateException("Сокращение позиции не подтверждено: "+symbol+" idx="+positionIdx);
    }''')

# -----------------------------------------------------------------------------
# Runtime engine: primary entry strategy unchanged; add full-size protective leg.
# -----------------------------------------------------------------------------
replace_once('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
             'import com.keytrins.liveresearch.model.Instrument;\nimport com.keytrins.liveresearch.model.Position;',
             'import com.keytrins.liveresearch.model.HedgeState;\nimport com.keytrins.liveresearch.model.Instrument;\nimport com.keytrins.liveresearch.model.Position;')
replace_once('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
             '    private static final double DOLLAR_LOCK_LAG_USDT = 0.50;\n',
             '    private static final double DOLLAR_LOCK_LAG_USDT = 0.50;\n    private static final double HEDGE_TRIGGER_ATR = 0.15;\n    private static final double HEDGE_EMERGENCY_STOP_ATR = 0.20;\n    private static final long HEDGE_UNKNOWN_WAIT_MS = 60_000L;\n')
replace_once('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
             '    private final Map<String,TradeState> trades;\n',
             '    private final Map<String,TradeState> trades;\n    private final Map<String,HedgeState> hedges;\n')
replace_once('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
             '        this.trades = db.openTrades();\n',
             '        this.trades = db.openTrades();\n        this.hedges = db.openHedges();\n')
sub_once('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
         r'            double bal = api.walletBalanceUsdt\(\);\n            Map<String,Position> pos = api.openPositions\(\);\n            for \(Position p : pos.values\(\)\) \{.*?\n            \}\n            return String.format',
'''            double bal = api.walletBalanceUsdt();
            Map<String,Position> pos = api.openPositions();
            int hedgeLegs=0, oneWayLegs=0;
            for (Position p : pos.values()) { if(p.positionIdx==0)oneWayLegs++; else hedgeLegs++; }
            return String.format''')
replace_once('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
             '                    bal, pos.size(), inst, skew);',
             '                    bal, pos.size(), inst, skew) + " • hedge legs="+hedgeLegs+" • one-way legs="+oneWayLegs;')
sub_once('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
         r'        Map<String,Position> exchangePositions = new HashMap<>\(\);\n        if \(!s.apiKey.isEmpty\(\) && !s.apiSecret.isEmpty\(\)\) \{\n            try \{\n                exchangePositions = api.openPositions\(\);.*?\n            \} catch \(Exception e\) \{',
'''        Map<String,Position> exchangePositions = new HashMap<>();
        if (!s.apiKey.isEmpty() && !s.apiSecret.isEmpty()) {
            try {
                exchangePositions = api.openPositions();
            } catch (Exception e) {''')
replace_once('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
             '                if(exchangePositions.containsKey(symbol)||trades.containsKey(symbol)) continue;',
             '                if(hasAnyPosition(exchangePositions,symbol)||trades.containsKey(symbol)) continue;')
sub_once('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
         r'        String tradeId=\("LRA_"\+symbol\+"_"\+\(System.currentTimeMillis\(\)/1000L\)\); if\(tradeId.length\(\)>36\)tradeId=tradeId.substring\(0,36\);\n        api.placeEntry\(tradeId,symbol,side,Decimals.fmt\(qty\),Decimals.fmt\(stopQ\)\);\n        Position p=api.waitPosition\(symbol,20_000L\);.*?BotRuntime.entries\+\+; log\("LIVE ENTRY "\+symbol\+" "\+side\+" qty="\+actualQty\+" @ "\+actualEntry\+" SL="\+tr.initialStop\);',
'''        try { api.switchToHedgeMode(symbol); }
        catch(Exception e){ db.logSignal(symbol,"REJECT","HEDGE_MODE_FAIL",sig,q,costR); log("ENTRY BLOCK "+symbol+": Hedge Mode недоступен: "+err(e)); return; }
        int primaryIdx="Buy".equals(side)?1:2;
        String tradeId=("LRA_"+symbol+"_"+(System.currentTimeMillis()/1000L)); if(tradeId.length()>36)tradeId=tradeId.substring(0,36);
        api.placeEntry(tradeId,symbol,side,Decimals.fmt(qty),Decimals.fmt(stopQ),primaryIdx);
        Position p=api.waitPosition(symbol,primaryIdx,20_000L);
        double actualEntry=p.avgPrice>0?p.avgPrice:entry, actualQty=p.size;
        TradeState tr=new TradeState(); tr.tradeId=tradeId; tr.symbol=symbol; tr.side=side; tr.openedAtMs=System.currentTimeMillis();
        tr.entryPrice=actualEntry; tr.initialQty=actualQty; tr.currentQty=actualQty; tr.initialStop=stopQ.doubleValue(); tr.currentStop=stopQ.doubleValue();
        tr.riskDistance=Math.abs(actualEntry-tr.initialStop); tr.targetRiskUsdt=s.riskUsdt; tr.atr=sig.m15Atr; tr.takerFee=taker;
        tr.spreadAtEntry=spread; tr.costREst=costR; tr.state="OPEN"; tr.highWater=actualEntry; tr.lowWater=actualEntry;
        tr.peakProfitUsdt=0; tr.protectedProfitUsdt=0;
        trades.put(symbol,tr); db.upsertTrade(tr); db.logSignal(symbol,"ENTRY","FILLED",sig,actualQty,costR);
        BotRuntime.entries++; log("LIVE ENTRY "+symbol+" "+side+" idx="+primaryIdx+" qty="+actualQty+" @ "+actualEntry+" SL="+tr.initialStop);''')

manage_and_helpers = '''    private void managePositions() {
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

                    if(hedge==null) maybeOpenHedge(tr,inst,primary,tk,mark);
                    hedge=hedges.get(symbol);
                    if(hedge!=null){
                        Position hp=findPosition(positions,symbol,hedge.positionIdx,hedge.side);
                        if(hp!=null && primaryEstimatedNet(tr,inst,mark)>0) maybeCloseHedgeOnPrimaryRecovery(tr,hedge,inst,hp);
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
        if(inst==null||primary==null||tr.atr<=0||tr.currentQty<=0)return;
        double adverse="Buy".equals(tr.side)?tr.entryPrice-mark:mark-tr.entryPrice;
        if(adverse+1e-12 < HEDGE_TRIGGER_ATR*tr.atr)return;
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
        double rawStop="Buy".equals(hedgeSide)?entryRef-HEDGE_EMERGENCY_STOP_ATR*tr.atr:entryRef+HEDGE_EMERGENCY_STOP_ATR*tr.atr;
        BigDecimal stopQ=stopPrice(inst,hedgeSide,rawStop);

        HedgeState h=new HedgeState();
        h.primaryTradeId=tr.tradeId; h.symbol=tr.symbol; h.side=hedgeSide; h.positionIdx=hedgeIdx; h.state="PENDING";
        h.openedAtMs=System.currentTimeMillis(); h.lastAttemptMs=h.openedAtMs; h.entryPrice=entryRef; h.initialQty=qty.doubleValue(); h.currentQty=qty.doubleValue();
        h.initialStop=stopQ.doubleValue(); h.currentStop=stopQ.doubleValue(); h.atr=tr.atr; h.takerFee=tr.takerFee; h.spreadAtEntry=tr.spreadAtEntry;
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
            if(("PENDING".equals(h.state)||"OPEN_UNKNOWN".equals(h.state)||"CLOSE_UNKNOWN".equals(h.state)) && System.currentTimeMillis()-h.lastAttemptMs < HEDGE_UNKNOWN_WAIT_MS)return;
            if(!"UNAVAILABLE_ONE_WAY".equals(h.state)&&!"REJECTED".equals(h.state)){
                h.currentQty=0; if(!"CLOSED_RECOVERY".equals(h.state))h.state="CLOSED"; db.upsertHedge(h);
                db.event("INFO","HEDGE_CLOSED","exchange hedge position absent",h.symbol,tr.tradeId);
                log(String.format(Locale.US,"HEDGE CLOSED %s peak=$%.3f protected~$%.3f",h.symbol,h.peakProfitUsdt,h.protectedProfitUsdt));
            }
            return;
        }
        if(inst==null)return;
        if("PENDING".equals(h.state)||"OPEN_UNKNOWN".equals(h.state)||"REJECTED".equals(h.state))h.state="OPEN";
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

'''
sub_once('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
         r'    private void managePositions\(\) \{.*?\n    \}\n\n    private boolean maybeReduce',
         manage_and_helpers + '    private boolean maybeReduce')
replace_once('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
             'private boolean maybeReduce(TradeState tr,Instrument inst,double r)throws Exception{',
             'private boolean maybeReduce(TradeState tr,Instrument inst,double r,int positionIdx)throws Exception{')
replace_once('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
             'api.reducePosition("FX"+System.currentTimeMillis(),tr.symbol,opposite,Decimals.fmt(close));',
             'api.reducePosition("FX"+System.currentTimeMillis(),tr.symbol,opposite,Decimals.fmt(close),positionIdx);')
replace_once('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
             'Position p=api.waitReduced(tr.symbol,before,8_000L);',
             'Position p=api.waitReduced(tr.symbol,positionIdx,before,8_000L);')
replace_once('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
             'api.reducePosition("R85"+System.currentTimeMillis(),tr.symbol,opposite,Decimals.fmt(close));',
             'api.reducePosition("R85"+System.currentTimeMillis(),tr.symbol,opposite,Decimals.fmt(close),positionIdx);')
replace_once('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
             'Position p=api.waitReduced(tr.symbol,before,8_000L);',
             'Position p=api.waitReduced(tr.symbol,positionIdx,before,8_000L);')
replace_once('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
             'private void maybeBeTrail(TradeState tr,Instrument inst,double r,double mark)throws Exception{',
             'private void maybeBeTrail(TradeState tr,Instrument inst,double r,double mark,int positionIdx)throws Exception{')
engine=read('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java')
engine=engine.replace('api.setStop(tr.symbol,Decimals.fmt(q));','api.setStop(tr.symbol,Decimals.fmt(q),positionIdx);')
write('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',engine)

# -----------------------------------------------------------------------------
# Dashboard: show primary and hedge as separate legs.
# -----------------------------------------------------------------------------
replace_once('app/src/main/java/com/keytrins/liveresearch/MainActivity.java',
             'import com.keytrins.liveresearch.model.Position;\nimport com.keytrins.liveresearch.model.TradeState;',
             'import com.keytrins.liveresearch.model.HedgeState;\nimport com.keytrins.liveresearch.model.Position;\nimport com.keytrins.liveresearch.model.TradeState;')
replace_once('app/src/main/java/com/keytrins/liveresearch/MainActivity.java',
             'BotRuntime.log("Live Research v0.1.3.5 готов.");',
             'BotRuntime.log("Live Research v0.1.3.6 Hedge готов.");')
replace_once('app/src/main/java/com/keytrins/liveresearch/MainActivity.java',
             '                Map<String, TradeState> tracked = dashboardDb.openTrades();\n',
             '                Map<String, TradeState> tracked = dashboardDb.openTrades();\n                Map<String, HedgeState> hedgeStates = dashboardDb.openHedges();\n')
replace_once('app/src/main/java/com/keytrins/liveresearch/MainActivity.java',
             '                BotRuntime.positionsText = renderPositions(positions, tracked);',
             '                BotRuntime.positionsText = renderPositions(positions, tracked, hedgeStates);')
sub_once('app/src/main/java/com/keytrins/liveresearch/MainActivity.java',
         r'    private String renderPositions\(Map<String, Position> positions, Map<String, TradeState> tracked\) \{.*?\n    \}\n\n    private List<HistoryRow>',
'''    private String renderPositions(Map<String, Position> positions, Map<String, TradeState> tracked, Map<String, HedgeState> hedgeStates) {
        if (positions == null || positions.isEmpty()) return "Открытых сделок нет.";
        List<Position> rows = new ArrayList<>(positions.values());
        rows.sort((a,b) -> { int c=a.symbol.compareTo(b.symbol); return c!=0?c:Integer.compare(a.positionIdx,b.positionIdx); });
        StringBuilder b = new StringBuilder();
        for (Position p : rows) {
            TradeState t = tracked.get(p.symbol);
            HedgeState h = hedgeStates.get(p.symbol);
            boolean isHedge = h != null && p.side.equals(h.side) && (h.positionIdx==0 || p.positionIdx==h.positionIdx);
            if (b.length() > 0) b.append("\n\n");
            String direction = "Buy".equals(p.side) ? "LONG" : "SHORT";
            String state = isHedge ? "HEDGE • "+h.state : (t == null ? "BYBIT" : t.state);
            b.append(p.symbol).append("  ").append(direction).append("  •  ").append(state);
            b.append(String.format(Locale.US, "\nEntry %.8f   •   Mark %.8f   •   Qty %.8f", p.avgPrice, p.markPrice, p.size));
            b.append(String.format(Locale.US, "\nPnL %+.3f USDT", p.unrealisedPnl));
            if(isHedge){
                double sl=p.stopLoss>0?p.stopLoss:h.currentStop;
                if(sl>0)b.append(String.format(Locale.US,"   •   SL %.8f",sl));
                b.append(String.format(Locale.US,"\nHedge peak %+.2f   •   Protected ~%+.2f USDT",
                        Math.max(0.0,h.peakProfitUsdt),Math.max(0.0,h.protectedProfitUsdt)));
            } else if (t != null && p.side.equals(t.side)) {
                double r = 0;
                if (t.riskDistance > 0) r = "Buy".equals(t.side) ? (p.markPrice-t.entryPrice)/t.riskDistance : (t.entryPrice-p.markPrice)/t.riskDistance;
                b.append(String.format(Locale.US, "   •   %+.2fR", r));
                double sl = p.stopLoss > 0 ? p.stopLoss : t.currentStop;
                b.append(String.format(Locale.US, "   •   SL %.8f", sl));
                b.append(String.format(Locale.US, "\nPeak %+.2f   •   Protected ~%+.2f USDT",
                        Math.max(0.0, t.peakProfitUsdt), Math.max(0.0, t.protectedProfitUsdt)));
            } else if (p.stopLoss > 0) {
                b.append(String.format(Locale.US, "   •   SL %.8f", p.stopLoss));
            }
        }
        return b.toString();
    }

    private List<HistoryRow>''')

# Version/settings/workflow artifacts.
replace_once('app/build.gradle', "versionCode 107\n        versionName '0.1.3.5'", "versionCode 108\n        versionName '0.1.3.6'")
replace_once('app/src/main/res/layout/activity_settings.xml',
             'android:text="Crypto-only • H1 EMA50/200 + ADX≥22 • M15 pullback + confirmation\\n-0.20R + слом → закрыть 85% • -0.35R → закрыть весь остаток\\nDollar lock: каждые +0.50 USDT по максимуму → защита +0.50 USDT с отставанием 0.50\\n+0.50 → BE+расходы • +1.00 → lock ~+0.50 • +1.50 → ~+1.00\\nВысокий R: ATR/R-floor остаётся дополнительной защитой • TP нет"',
             'android:text="Crypto-only • H1 EMA50/200 + ADX≥22 • M15 pullback + confirmation\\nPrimary: -0.20R + слом → закрыть 85% • -0.35R → закрыть остаток\\nHEDGE: при -0.15 ATR открыть 100% встречной позиции • аварийный SL 0.20 ATR\\nЕсли primary снова выходит в расчётный net + → hedge закрыть reduce-only\\nЕсли adverse движение продолжается после partial/full primary → hedge остаётся и ведётся $0.50/$0.50 • TP нет"')
wf=Path('.github/workflows/live-research-android.yml')
w=wf.read_text(encoding='utf-8').replace('v0.1.3.3','v0.1.3.6')
wf.write_text(w,encoding='utf-8')

# One-shot cleanup. The workflow commits the resulting source tree.
for temp in [Path('.github/workflows/apply-android-hedge-once.yml'), Path('tools/apply_android_hedge_patch.py')]:
    if temp.exists():
        temp.unlink()
