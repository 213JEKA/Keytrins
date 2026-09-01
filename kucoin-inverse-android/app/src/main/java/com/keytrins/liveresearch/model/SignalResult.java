package com.keytrins.liveresearch.model;

public final class SignalResult {
    public final Signal signal;
    public final String reason;
    public SignalResult(Signal signal, String reason) {
        this.signal = signal;
        this.reason = reason;
    }
}
