package com.manas.worldsave.timeseries;

/** One (timestamp, value) sample of a time series. Timestamps are epoch millis, strictly increasing within a segment. */
public record Point(long timestampMillis, double value) {
}
