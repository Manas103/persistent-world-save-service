package com.manas.worldsave.timeseries;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pure round-trip tests of the delta-of-delta / delta codec, no RocksDB or ObjectStore involved. */
class SegmentCodecTest {

    @Test
    void roundTripsFixedIntervalSeriesExactly() {
        List<Point> points = new ArrayList<>();
        long t = 1_700_000_000_000L;
        for (int i = 0; i < 1000; i++) {
            points.add(new Point(t, Math.sin(i * 0.01) * 100));
            t += 1000; // fixed 1-second cadence: delta-of-delta should be a long run of zeros
        }
        byte[] encoded = SegmentCodec.encode(points);
        List<Point> decoded = SegmentCodec.decode(encoded);
        assertEquals(points, decoded);
    }

    @Test
    void roundTripsIrregularIntervalsAndNegativeValuesExactly() {
        Random random = new Random(42);
        List<Point> points = new ArrayList<>();
        long t = 0;
        for (int i = 0; i < 500; i++) {
            t += random.nextInt(5000) + 1;
            points.add(new Point(t, random.nextDouble() * 2000 - 1000));
        }
        byte[] encoded = SegmentCodec.encode(points);
        List<Point> decoded = SegmentCodec.decode(encoded);
        assertEquals(points, decoded);
    }

    @Test
    void roundTripsSinglePoint() {
        List<Point> points = List.of(new Point(12345L, 3.14));
        assertEquals(points, SegmentCodec.decode(SegmentCodec.encode(points)));
    }

    @Test
    void fixedIntervalSeriesTimestampStreamShrinksToOneByteVarintsViaDeltaOfDelta() {
        // The timestamp half of the encoding is where delta-of-delta earns its keep: a fixed
        // cadence collapses every delta-of-delta after the first to zero, a 1-byte varint each.
        // The value half is stored as plain 8-byte doubles (see class doc); Zstandard, not this
        // codec, is responsible for compressing repeated or regular value runs.
        List<Point> points = new ArrayList<>();
        long t = 0;
        for (int i = 0; i < 10_000; i++) {
            points.add(new Point(t, 42.0));
            t += 1000;
        }
        byte[] encoded = SegmentCodec.encode(points);
        assertEquals(points, SegmentCodec.decode(encoded));
        // 10,000 points raw as (long, double) pairs would be 160,000 bytes. Timestamps collapse to
        // ~1 byte each (9,999 one-byte deltas-of-delta plus one 8-byte first timestamp) and values
        // stay at 8 bytes each, so the expected size is close to 10,000 + 80,000 = 90,000 bytes,
        // well below the 160,000-byte fully-raw baseline.
        org.junit.jupiter.api.Assertions.assertTrue(encoded.length < 95_000,
                "expected the timestamp stream's delta-of-delta collapse to keep total size near 90KB, got " + encoded.length);
    }
}
