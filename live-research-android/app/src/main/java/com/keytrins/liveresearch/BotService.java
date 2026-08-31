package com.keytrins.liveresearch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.keytrins.liveresearch.bot.LiveResearchEngine;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BotService extends Service {
    private static final String CHANNEL = "okx_inverse_bot";
    private static final int NOTIFY_ID = 101;
    private ExecutorService executor;
    private volatile LiveResearchEngine engine;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFY_ID, notification("Запуск…"));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (BotRuntime.running.getAndSet(true)) return START_NOT_STICKY;
        SettingsStore.Snapshot s = new SettingsStore(this).load();
        BotRuntime.status = s.live ? "OKX LIVE • запуск" : "OKX OBSERVE • запуск";
        BotRuntime.log(BotRuntime.status);
        updateNotification(BotRuntime.status);

        executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            try {
                engine = new LiveResearchEngine(this, s);
                engine.run(() -> !Thread.currentThread().isInterrupted() && BotRuntime.running.get(), this::updateNotification);
            } catch (Throwable t) {
                BotRuntime.status = "ОШИБКА: " + t.getMessage();
                BotRuntime.log(BotRuntime.status);
            } finally {
                BotRuntime.running.set(false);
                stopSelf();
            }
        });
        return START_NOT_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "OKX Inverse robot", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Инверсные OKX SWAP входы и защита безубытком");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    private Notification notification(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return b.setContentTitle("OKX Inverse")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification(String text) {
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFY_ID, notification(text));
    }

    @Override public void onDestroy() {
        BotRuntime.running.set(false);
        if (engine != null) engine.close();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
