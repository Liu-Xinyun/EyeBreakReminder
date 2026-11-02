package com.example.eyebreak20;

/** Pure Java time rules: elapsed timestamps, not wall-clock time. */
public final class TimingRules {
    private TimingRules() { }

    public static boolean isShortScreenOff(long offAt, long onAt, long thresholdMs) {
        return offAt > 0 && onAt >= offAt && onAt - offAt <= thresholdMs;
    }

    public static long moveTimestampAfterPause(long timestamp, long offAt, long onAt) {
        if (timestamp <= 0 || onAt < offAt) throw new IllegalArgumentException("invalid timestamps");
        return timestamp + (onAt - offAt);
    }

    public static boolean isWithinStrictBreak(long breakUntil, long now) {
        return breakUntil > now;
    }
}
