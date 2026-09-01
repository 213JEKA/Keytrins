package com.keytrins.kucoingrid;

import android.app.*;import android.content.*;

public class GridService extends Service {
 public static final String ACTION_LOG="com.keytrins.kucoingrid.LOG"; private volatile Thread worker; private volatile GridEngine engine;
 @Override public void onCreate(){super.onCreate();String ch="grid";NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel(ch,"Adaptive Grid",NotificationManager.IMPORTANCE_LOW));Notification n=new Notification.Builder(this,ch).setContentTitle("KuCoin Adaptive Grid 0.2.0").setContentText("Робот запущен").setSmallIcon(android.R.drawable.stat_notify_sync).build();startForeground(31,n);}
 @Override public int onStartCommand(Intent i,int flags,int id){
  if(i==null)return START_NOT_STICKY;
  if("STOP".equals(i.getAction())){requestStop(true);return START_NOT_STICKY;}
  Thread old=worker;if(old!=null&&old.isAlive()){log("Робот уже работает. Сначала нажмите СТОП и дождитесь сообщения о завершении.");return START_NOT_STICKY;}
  ApiClient api=new ApiClient(i.getStringExtra("key"),i.getStringExtra("secret"),i.getStringExtra("pass"));GridEngine.Config c=new GridEngine.Config();c.symbol=i.getStringExtra("symbol");c.capital=i.getDoubleExtra("capital",100);c.leverage=i.getIntExtra("lev",2);c.levels=i.getIntExtra("levels",5);c.test=i.getBooleanExtra("test",true);c.maxDailyLossPct=i.getDoubleExtra("dayLoss",3);c.maxPositionLossPct=i.getDoubleExtra("posLoss",1.5);c.protectStepUsd=i.getDoubleExtra("protectStep",1);c.protectLagUsd=i.getDoubleExtra("protectLag",1);engine=new GridEngine(this,api,c,this::log);worker=new Thread(engine,"grid-engine");worker.start();log("Робот запущен: "+c.symbol+" / "+(c.test?"TEST":"LIVE")+" | v0.2.0");return START_NOT_STICKY;
 }
 private void requestStop(boolean stopService){final GridEngine e=engine;final Thread w=worker;if(e!=null)e.stop();if(w!=null)w.interrupt();if(e==null||w==null){if(stopService)stopSelf();return;}log("Остановка: завершаю цикл и защищаю открытую позицию...");new Thread(()->{try{w.join(25000);}catch(InterruptedException ignored){}finally{if(worker==w){engine=null;worker=null;}if(stopService)stopSelf();}},"grid-stop").start();}
 private void log(String s){Intent x=new Intent(ACTION_LOG).setPackage(getPackageName()).putExtra("text",s);sendBroadcast(x);}
 @Override public void onDestroy(){GridEngine e=engine;Thread w=worker;if(e!=null)e.stop();if(w!=null)w.interrupt();super.onDestroy();}
 @Override public android.os.IBinder onBind(Intent i){return null;}
}
