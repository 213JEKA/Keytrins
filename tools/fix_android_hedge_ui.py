from pathlib import Path

p = Path('live-research-android/app/src/main/java/com/keytrins/liveresearch/MainActivity.java')
text = p.read_text(encoding='utf-8')
start = text.index('    private String renderPositions(')
end = text.index('    private List<HistoryRow>', start)
block = r'''    private String renderPositions(Map<String, Position> positions, Map<String, TradeState> tracked, Map<String, HedgeState> hedgeStates) {
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

'''
p.write_text(text[:start] + block + text[end:], encoding='utf-8')
