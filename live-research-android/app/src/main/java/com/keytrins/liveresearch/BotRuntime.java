package com.keytrins.liveresearch;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BotRuntime {
    public static final AtomicBoolean running = new AtomicBoolean(false);
    public static volatile boolean liveArmed = false;
    public static volatile String status = "Остановлен";
    public static volatile int universe = 0, openPositions = 0, scans = 0, signals = 0, entries = 0, reductions = 0;
    public static volatile double balance = 0.0;
    public static volatile String positionsText = "Открытых сделок нет.";
    public static volatile String lastScanText = "Скан ещё не выполнялся.";
    private static final Deque<String> logs = new ArrayDeque<>();
    private static final LinkedHashMap<String,Integer> scanReasons = new LinkedHashMap<>();
    private static final Deque<String> scanCandidates = new ArrayDeque<>();
    private static long scanBucket = -1;

    public static synchronized void log(String text) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        logs.addFirst(t + "  " + text);
        while (logs.size() > 120) logs.removeLast();
    }

    public static synchronized String logText() {
        StringBuilder b = new StringBuilder();
        for (String x : logs) b.append(x).append('\n');
        return b.toString();
    }

    public static synchronized void recordDecision(String symbol, String reason) {
        long bucket = System.currentTimeMillis() / (15L * 60_000L);
        if (bucket != scanBucket) {
            scanBucket = bucket;
            scanReasons.clear();
            scanCandidates.clear();
        }
        scanReasons.put(reason, scanReasons.getOrDefault(reason, 0) + 1);
        if (reason.startsWith("SIGNAL")) {
            scanCandidates.addLast(symbol + " " + reason.replace("SIGNAL_", ""));
            while (scanCandidates.size() > 12) scanCandidates.removeFirst();
        }
        StringBuilder b = new StringBuilder();
        b.append("Решения текущего M15-скана: ");
        boolean first = true;
        for (Map.Entry<String,Integer> e : scanReasons.entrySet()) {
            if (!first) b.append(" • "); first = false;
            b.append(e.getKey()).append('=').append(e.getValue());
        }
        if (!scanCandidates.isEmpty()) {
            b.append("\nКандидаты: "); first = true;
            for (String x : scanCandidates) { if (!first) b.append(", "); first = false; b.append(x); }
        }
        lastScanText = b.toString();
    }

    private BotRuntime() {}
}
