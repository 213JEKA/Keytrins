from pathlib import Path
import re

ROOT = Path('live-research-android')

def p(rel): return ROOT / rel

def read(rel): return p(rel).read_text(encoding='utf-8')

def write(rel, text): p(rel).write_text(text, encoding='utf-8')

def replace_once(rel, old, new):
    text = read(rel)
    if old not in text:
        raise SystemExit(f'Expected text not found in {rel}: {old[:100]!r}')
    write(rel, text.replace(old, new, 1))

# TradeState: persist entry ATR separately from rolling ATR.
replace_once(
    'app/src/main/java/com/keytrins/liveresearch/model/TradeState.java',
    'public double targetRiskUsdt, atr, takerFee, spreadAtEntry, costREst, highWater, lowWater, balanceAtOpen;',
    'public double targetRiskUsdt, atr, entryAtr, takerFee, spreadAtEntry, costREst, highWater, lowWater, balanceAtOpen;'
)

# DB schema v5 with entry_atr migration.
replace_once('app/src/main/java/com/keytrins/liveresearch/storage/Db.java', 'private static final int VERSION = 4;', 'private static final int VERSION = 5;')
replace_once(
    'app/src/main/java/com/keytrins/liveresearch/storage/Db.java',
    'target_risk REAL, atr REAL, taker_fee REAL',
    'target_risk REAL, atr REAL, entry_atr REAL DEFAULT 0, taker_fee REAL'
)
replace_once(
    'app/src/main/java/com/keytrins/liveresearch/storage/Db.java',
    '        if (oldVersion < 4) {\n            db.execSQL("CREATE TABLE IF NOT EXISTS hedges(primary_trade_id TEXT PRIMARY KEY, symbol TEXT UNIQUE NOT NULL, side TEXT, position_idx INTEGER, state TEXT, opened_at INTEGER, last_attempt INTEGER, entry REAL, initial_qty REAL, current_qty REAL, initial_stop REAL, current_stop REAL, atr REAL, taker_fee REAL, spread REAL, high_water REAL, low_water REAL, peak_profit REAL DEFAULT 0, protected_profit REAL DEFAULT 0)");\n        }\n',
    '        if (oldVersion < 4) {\n            db.execSQL("CREATE TABLE IF NOT EXISTS hedges(primary_trade_id TEXT PRIMARY KEY, symbol TEXT UNIQUE NOT NULL, side TEXT, position_idx INTEGER, state TEXT, opened_at INTEGER, last_attempt INTEGER, entry REAL, initial_qty REAL, current_qty REAL, initial_stop REAL, current_stop REAL, atr REAL, taker_fee REAL, spread REAL, high_water REAL, low_water REAL, peak_profit REAL DEFAULT 0, protected_profit REAL DEFAULT 0)");\n        }\n        if (oldVersion < 5) {\n            try { db.execSQL("ALTER TABLE trades ADD COLUMN entry_atr REAL DEFAULT 0"); } catch (Exception ignored) {}\n        }\n'
)
replace_once(
    'app/src/main/java/com/keytrins/liveresearch/storage/Db.java',
    't.riskDistance = d(c, "risk_distance"); t.targetRiskUsdt = d(c, "target_risk"); t.atr = d(c, "atr");',
    't.riskDistance = d(c, "risk_distance"); t.targetRiskUsdt = d(c, "target_risk"); t.atr = d(c, "atr");\n                int entryAtrIdx = c.getColumnIndex("entry_atr"); t.entryAtr = entryAtrIdx >= 0 ? c.getDouble(entryAtrIdx) : t.atr;\n                if (!(t.entryAtr > 0)) t.entryAtr = t.atr;'
)
replace_once(
    'app/src/main/java/com/keytrins/liveresearch/storage/Db.java',
    'v.put("target_risk", t.targetRiskUsdt); v.put("atr", t.atr); v.put("taker_fee", t.takerFee);',
    'v.put("target_risk", t.targetRiskUsdt); v.put("atr", t.atr); v.put("entry_atr", t.entryAtr); v.put("taker_fee", t.takerFee);'
)

# Engine constants and entry state.
replace_once(
    'app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
    '    private static final double HEDGE_TRIGGER_ATR = 0.15;\n    private static final double HEDGE_EMERGENCY_STOP_ATR = 0.20;\n    private static final long HEDGE_UNKNOWN_WAIT_MS = 60_000L;',
    '    private static final double HEDGE_TRIGGER_ATR = 0.15;\n    private static final double HEDGE_TRIGGER_R_BACKSTOP = -0.15;\n    private static final double HEDGE_EMERGENCY_STOP_ATR = 0.20;\n    private static final long HEDGE_UNKNOWN_WAIT_MS = 60_000L;\n    private static final long HEDGE_RETRY_MS = 5_000L;\n    private static final long HEDGE_OPEN_GRACE_MS = 8_000L;'
)
replace_once(
    'app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
    '        try { api.switchToHedgeMode(symbol); }',
    '        HedgeState staleHedge=hedges.remove(symbol); if(staleHedge!=null) db.deleteHedge(symbol);\n        try { api.switchToHedgeMode(symbol); }'
)
replace_once(
    'app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
    'tr.riskDistance=Math.abs(actualEntry-tr.initialStop); tr.targetRiskUsdt=s.riskUsdt; tr.atr=sig.m15Atr; tr.takerFee=taker;',
    'tr.riskDistance=Math.abs(actualEntry-tr.initialStop); tr.targetRiskUsdt=s.riskUsdt; tr.atr=sig.m15Atr; tr.entryAtr=sig.m15Atr; tr.takerFee=taker;'
)

old = '''                    if(hedge==null) maybeOpenHedge(tr,inst,primary,tk,mark);\n                    hedge=hedges.get(symbol);\n                    if(hedge!=null){\n                        Position hp=findPosition(positions,symbol,hedge.positionIdx,hedge.side);\n                        if(hp!=null && primaryEstimatedNet(tr,inst,mark)>0) maybeCloseHedgeOnPrimaryRecovery(tr,hedge,inst,hp);\n                    }\n'''
new = '''                    String expectedHedgeSide="Buy".equals(tr.side)?"Sell":"Buy";\n                    int expectedHedgeIdx="Buy".equals(expectedHedgeSide)?1:2;\n                    Position exchangeHedge=findPosition(positions,symbol,expectedHedgeIdx,expectedHedgeSide);\n                    if(exchangeHedge!=null){\n                        if(hedge==null || !tr.tradeId.equals(hedge.primaryTradeId) || !hedgeIsPotentiallyAlive(hedge)){\n                            hedge=recoverHedgeState(tr,exchangeHedge);\n                        }\n                    } else if(hedgeAttemptAllowed(tr,hedge)){\n                        maybeOpenHedge(tr,inst,primary,tk,mark);\n                        hedge=hedges.get(symbol);\n                    }\n                    if(exchangeHedge!=null && hedge!=null && primaryEstimatedNet(tr,inst,mark)>0){\n                        maybeCloseHedgeOnPrimaryRecovery(tr,hedge,inst,exchangeHedge);\n                    }\n'''
replace_once('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java', old, new)

replace_once(
    'app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
    '        if(inst==null||primary==null||tr.atr<=0||tr.currentQty<=0)return;\n        double adverse="Buy".equals(tr.side)?tr.entryPrice-mark:mark-tr.entryPrice;\n        if(adverse+1e-12 < HEDGE_TRIGGER_ATR*tr.atr)return;',
    '        if(inst==null||primary==null||tr.currentQty<=0)return;\n        double triggerAtr=tr.entryAtr>0?tr.entryAtr:tr.atr; if(!(triggerAtr>0))return;\n        double adverse="Buy".equals(tr.side)?tr.entryPrice-mark:mark-tr.entryPrice;\n        double rNow=priceR(tr,mark);\n        boolean atrCross=adverse+1e-12 >= HEDGE_TRIGGER_ATR*triggerAtr;\n        boolean rBackstop=rNow<=HEDGE_TRIGGER_R_BACKSTOP;\n        if(!atrCross&&!rBackstop)return;\n        db.event("INFO","HEDGE_TRIGGER","adverse="+adverse+" entryATR="+triggerAtr+" r="+rNow,tr.symbol,tr.tradeId);'
)
replace_once(
    'app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
    'double rawStop="Buy".equals(hedgeSide)?entryRef-HEDGE_EMERGENCY_STOP_ATR*tr.atr:entryRef+HEDGE_EMERGENCY_STOP_ATR*tr.atr;',
    'double rawStop="Buy".equals(hedgeSide)?entryRef-HEDGE_EMERGENCY_STOP_ATR*triggerAtr:entryRef+HEDGE_EMERGENCY_STOP_ATR*triggerAtr;'
)
replace_once(
    'app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
    'h.initialStop=stopQ.doubleValue(); h.currentStop=stopQ.doubleValue(); h.atr=tr.atr; h.takerFee=tr.takerFee;',
    'h.initialStop=stopQ.doubleValue(); h.currentStop=stopQ.doubleValue(); h.atr=triggerAtr; h.takerFee=tr.takerFee;'
)

replace_once(
    'app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
    '        if(hp==null){\n            if(("PENDING".equals(h.state)||"OPEN_UNKNOWN".equals(h.state)||"CLOSE_UNKNOWN".equals(h.state)) && System.currentTimeMillis()-h.lastAttemptMs < HEDGE_UNKNOWN_WAIT_MS)return;',
    '        if(hp==null){\n            long age=System.currentTimeMillis()-h.lastAttemptMs;\n            if(age < HEDGE_OPEN_GRACE_MS)return;\n            if(("PENDING".equals(h.state)||"OPEN_UNKNOWN".equals(h.state)||"CLOSE_UNKNOWN".equals(h.state)) && age < HEDGE_UNKNOWN_WAIT_MS)return;'
)
replace_once(
    'app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java',
    '        if(inst==null)return;\n        if("PENDING".equals(h.state)||"OPEN_UNKNOWN".equals(h.state)||"REJECTED".equals(h.state))h.state="OPEN";',
    '        if(inst==null)return;\n        if(!"BE".equals(h.state)&&!"DOLLAR_LOCK".equals(h.state)&&!"CLOSE_PENDING".equals(h.state)&&!"CLOSE_UNKNOWN".equals(h.state))h.state="OPEN";'
)

anchor='    private boolean hedgeStopImproves(HedgeState h,Instrument inst,double candidate,double mark){\n'
helpers='''    private boolean hedgeAttemptAllowed(TradeState tr,HedgeState h){\n        if(h==null)return true;\n        if(!tr.tradeId.equals(h.primaryTradeId))return true;\n        String st=h.state==null?"":h.state;\n        if("PENDING".equals(st)||"OPEN_UNKNOWN".equals(st)||"CLOSE_PENDING".equals(st)||"CLOSE_UNKNOWN".equals(st))return false;\n        if("UNAVAILABLE_ONE_WAY".equals(st))return false;\n        if("OPEN".equals(st)||"BE".equals(st)||"DOLLAR_LOCK".equals(st)||"OPEN_RECOVERED".equals(st))return false;\n        return System.currentTimeMillis()-h.lastAttemptMs>=HEDGE_RETRY_MS;\n    }\n\n    private HedgeState recoverHedgeState(TradeState tr,Position hp){\n        HedgeState h=new HedgeState();\n        h.primaryTradeId=tr.tradeId; h.symbol=tr.symbol; h.side=hp.side; h.positionIdx=hp.positionIdx; h.state="OPEN_RECOVERED";\n        h.openedAtMs=System.currentTimeMillis(); h.lastAttemptMs=h.openedAtMs; h.entryPrice=hp.avgPrice; h.initialQty=hp.size; h.currentQty=hp.size;\n        h.initialStop=hp.stopLoss; h.currentStop=hp.stopLoss; h.atr=tr.entryAtr>0?tr.entryAtr:tr.atr; h.takerFee=tr.takerFee; h.spreadAtEntry=tr.spreadAtEntry;\n        h.highWater=hp.avgPrice; h.lowWater=hp.avgPrice; hedges.put(tr.symbol,h); db.upsertHedge(h);\n        db.event("WARN","HEDGE_RECOVERED","exchange hedge existed without live local state idx="+hp.positionIdx,tr.symbol,tr.tradeId);\n        log("HEDGE RECOVERED "+tr.symbol+" idx="+hp.positionIdx+" qty="+hp.size);\n        return h;\n    }\n\n'''
replace_once('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java', anchor, helpers+anchor)

old='''            if(stopImproves(tr,inst,dollarCandidate,mark)){\n                BigDecimal q=stopPrice(inst,tr.side,dollarCandidate);\n                api.setStop(tr.symbol,Decimals.fmt(q),positionIdx);\n                tr.currentStop=q.doubleValue();\n                tr.beArmed=true;\n                tr.state=protectedUsd>0?"DOLLAR_LOCK":"BE";\n                double actualProtected=estimatedProtectedProfitAtStop(tr,inst,tr.currentStop);\n                if(actualProtected>tr.protectedProfitUsdt)tr.protectedProfitUsdt=actualProtected;\n                log(String.format(Locale.US,\n                        "DOLLAR LOCK %s peak=$%.3f target=$%.2f protected~$%.3f stop=%s",\n                        tr.symbol,peakGrossUsd,protectedUsd,tr.protectedProfitUsdt,Decimals.fmt(q)));\n            }\n'''
new='''            if(stopImproves(tr,inst,dollarCandidate,mark)){\n                BigDecimal q=stopPrice(inst,tr.side,dollarCandidate);\n                api.setStop(tr.symbol,Decimals.fmt(q),positionIdx);\n                tr.currentStop=q.doubleValue();\n                tr.beArmed=true;\n                tr.state=protectedUsd>0?"DOLLAR_LOCK":"BE";\n                double actualProtected=estimatedProtectedProfitAtStop(tr,inst,tr.currentStop);\n                if(actualProtected>tr.protectedProfitUsdt)tr.protectedProfitUsdt=actualProtected;\n                log(String.format(Locale.US,\n                        "DOLLAR LOCK %s peak=$%.3f target=$%.2f protected~$%.3f stop=%s",\n                        tr.symbol,peakGrossUsd,protectedUsd,tr.protectedProfitUsdt,Decimals.fmt(q)));\n            } else if(dollarFloorAlreadyCrossed(tr,dollarCandidate,mark)){\n                double netNow=primaryEstimatedNet(tr,inst,mark);\n                if(netNow<=protectedUsd+0.05){\n                    marketCloseForMissedPrimaryLock(tr,inst,positionIdx,peakGrossUsd,protectedUsd,netNow);\n                    return;\n                }\n            }\n'''
replace_once('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java', old, new)

old='''            if(hedgeStopImproves(h,inst,candidate,mark)){\n                BigDecimal q=stopPrice(inst,h.side,candidate);\n                api.setStop(h.symbol,Decimals.fmt(q),h.positionIdx);\n                h.currentStop=q.doubleValue(); h.state=protectedUsd>0?"DOLLAR_LOCK":"BE";\n                double actual=estimatedHedgeProtectedAtStop(h,inst,h.currentStop); if(actual>h.protectedProfitUsdt)h.protectedProfitUsdt=actual;\n                log(String.format(Locale.US,"HEDGE LOCK %s peak=$%.3f target=$%.2f protected~$%.3f stop=%s",\n                        h.symbol,h.peakProfitUsdt,protectedUsd,h.protectedProfitUsdt,Decimals.fmt(q)));\n            }\n'''
new='''            if(hedgeStopImproves(h,inst,candidate,mark)){\n                BigDecimal q=stopPrice(inst,h.side,candidate);\n                api.setStop(h.symbol,Decimals.fmt(q),h.positionIdx);\n                h.currentStop=q.doubleValue(); h.state=protectedUsd>0?"DOLLAR_LOCK":"BE";\n                double actual=estimatedHedgeProtectedAtStop(h,inst,h.currentStop); if(actual>h.protectedProfitUsdt)h.protectedProfitUsdt=actual;\n                log(String.format(Locale.US,"HEDGE LOCK %s peak=$%.3f target=$%.2f protected~$%.3f stop=%s",\n                        h.symbol,h.peakProfitUsdt,protectedUsd,h.protectedProfitUsdt,Decimals.fmt(q)));\n            } else if(hedgeFloorAlreadyCrossed(h,candidate,mark)){\n                double netNow=estimatedHedgeNetAtMark(h,inst,mark);\n                if(netNow<=protectedUsd+0.05){\n                    marketCloseForMissedHedgeLock(tr,h,inst,hp,protectedUsd,netNow);\n                    return;\n                }\n            }\n'''
replace_once('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java', old, new)

anchor='    private boolean maybeReduce(TradeState tr,Instrument inst,double r,int positionIdx)throws Exception{\n'
helpers='''    private boolean dollarFloorAlreadyCrossed(TradeState tr,double candidate,double mark){\n        return "Buy".equals(tr.side)?mark<=candidate:mark>=candidate;\n    }\n\n    private boolean hedgeFloorAlreadyCrossed(HedgeState h,double candidate,double mark){\n        return "Buy".equals(h.side)?mark<=candidate:mark>=candidate;\n    }\n\n    private double estimatedHedgeNetAtMark(HedgeState h,Instrument inst,double mark){\n        if(h.currentQty<=0)return 0;\n        double gross="Buy".equals(h.side)?(mark-h.entryPrice)*h.currentQty:(h.entryPrice-mark)*h.currentQty;\n        double costPerUnit=h.entryPrice*(2*h.takerFee)+h.spreadAtEntry+2*inst.tickSize.doubleValue();\n        return gross-costPerUnit*h.currentQty;\n    }\n\n    private void marketCloseForMissedPrimaryLock(TradeState tr,Instrument inst,int positionIdx,double peak,double target,double netNow)throws Exception{\n        BigDecimal close=fullCloseQty(inst,tr.currentQty); if(close==null)return;\n        String opposite="Buy".equals(tr.side)?"Sell":"Buy"; double before=tr.currentQty;\n        api.reducePosition("PLC"+System.currentTimeMillis(),tr.symbol,opposite,Decimals.fmt(close),positionIdx);\n        Position after=api.waitReduced(tr.symbol,positionIdx,before,8_000L); tr.currentQty=after==null?0:after.size;\n        tr.state=after==null?"PROFIT_LOCK_CATCHUP_EXIT":"PROFIT_LOCK_CATCHUP"; tr.beArmed=true;\n        db.event("WARN","PROFIT_LOCK_CATCHUP","peak="+peak+" target="+target+" netNow="+netNow,tr.symbol,tr.tradeId);\n        log(String.format(Locale.US,"PROFIT LOCK CATCH-UP %s peak=$%.2f target=$%.2f netNow=$%.2f remain=%.8f",tr.symbol,peak,target,netNow,tr.currentQty));\n    }\n\n    private void marketCloseForMissedHedgeLock(TradeState tr,HedgeState h,Instrument inst,Position hp,double target,double netNow)throws Exception{\n        BigDecimal close=Decimals.floorStep(Decimals.bd(hp.size),inst.qtyStep); if(close.signum()<=0)return;\n        String closeSide="Buy".equals(h.side)?"Sell":"Buy"; double before=hp.size;\n        api.reducePosition("HLC"+System.currentTimeMillis(),h.symbol,closeSide,Decimals.fmt(close),h.positionIdx);\n        Position after=api.waitReduced(h.symbol,h.positionIdx,before,8_000L); h.currentQty=after==null?0:after.size; h.state=after==null?"CLOSED_LOCK_CATCHUP":"OPEN"; h.lastAttemptMs=System.currentTimeMillis(); db.upsertHedge(h);\n        db.event("WARN","HEDGE_LOCK_CATCHUP","target="+target+" netNow="+netNow,h.symbol,tr.tradeId);\n        log(String.format(Locale.US,"HEDGE LOCK CATCH-UP %s target=$%.2f netNow=$%.2f remain=%.8f",h.symbol,target,netNow,h.currentQty));\n    }\n\n'''
replace_once('app/src/main/java/com/keytrins/liveresearch/bot/LiveResearchEngine.java', anchor, helpers+anchor)

replace_once(
    'app/src/main/java/com/keytrins/liveresearch/MainActivity.java',
    '                b.append(String.format(Locale.US, "\\nPeak %+.2f   •   Protected ~%+.2f USDT",\n                        Math.max(0.0, t.peakProfitUsdt), Math.max(0.0, t.protectedProfitUsdt)));',
    '                b.append(String.format(Locale.US, "\\nPeak %+.2f   •   Protected ~%+.2f USDT",\n                        Math.max(0.0, t.peakProfitUsdt), Math.max(0.0, t.protectedProfitUsdt)));\n                double ha=t.entryAtr>0?t.entryAtr:t.atr;\n                if(ha>0){ double ht="Buy".equals(t.side)?t.entryPrice-0.15*ha:t.entryPrice+0.15*ha; b.append(String.format(Locale.US,"\\nHedge trigger %.8f • idx %d",ht,p.positionIdx)); }'
)

replace_once(
    'app/src/main/res/layout/activity_settings.xml',
    'HEDGE: при -0.15 ATR открыть 100% встречной позиции • аварийный SL 0.20 ATR',
    'HEDGE: при -0.15 ENTRY ATR открыть 100% встречной позиции • fail-safe не позже -0.15R • аварийный SL 0.20 ATR'
)
replace_once('app/build.gradle', 'versionCode 108', 'versionCode 109')
replace_once('app/build.gradle', "versionName '0.1.3.6'", "versionName '0.1.3.7'")
replace_once('app/src/main/java/com/keytrins/liveresearch/MainActivity.java', 'Live Research v0.1.3.6 Hedge ', 'Live Research v0.1.3.7 HedgeFix ')

print('v0.1.3.7 hedge reliability patch applied')
