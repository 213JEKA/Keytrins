package com.keytrins.liveresearch;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BotRuntime {
    public static final AtomicBoolean running = new AtomicBoolean(false);
    public static volatile boolean liveArmed = false;
    public static volatile String status = "Остановлен";
    public static volatile int universe = 0, openPositions = 0, scans = 0, signals = 0, entries = 0, reductions = 0;
    public static volatile double balance = 0.0;
    private static final Deque<String> logs = new ArrayDeque<>();

    public static synchronized void log(String text) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        logs.addFirst(t + "  " + text);
        while (logs.size() > 80) logs.removeLast();
    }

    public static synchronized String logText() {
        StringBuilder b = new StringBuilder();
        for (String x : logs) b.append(x).append('\n');
        return b.toString();
    }

    private BotRuntime() {}
}
