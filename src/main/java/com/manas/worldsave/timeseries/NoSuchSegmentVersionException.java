package com.manas.worldsave.timeseries;

/** Thrown when a (series, range, version) tuple has no manifest row. */
public class NoSuchSegmentVersionException extends RuntimeException {
    public NoSuchSegmentVersionException(String series, long start, long end, int version) {
        super("no such segment version: series=" + series + " range=[" + start + "," + end + ") version=" + version);
    }
}
