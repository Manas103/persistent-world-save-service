package com.manas.worldsave.timeseries;

import java.nio.charset.StandardCharsets;

/**
 * One segment's metadata row, keyed in RocksDB by (series, time range, version). The object
 * itself (the compressed points) lives in the pluggable ObjectStore under objectKey; this row
 * is only the pointer plus the numbers needed to report compression and verify a restore.
 */
public record SegmentManifestEntry(
        String series,
        long startTimeMillis,
        long endTimeMillis,
        int version,
        String objectKey,
        int pointCount,
        int rawBytes,
        int compressedBytes,
        String sha256Hex) {

    public byte[] toBytes() {
        String line = String.join("|",
                series, Long.toString(startTimeMillis), Long.toString(endTimeMillis),
                Integer.toString(version), objectKey, Integer.toString(pointCount),
                Integer.toString(rawBytes), Integer.toString(compressedBytes), sha256Hex);
        return line.getBytes(StandardCharsets.UTF_8);
    }

    public static SegmentManifestEntry fromBytes(byte[] data) {
        String line = new String(data, StandardCharsets.UTF_8);
        String[] parts = line.split("\\|", 9);
        return new SegmentManifestEntry(
                parts[0], Long.parseLong(parts[1]), Long.parseLong(parts[2]),
                Integer.parseInt(parts[3]), parts[4], Integer.parseInt(parts[5]),
                Integer.parseInt(parts[6]), Integer.parseInt(parts[7]), parts[8]);
    }
}
