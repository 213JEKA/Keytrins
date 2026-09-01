package com.keytrins.liveresearch;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.keytrins.liveresearch.model.Position;
import com.keytrins.liveresearch.model.TradeState;
import com.keytrins.liveresearch.net.KucoinClient;
import com.keytrins.liveresearch.storage.Db;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends android.app.Activity {
    private TextView statusText,balanceText,incomeText,metricsText,positionsText,historyText;
    private SettingsStore store;private Db dashboardDb;private final Handler ui=new Handler(Looper.getMainLooper());private final ExecutorService worker=Executors.newSingleThreadExecutor();private final AtomicBoolean busy=new AtomicBoolean(false);private int refreshTick=0;

    @Override protected void onCreate(Bundle savedInstanceState){super.onCreate(savedInstanceState);setContentView(R.layout.activity_main);store=new SettingsStore(this);dashboardDb=new Db(this);statusText=findViewById(R.id.statusText);balanceText=findViewById(R.id.balanceText);incomeText=findViewById(R.id.incomeText);metricsText=findViewById(R.id.metricsText);positionsText=findViewById(R.id.positionsText);historyText=findViewById(R.id.historyText);findViewById(R.id.settingsButton).setOnClickListener(v->startActivity(new Intent(this,SettingsActivity.class)));findViewById(R.id.startButton).setOnClickListener(v->startBot());findViewById(R.id.stopButton).setOnClickListener(v->stopBot());historyText.setText(dashboardDb.recentClosedTradesText(3));requestNotificationPermission();BotRuntime.log("KuCoin Inverse v0.1.1 готов • profit-lock $1/$1");refreshUiLoop();}

    private void requestNotificationPermission(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},7);}
    private void startBot(){SettingsStore.Snapshot s=store.load();if(s.live&&(s.apiKey.isEmpty()||s.apiSecret.isEmpty()||s.apiPassphrase.isEmpty())){Toast.makeText(this,"Добавьте KuCoin API key + secret + passphrase в ⚙",Toast.LENGTH_LONG).show();return;}if(s.live&&!BotRuntime.liveArmed){EditText input=new EditText(this);input.setHint("Введите LIVE");input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);new AlertDialog.Builder(this).setTitle("Запустить реальную торговлю KuCoin?").setMessage("Подтвердите LIVE. Аккаунт должен быть One-Way.").setView(input).setPositiveButton("Запустить",(d,w)->{if(!"LIVE".equals(input.getText().toString().trim())){Toast.makeText(this,"LIVE не подтверждён",Toast.LENGTH_SHORT).show();return;}BotRuntime.liveArmed=true;startBotInternal();}).setNegativeButton("Отмена",null).show();return;}startBotInternal();}
    private void startBotInternal(){Intent i=new Intent(this,BotService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    private void stopBot(){stopService(new Intent(this,BotService.class));BotRuntime.running.set(false);BotRuntime.status="Остановлен";BotRuntime.log("Остановлен пользователем");}

    private void refreshUiLoop(){ui.postDelayed(new Runnable(){@Override public void run(){statusText.setText(BotRuntime.status);metricsText.setText(String.format(Locale.US,"Universe  %d     •     Открыто  %d\nСканов  %d     •     Сигналов  %d\nВходов  %d     •     Сокращений  %d",BotRuntime.universe,BotRuntime.openPositions,BotRuntime.scans,BotRuntime.signals,BotRuntime.entries,BotRuntime.reductions));if((refreshTick++%2)==0)pollDashboard();historyText.setText(dashboardDb.recentClosedTradesText(3));ui.postDelayed(this,1000);}},250);}

    private void pollDashboard(){if(!busy.compareAndSet(false,true))return;SettingsStore.Snapshot s=store.load();if(s.apiKey.isEmpty()||s.apiSecret.isEmpty()||s.apiPassphrase.isEmpty()){busy.set(false);return;}worker.submit(()->{try{double balance;Map<String,Position> positions;try(KucoinClient api=new KucoinClient(s)){api.getInstruments();balance=api.walletBalanceUsdt();positions=api.openPositions();}Map<String,TradeState> tracked=dashboardDb.openTrades();double unreal=0;for(Position p:positions.values())unreal+=p.unrealisedPnl;BotRuntime.balance=balance;BotRuntime.openPositions=positions.size();String pt=renderPositions(positions,tracked);double baseline=store.baselineBalance();if(Double.isNaN(baseline)){store.seedBaselineIfAbsent(balance);baseline=store.baselineBalance();}double income=balance-baseline+unreal;final double b=balance,inc=income;ui.post(()->{balanceText.setText(String.format(Locale.US,"%.2f",b));incomeText.setText(String.format(Locale.US,"%+.2f",inc));incomeText.setTextColor(getColor(inc>=0?R.color.ok:R.color.danger));positionsText.setText(pt);});}catch(Exception e){BotRuntime.log("KUCOIN DASHBOARD: "+e);}finally{busy.set(false);}});}

    private String renderPositions(Map<String,Position> positions,Map<String,TradeState> tracked){if(positions==null||positions.isEmpty())return "Открытых сделок нет.";List<Position> rows=new ArrayList<>(positions.values());rows.sort((a,b)->a.symbol.compareTo(b.symbol));StringBuilder b=new StringBuilder();for(Position p:rows){if(b.length()>0)b.append("\n\n");TradeState t=tracked.get(p.symbol);String direction="Buy".equals(p.side)?"LONG":"SHORT";b.append(p.symbol).append("  ").append(direction).append("  •  ").append(t==null?"KUCOIN":"INVERSE • "+t.state);b.append(String.format(Locale.US,"\nEntry %.8f   •   Mark %.8f   •   Contracts %.8f",p.avgPrice,p.markPrice,p.size));b.append(String.format(Locale.US,"\nPnL %+.3f USDT",p.unrealisedPnl));if(t!=null&&p.side.equals(t.side)){double r=t.riskDistance>0?("Buy".equals(t.side)?(p.markPrice-t.entryPrice)/t.riskDistance:(t.entryPrice-p.markPrice)/t.riskDistance):0;b.append(String.format(Locale.US,"   •   %+.2fR   •   SL %.8f",r,t.currentStop));b.append(String.format(Locale.US,"\nPeak %+.2f   •   Protected ~%+.2f USDT",Math.max(0,t.peakProfitUsdt),Math.max(0,t.protectedProfitUsdt)));}}return b.toString();}

    @Override protected void onDestroy(){if(dashboardDb!=null)dashboardDb.close();worker.shutdownNow();super.onDestroy();}
}
