package com.keytrins.kucoingrid;

import android.app.*;import android.content.*;import android.os.*;

public class GridService extends Service {
 public static final String ACTION_LOG="com.keytrins.kucoingrid.LOG"; private Thread worker; private GridEngine engine;
 @Override public void onCreate(){super.onCreate(); String ch="grid"; NotificationManager nm=getSystemService(NotificationManager.class); nm.createNotificationChannel(new NotificationChannel(ch,"Adaptive Grid",NotificationManager.IMPORTANCE_LOW)); Notification n=new Notification.Builder(this,ch).setContentTitle("KuCoin Adaptive Grid").setContentText("Робот запущен").setSmallIcon(android.R.drawable.stat_notify_sync).build(); startForeground(31,n);}
 @Override public int onStartCommand(Intent i,int flags,int id){ if(i==null)return START_NOT_STICKY; if("STOP".equals(i.getAction())){stopEngine();stopSelf();return START_NOT_STICKY;} stopEngine(); ApiClient api=new ApiClient(i.getStringExtra("key"),i.getStringExtra("secret"),i.getStringExtra("pass")); GridEngine.Config c=new GridEngine.Config(); c.symbol=i.getStringExtra("symbol"); c.capital=i.getDoubleExtra("capital",100);c.leverage=i.getIntExtra("lev",2);c.levels=i.getIntExtra("levels",5);c.test=i.getBooleanExtra("test",true); engine=new GridEngine(api,c,this::log); worker=new Thread(engine,"grid-engine");worker.start();log("Робот запущен: "+c.symbol+" / "+(c.test?"TEST":"LIVE"));return START_NOT_STICKY;}
 private void stopEngine(){if(engine!=null)engine.stop();if(worker!=null)worker.interrupt();engine=null;worker=null;}
 private void log(String s){Intent x=new Intent(ACTION_LOG).setPackage(getPackageName()).putExtra("text",s);sendBroadcast(x);}
 @Override public void onDestroy(){stopEngine();super.onDestroy();}
 @Override public android.os.IBinder onBind(Intent i){return null;}
}
