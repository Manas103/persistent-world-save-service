package com.manas.worldsave.timeseries;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Encodes a sorted list of Points into a compact byte buffer: timestamps as delta-of-delta
 * zigzag varints (a fixed-interval series collapses to a run of zero deltas, which Zstandard
 * then compresses hard), values as exact IEEE-754 doubles with no delta encoding. Values were
 * originally stored as a previous-value delta and reconstructed by cumulative addition; see the
 * README Findings section for why that was reverted (floating-point rounding drift). All
 * compression of the value stream is left to Zstandard (ZstdBlob), which this is the wire format
 * for.
 */
public final class SegmentCodec {

    private SegmentCodec() {
    }

    public static byte[] encode(List<Point> points) {
        if (points.isEmpty()) {
            throw new IllegalArgumentException("cannot encode an empty segment");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(points.size() * 10);
        writeVarInt(out, points.size());

        long firstTs = points.get(0).timestampMillis();
        writeVarLong(out, firstTs);
        long prevTs = firstTs;
        long prevDelta = 0;
        for (int i = 1; i < points.size(); i++) {
            long ts = points.get(i).timestampMillis();
            long delta = ts - prevTs;
            long deltaOfDelta = delta - prevDelta;
            writeZigZagVarLong(out, deltaOfDelta);
            prevDelta = delta;
            prevTs = ts;
        }

        for (Point p : points) {
            writeDouble(out, p.value());
        }
        return out.toByteArray();
    }

    public static List<Point> decode(byte[] buf) {
        ByteBuffer bb = ByteBuffer.wrap(buf);
        int count = readVarInt(bb);
        long[] timestamps = new long[count];
        timestamps[0] = readVarLong(bb);
        long prevTs = timestamps[0];
        long prevDelta = 0;
        for (int i = 1; i < count; i++) {
            long deltaOfDelta = readZigZagVarLong(bb);
            long delta = prevDelta + deltaOfDelta;
            long ts = prevTs + delta;
            timestamps[i] = ts;
            prevDelta = delta;
            prevTs = ts;
        }

        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            values[i] = bb.getDouble();
        }

        List<Point> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(new Point(timestamps[i], values[i]));
        }
        return result;
    }

    private static void writeDouble(ByteArrayOutputStream out, double d) {
        long bits = Double.doubleToLongBits(d);
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (bits >>> shift) & 0xFF);
        }
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        writeVarLong(out, value & 0xFFFFFFFFL);
    }

    private static int readVarInt(ByteBuffer bb) {
        return (int) readVarLong(bb);
    }

    private static void writeVarLong(ByteArrayOutputStream out, long value) {
        long v = value;
        while (true) {
            int b = (int) (v & 0x7F);
            v >>>= 7;
            if (v == 0) {
                out.write(b);
                break;
            } else {
                out.write(b | 0x80);
            }
        }
    }

    private static long readVarLong(ByteBuffer bb) {
        long result = 0;
        int shift = 0;
        while (true) {
            byte b = bb.get();
            result |= ((long) (b & 0x7F)) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        return result;
    }

    private static void writeZigZagVarLong(ByteArrayOutputStream out, long value) {
        writeVarLong(out, (value << 1) ^ (value >> 63));
    }

    private static long readZigZagVarLong(ByteBuffer bb) {
        long z = readVarLong(bb);
        return (z >>> 1) ^ -(z & 1);
    }
}
