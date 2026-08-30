package com.keytrins.liveresearch.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.keytrins.liveresearch.BotRuntime;
import com.keytrins.liveresearch.model.HedgeState;
import com.keytrins.liveresearch.model.Signal;
import com.keytrins.liveresearch.model.TradeState;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class Db extends SQLiteOpenHelper {
    private static final int VERSION = 4;

    public Db(Context c) { super(c, "live_research_android.db", null, VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, level TEXT, kind TEXT, symbol TEXT, trade_id TEXT, message TEXT)");
        db.execSQL("CREATE TABLE signals(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, symbol TEXT NOT NULL, signal_time INTEGER, decision TEXT, reason TEXT, score REAL, cost_r REAL, qty REAL, UNIQUE(symbol, signal_time, decision) ON CONFLICT IGNORE)");
        db.execSQL("CREATE TABLE scan_decisions(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, scan_time INTEGER, symbol TEXT NOT NULL, decision TEXT, reason TEXT)");
        db.execSQL("CREATE TABLE trades(trade_id TEXT PRIMARY KEY, symbol TEXT UNIQUE NOT NULL, side TEXT, opened_at INTEGER, entry REAL, initial_qty REAL, current_qty REAL, initial_stop REAL, current_stop REAL, risk_distance REAL, target_risk REAL, atr REAL, taker_fee REAL, spread REAL, cost_r REAL, state TEXT, high_water REAL, low_water REAL, peak_profit REAL DEFAULT 0, protected_profit REAL DEFAULT 0, reduced INTEGER, be_armed INTEGER, trailing INTEGER, structure_break INTEGER, structure_break_time INTEGER, balance_open REAL DEFAULT 0)");
        db.execSQL("CREATE TABLE hedges(primary_trade_id TEXT PRIMARY KEY, symbol TEXT UNIQUE NOT NULL, side TEXT, position_idx INTEGER, state TEXT, opened_at INTEGER, last_attempt INTEGER, entry REAL, initial_qty REAL, current_qty REAL, initial_stop REAL, current_stop REAL, atr REAL, taker_fee REAL, spread REAL, high_water REAL, low_water REAL, peak_profit REAL DEFAULT 0, protected_profit REAL DEFAULT 0)");
        db.execSQL("CREATE TABLE closed_trades(id INTEGER PRIMARY KEY AUTOINCREMENT, trade_id TEXT, symbol TEXT, side TEXT, opened_at INTEGER, closed_at INTEGER, entry REAL, initial_qty REAL, target_risk REAL, gross REAL, fees REAL, funding REAL, net REAL, net_r REAL, peak_profit REAL DEFAULT 0, protected_profit REAL DEFAULT 0, reduced INTEGER, be_armed INTEGER, trailing INTEGER, balance_open REAL DEFAULT 0, balance_close REAL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_scan_time ON scan_decisions(scan_time)");
        db.execSQL("CREATE INDEX idx_closed_time ON closed_trades(closed_at DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try { db.execSQL("ALTER TABLE trades ADD COLUMN balance_open REAL DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE closed_trades ADD COLUMN balance_open REAL DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE closed_trades ADD COLUMN balance_close REAL DEFAULT 0"); } catch (Exception ignored) {}
            db.execSQL("CREATE TABLE IF NOT EXISTS scan_decisions(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, scan_time INTEGER, symbol TEXT NOT NULL, decision TEXT, reason TEXT)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_scan_time ON scan_decisions(scan_time)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_closed_time ON closed_trades(closed_at DESC)");
        }
        if (oldVersion < 3) {
            try { db.execSQL("ALTER TABLE trades ADD COLUMN peak_profit REAL DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE trades ADD COLUMN protected_profit REAL DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE closed_trades ADD COLUMN peak_profit REAL DEFAULT 0"); } catch (Exception ignored) {}
            try { db.execSQL("ALTER TABLE closed_trades ADD COLUMN protected_profit REAL DEFAULT 0"); } catch (Exception ignored) {}
        }
        if (oldVersion < 4) {
            db.execSQL("CREATE TABLE IF NOT EXISTS hedges(primary_trade_id TEXT PRIMARY KEY, symbol TEXT UNIQUE NOT NULL, side TEXT, position_idx INTEGER, state TEXT, opened_at INTEGER, last_attempt INTEGER, entry REAL, initial_qty REAL, current_qty REAL, initial_stop REAL, current_stop REAL, atr REAL, taker_fee REAL, spread REAL, high_water REAL, low_water REAL, peak_profit REAL DEFAULT 0, protected_profit REAL DEFAULT 0)");
        }
    }

    public synchronized void event(String level, String kind, String message, String symbol, String tradeId) {
        ContentValues v = new ContentValues();
        v.put("ts", System.currentTimeMillis()); v.put("level", level); v.put("kind", kind);
        v.put("symbol", symbol); v.put("trade_id", tradeId); v.put("message", message);
        getWritableDatabase().insert("events", null, v);
    }

    public synchronized void scanDecision(long scanTime, String symbol, String decision, String reason) {
        ContentValues v = new ContentValues();
        v.put("ts", System.currentTimeMillis()); v.put("scan_time", scanTime); v.put("symbol", symbol);
        v.put("decision", decision); v.put("reason", reason);
        getWritableDatabase().insert("scan_decisions", null, v);
    }

    public synchronized void logSignal(String symbol, String decision, String reason, Signal sig, double qty, double costR) {
        ContentValues v = new ContentValues();
        v.put("ts", System.currentTimeMillis()); v.put("symbol", symbol);
        v.put("signal_time", sig == null ? 0 : sig.signalTimeMs); v.put("decision", decision); v.put("reason", reason);
        v.put("score", sig == null ? 0 : sig.trendScore); v.put("cost_r", costR); v.put("qty", qty);
        getWritableDatabase().insertWithOnConflict("signals", null, v, SQLiteDatabase.CONFLICT_IGNORE);
        if ("REJECT".equals(decision)) BotRuntime.recordDecision(symbol, "ENTRY_" + reason);
    }

    public synchronized boolean hasEntryForSignal(String symbol, long signalTime) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM signals WHERE symbol=? AND signal_time=? AND decision IN ('ENTRY_ATTEMPT','ENTRY') LIMIT 1",
                new String[]{symbol, Long.toString(signalTime)})) {
            return c.moveToFirst();
        }
    }

    public synchronized void upsertTrade(TradeState t) {
        if (t.balanceAtOpen <= 0 && BotRuntime.balance > 0) t.balanceAtOpen = BotRuntime.balance;
        ContentValues v = tradeValues(t);
        getWritableDatabase().insertWithOnConflict("trades", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized void closeTrade(TradeState t, long closedAt, double gross, double fees, double funding, double net) {
        closeTrade(t, closedAt, gross, fees, funding, net, BotRuntime.balance);
    }

    public synchronized void closeTrade(TradeState t, long closedAt, double gross, double fees, double funding, double net, double balanceClose) {
        ContentValues v = new ContentValues();
        v.put("trade_id",t.tradeId); v.put("symbol",t.symbol); v.put("side",t.side); v.put("opened_at",t.openedAtMs);
        v.put("closed_at",closedAt); v.put("entry",t.entryPrice); v.put("initial_qty",t.initialQty); v.put("target_risk",t.targetRiskUsdt);
        v.put("gross",gross); v.put("fees",fees); v.put("funding",funding); v.put("net",net);
        v.put("net_r",t.targetRiskUsdt > 0 ? net/t.targetRiskUsdt : 0); v.put("peak_profit", t.peakProfitUsdt); v.put("protected_profit", t.protectedProfitUsdt);
        v.put("reduced",t.reduced?1:0); v.put("be_armed",t.beArmed?1:0); v.put("trailing",t.trailing?1:0);
        v.put("balance_open", t.balanceAtOpen); v.put("balance_close", balanceClose);
        getWritableDatabase().insert("closed_trades",null,v);
    }

    public synchronized void deleteTrade(String symbol) {
        getWritableDatabase().delete("trades", "symbol=?", new String[]{symbol});
    }

    public synchronized Map<String, TradeState> openTrades() {
        Map<String, TradeState> out = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM trades", null)) {
            while (c.moveToNext()) {
                TradeState t = new TradeState();
                t.tradeId = s(c, "trade_id"); t.symbol = s(c, "symbol"); t.side = s(c, "side"); t.state = s(c, "state");
                t.openedAtMs = l(c, "opened_at"); t.entryPrice = d(c, "entry"); t.initialQty = d(c, "initial_qty");
                t.currentQty = d(c, "current_qty"); t.initialStop = d(c, "initial_stop"); t.currentStop = d(c, "current_stop");
                t.riskDistance = d(c, "risk_distance"); t.targetRiskUsdt = d(c, "target_risk"); t.atr = d(c, "atr");
                t.takerFee = d(c, "taker_fee"); t.spreadAtEntry = d(c, "spread"); t.costREst = d(c, "cost_r");
                t.highWater = d(c, "high_water"); t.lowWater = d(c, "low_water");
                int peakIdx = c.getColumnIndex("peak_profit"); t.peakProfitUsdt = peakIdx >= 0 ? c.getDouble(peakIdx) : 0;
                int protectedIdx = c.getColumnIndex("protected_profit"); t.protectedProfitUsdt = protectedIdx >= 0 ? c.getDouble(protectedIdx) : 0;
                t.reduced = i(c, "reduced") != 0; t.beArmed = i(c, "be_armed") != 0; t.trailing = i(c, "trailing") != 0;
                t.structureBreak = i(c, "structure_break") != 0; t.structureBreakTimeMs = l(c, "structure_break_time");
                int balIdx = c.getColumnIndex("balance_open"); t.balanceAtOpen = balIdx >= 0 ? c.getDouble(balIdx) : 0;
                out.put(t.symbol, t);
            }
        }
        return out;
    }


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

    public synchronized String recentClosedTradesText(int limit) {
        StringBuilder b = new StringBuilder();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT symbol,side,closed_at,net,net_r,fees,funding,balance_open,balance_close FROM closed_trades ORDER BY closed_at DESC LIMIT ?",
                new String[]{Integer.toString(Math.max(1, limit))})) {
            while (c.moveToNext()) {
                if (b.length() > 0) b.append("\n\n");
                long closed = c.getLong(2);
                double net = c.getDouble(3), netR = c.getDouble(4), fees = c.getDouble(5), funding = c.getDouble(6);
                double bo = c.getDouble(7), bc = c.getDouble(8);
                String time = new SimpleDateFormat("dd.MM HH:mm", Locale.US).format(new Date(closed));
                b.append(c.getString(0)).append(' ').append(c.getString(1)).append(" • ").append(time)
                        .append(String.format(Locale.US, "\nРезультат: %+.3f USDT (%+.2fR) • fee %.3f • funding %+.3f", net, netR, fees, funding));
                if (bo > 0 || bc > 0) b.append(String.format(Locale.US, "\nБаланс: %.2f → %.2f USDT", bo, bc));
            }
        }
        return b.length() == 0 ? "Закрытых сделок пока нет." : b.toString();
    }

    private ContentValues tradeValues(TradeState t) {
        ContentValues v = new ContentValues();
        v.put("trade_id", t.tradeId); v.put("symbol", t.symbol); v.put("side", t.side); v.put("opened_at", t.openedAtMs);
        v.put("entry", t.entryPrice); v.put("initial_qty", t.initialQty); v.put("current_qty", t.currentQty);
        v.put("initial_stop", t.initialStop); v.put("current_stop", t.currentStop); v.put("risk_distance", t.riskDistance);
        v.put("target_risk", t.targetRiskUsdt); v.put("atr", t.atr); v.put("taker_fee", t.takerFee);
        v.put("spread", t.spreadAtEntry); v.put("cost_r", t.costREst); v.put("state", t.state);
        v.put("high_water", t.highWater); v.put("low_water", t.lowWater); v.put("peak_profit", t.peakProfitUsdt); v.put("protected_profit", t.protectedProfitUsdt);
        v.put("reduced", t.reduced ? 1 : 0); v.put("be_armed", t.beArmed ? 1 : 0); v.put("trailing", t.trailing ? 1 : 0); v.put("structure_break", t.structureBreak ? 1 : 0);
        v.put("structure_break_time", t.structureBreakTimeMs); v.put("balance_open", t.balanceAtOpen);
        return v;
    }

    private static int idx(Cursor c, String n) { return c.getColumnIndexOrThrow(n); }
    private static String s(Cursor c, String n) { return c.getString(idx(c,n)); }
    private static double d(Cursor c, String n) { return c.getDouble(idx(c,n)); }
    private static long l(Cursor c, String n) { return c.getLong(idx(c,n)); }
    private static int i(Cursor c, String n) { return c.getInt(idx(c,n)); }
}
