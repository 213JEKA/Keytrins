package com.keytrins.liveresearch.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.keytrins.liveresearch.BotRuntime;
import com.keytrins.liveresearch.model.Signal;
import com.keytrins.liveresearch.model.TradeState;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class Db extends SQLiteOpenHelper {
    private static final int VERSION=7;
    public Db(Context c){super(c,"live_research_android.db",null,VERSION);}

    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, level TEXT, kind TEXT, symbol TEXT, trade_id TEXT, message TEXT)");
        db.execSQL("CREATE TABLE signals(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, symbol TEXT NOT NULL, signal_time INTEGER, decision TEXT, reason TEXT, score REAL, cost_r REAL, qty REAL, UNIQUE(symbol,signal_time,decision) ON CONFLICT IGNORE)");
        db.execSQL("CREATE TABLE trades(trade_id TEXT PRIMARY KEY, symbol TEXT UNIQUE NOT NULL, side TEXT, opened_at INTEGER, entry REAL, initial_qty REAL, current_qty REAL, initial_stop REAL, current_stop REAL, stop_algo_id TEXT, risk_distance REAL, target_risk REAL, atr REAL, entry_atr REAL DEFAULT 0, taker_fee REAL, spread REAL, cost_r REAL, state TEXT, high_water REAL, low_water REAL, peak_profit REAL DEFAULT 0, protected_profit REAL DEFAULT 0, reduced INTEGER, be_armed INTEGER, trailing INTEGER, structure_break INTEGER, structure_break_time INTEGER, balance_open REAL DEFAULT 0)");
        db.execSQL("CREATE TABLE closed_trades(id INTEGER PRIMARY KEY AUTOINCREMENT, trade_id TEXT, symbol TEXT, side TEXT, opened_at INTEGER, closed_at INTEGER, entry REAL, initial_qty REAL, target_risk REAL, gross REAL, fees REAL, funding REAL, net REAL, net_r REAL, peak_profit REAL DEFAULT 0, protected_profit REAL DEFAULT 0, reduced INTEGER, be_armed INTEGER, trailing INTEGER, balance_open REAL DEFAULT 0, balance_close REAL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_closed_time ON closed_trades(closed_at DESC)");
    }

    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){
        try{db.execSQL("CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, level TEXT, kind TEXT, symbol TEXT, trade_id TEXT, message TEXT)");}catch(Exception ignored){}
        try{db.execSQL("CREATE TABLE IF NOT EXISTS signals(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, symbol TEXT NOT NULL, signal_time INTEGER, decision TEXT, reason TEXT, score REAL, cost_r REAL, qty REAL, UNIQUE(symbol,signal_time,decision) ON CONFLICT IGNORE)");}catch(Exception ignored){}
        try{db.execSQL("CREATE TABLE IF NOT EXISTS closed_trades(id INTEGER PRIMARY KEY AUTOINCREMENT, trade_id TEXT, symbol TEXT, side TEXT, opened_at INTEGER, closed_at INTEGER, entry REAL, initial_qty REAL, target_risk REAL, gross REAL, fees REAL, funding REAL, net REAL, net_r REAL, peak_profit REAL DEFAULT 0, protected_profit REAL DEFAULT 0, reduced INTEGER, be_armed INTEGER, trailing INTEGER, balance_open REAL DEFAULT 0, balance_close REAL DEFAULT 0)");}catch(Exception ignored){}
        add(db,"trades","peak_profit REAL DEFAULT 0"); add(db,"trades","protected_profit REAL DEFAULT 0");
        add(db,"trades","entry_atr REAL DEFAULT 0"); add(db,"trades","stop_algo_id TEXT"); add(db,"trades","balance_open REAL DEFAULT 0");
        add(db,"closed_trades","peak_profit REAL DEFAULT 0"); add(db,"closed_trades","protected_profit REAL DEFAULT 0");
        add(db,"closed_trades","balance_open REAL DEFAULT 0"); add(db,"closed_trades","balance_close REAL DEFAULT 0");
        try{db.execSQL("CREATE INDEX IF NOT EXISTS idx_closed_time ON closed_trades(closed_at DESC)");}catch(Exception ignored){}
    }
    private static void add(SQLiteDatabase db,String table,String def){try{db.execSQL("ALTER TABLE "+table+" ADD COLUMN "+def);}catch(Exception ignored){}}

    public synchronized void event(String level,String kind,String message,String symbol,String tradeId){
        ContentValues v=new ContentValues(); v.put("ts",System.currentTimeMillis());v.put("level",level);v.put("kind",kind);v.put("symbol",symbol);v.put("trade_id",tradeId);v.put("message",message);
        getWritableDatabase().insert("events",null,v);
    }
    public synchronized void logSignal(String symbol,String decision,String reason,Signal sig,double qty,double costR){
        ContentValues v=new ContentValues();v.put("ts",System.currentTimeMillis());v.put("symbol",symbol);v.put("signal_time",sig==null?0:sig.signalTimeMs);v.put("decision",decision);v.put("reason",reason);v.put("score",sig==null?0:sig.trendScore);v.put("cost_r",costR);v.put("qty",qty);
        getWritableDatabase().insertWithOnConflict("signals",null,v,SQLiteDatabase.CONFLICT_IGNORE);
        if("REJECT".equals(decision))BotRuntime.recordDecision(symbol,"ENTRY_"+reason);
    }
    public synchronized boolean hasEntryForSignal(String symbol,long signalTime){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM signals WHERE symbol=? AND signal_time=? AND decision IN ('ENTRY_ATTEMPT','ENTRY') LIMIT 1",new String[]{symbol,Long.toString(signalTime)})){return c.moveToFirst();}
    }
    public synchronized void upsertTrade(TradeState t){
        if(t.balanceAtOpen<=0&&BotRuntime.balance>0)t.balanceAtOpen=BotRuntime.balance;
        getWritableDatabase().insertWithOnConflict("trades",null,tradeValues(t),SQLiteDatabase.CONFLICT_REPLACE);
    }
    public synchronized void deleteTrade(String symbol){getWritableDatabase().delete("trades","symbol=?",new String[]{symbol});}
    public synchronized Map<String,TradeState> openTrades(){
        Map<String,TradeState> out=new HashMap<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT * FROM trades",null)){
            while(c.moveToNext()){
                TradeState t=new TradeState();t.tradeId=s(c,"trade_id");t.symbol=s(c,"symbol");t.side=s(c,"side");t.state=s(c,"state");t.openedAtMs=l(c,"opened_at");
                t.entryPrice=d(c,"entry");t.initialQty=d(c,"initial_qty");t.currentQty=d(c,"current_qty");t.initialStop=d(c,"initial_stop");t.currentStop=d(c,"current_stop");
                t.stopAlgoId=optionalString(c,"stop_algo_id");t.riskDistance=d(c,"risk_distance");t.targetRiskUsdt=d(c,"target_risk");t.atr=d(c,"atr");t.entryAtr=optionalDouble(c,"entry_atr",t.atr);
                t.takerFee=d(c,"taker_fee");t.spreadAtEntry=d(c,"spread");t.costREst=d(c,"cost_r");t.highWater=d(c,"high_water");t.lowWater=d(c,"low_water");
                t.peakProfitUsdt=optionalDouble(c,"peak_profit",0);t.protectedProfitUsdt=optionalDouble(c,"protected_profit",0);t.reduced=i(c,"reduced")!=0;t.beArmed=i(c,"be_armed")!=0;t.trailing=i(c,"trailing")!=0;t.structureBreak=i(c,"structure_break")!=0;t.structureBreakTimeMs=l(c,"structure_break_time");t.balanceAtOpen=optionalDouble(c,"balance_open",0);
                out.put(t.symbol,t);
            }
        }return out;
    }
    public synchronized void closeTrade(TradeState t,long closedAt,double gross,double fees,double funding,double net){
        ContentValues v=new ContentValues();v.put("trade_id",t.tradeId);v.put("symbol",t.symbol);v.put("side",t.side);v.put("opened_at",t.openedAtMs);v.put("closed_at",closedAt);v.put("entry",t.entryPrice);v.put("initial_qty",t.initialQty);v.put("target_risk",t.targetRiskUsdt);v.put("gross",gross);v.put("fees",fees);v.put("funding",funding);v.put("net",net);v.put("net_r",t.targetRiskUsdt>0?net/t.targetRiskUsdt:0);v.put("peak_profit",t.peakProfitUsdt);v.put("protected_profit",t.protectedProfitUsdt);v.put("reduced",t.reduced?1:0);v.put("be_armed",t.beArmed?1:0);v.put("trailing",t.trailing?1:0);v.put("balance_open",t.balanceAtOpen);v.put("balance_close",BotRuntime.balance);
        getWritableDatabase().insert("closed_trades",null,v);
    }
    public synchronized String recentClosedTradesText(int limit){
        StringBuilder b=new StringBuilder();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT symbol,side,closed_at,net,net_r,peak_profit,protected_profit FROM closed_trades ORDER BY closed_at DESC LIMIT ?",new String[]{Integer.toString(Math.max(1,limit))})){
            while(c.moveToNext()){if(b.length()>0)b.append("\n\n");String time=new SimpleDateFormat("dd.MM HH:mm",Locale.US).format(new Date(c.getLong(2)));b.append(c.getString(0)).append(' ').append(c.getString(1)).append(" • ").append(time).append(String.format(Locale.US,"\nРезультат: %+.3f USDT (%+.2fR)\nPeak %+.2f • Protected ~%+.2f",c.getDouble(3),c.getDouble(4),c.getDouble(5),c.getDouble(6)));}
        }return b.length()==0?"Закрытых сделок пока нет.":b.toString();
    }
    private ContentValues tradeValues(TradeState t){
        ContentValues v=new ContentValues();v.put("trade_id",t.tradeId);v.put("symbol",t.symbol);v.put("side",t.side);v.put("opened_at",t.openedAtMs);v.put("entry",t.entryPrice);v.put("initial_qty",t.initialQty);v.put("current_qty",t.currentQty);v.put("initial_stop",t.initialStop);v.put("current_stop",t.currentStop);v.put("stop_algo_id",t.stopAlgoId);v.put("risk_distance",t.riskDistance);v.put("target_risk",t.targetRiskUsdt);v.put("atr",t.atr);v.put("entry_atr",t.entryAtr);v.put("taker_fee",t.takerFee);v.put("spread",t.spreadAtEntry);v.put("cost_r",t.costREst);v.put("state",t.state);v.put("high_water",t.highWater);v.put("low_water",t.lowWater);v.put("peak_profit",t.peakProfitUsdt);v.put("protected_profit",t.protectedProfitUsdt);v.put("reduced",t.reduced?1:0);v.put("be_armed",t.beArmed?1:0);v.put("trailing",t.trailing?1:0);v.put("structure_break",t.structureBreak?1:0);v.put("structure_break_time",t.structureBreakTimeMs);v.put("balance_open",t.balanceAtOpen);return v;
    }
    private static int idx(Cursor c,String n){return c.getColumnIndexOrThrow(n);}private static String s(Cursor c,String n){return c.getString(idx(c,n));}private static double d(Cursor c,String n){return c.getDouble(idx(c,n));}private static long l(Cursor c,String n){return c.getLong(idx(c,n));}private static int i(Cursor c,String n){return c.getInt(idx(c,n));}
    private static String optionalString(Cursor c,String n){int x=c.getColumnIndex(n);return x>=0&&!c.isNull(x)?c.getString(x):"";}private static double optionalDouble(Cursor c,String n,double def){int x=c.getColumnIndex(n);return x>=0&&!c.isNull(x)?c.getDouble(x):def;}
}
