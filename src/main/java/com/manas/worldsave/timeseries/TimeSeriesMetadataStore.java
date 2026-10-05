package com.manas.worldsave.timeseries;

import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.RocksIterator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The RocksDB metadata plane, separated from the data backend entirely: this class never
 * touches ObjectStore and knows nothing about Zstandard or delta-of-delta encoding, only
 * (series, time range, version) -> SegmentManifestEntry. A range index lets queryRange find
 * every known (startTime, endTime) boundary for a series without scanning every version.
 */
public final class TimeSeriesMetadataStore implements AutoCloseable {

    public record RangeKey(long startTimeMillis, long endTimeMillis) {
    }

    private final RocksDB db;
    private final Options options;

    public TimeSeriesMetadataStore(Path dbDir) {
        RocksDB.loadLibrary();
        this.options = new Options().setCreateIfMissing(true);
        try {
            this.db = RocksDB.open(options, dbDir.toString());
        } catch (RocksDBException e) {
            throw new IllegalStateException("failed to open RocksDB metadata plane at " + dbDir, e);
        }
    }

    private static String rangeIndexKey(String series, long start, long end) {
        return series + "|ranges|" + start + "|" + end;
    }

    private static String latestPointerKey(String series, long start, long end) {
        return series + "|" + start + "|" + end + "|latest";
    }

    private static String versionedKey(String series, long start, long end, int version) {
        return series + "|" + start + "|" + end + "|v" + version;
    }

    /** Returns the next version number to use (1 if this (series, range) has never been written). */
    public synchronized int currentLatestVersion(String series, long start, long end) {
        try {
            byte[] v = db.get(latestPointerKey(series, start, end).getBytes(StandardCharsets.UTF_8));
            return v == null ? 0 : Integer.parseInt(new String(v, StandardCharsets.UTF_8));
        } catch (RocksDBException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Writes a new version's manifest row, advances the latest pointer, and records the range boundary. Returns the version written. */
    public synchronized int putNextVersion(String series, long start, long end, SegmentManifestEntry baseEntry) {
        int next = currentLatestVersion(series, start, end) + 1;
        SegmentManifestEntry withVersion = new SegmentManifestEntry(
                series, start, end, next, baseEntry.objectKey(), baseEntry.pointCount(),
                baseEntry.rawBytes(), baseEntry.compressedBytes(), baseEntry.sha256Hex());
        try {
            db.put(versionedKey(series, start, end, next).getBytes(StandardCharsets.UTF_8), withVersion.toBytes());
            db.put(latestPointerKey(series, start, end).getBytes(StandardCharsets.UTF_8),
                    Integer.toString(next).getBytes(StandardCharsets.UTF_8));
            db.put(rangeIndexKey(series, start, end).getBytes(StandardCharsets.UTF_8), new byte[]{1});
        } catch (RocksDBException e) {
            throw new IllegalStateException(e);
        }
        return next;
    }

    public SegmentManifestEntry getVersion(String series, long start, long end, int version) {
        try {
            byte[] v = db.get(versionedKey(series, start, end, version).getBytes(StandardCharsets.UTF_8));
            if (v == null) {
                throw new NoSuchSegmentVersionException(series, start, end, version);
            }
            return SegmentManifestEntry.fromBytes(v);
        } catch (RocksDBException e) {
            throw new IllegalStateException(e);
        }
    }

    public SegmentManifestEntry getLatest(String series, long start, long end) {
        int latest = currentLatestVersion(series, start, end);
        if (latest == 0) {
            throw new NoSuchSegmentVersionException(series, start, end, 0);
        }
        return getVersion(series, start, end, latest);
    }

    /** Every (start, end) range boundary ever written for this series, in no particular order. */
    public List<RangeKey> listRanges(String series) {
        List<RangeKey> ranges = new ArrayList<>();
        String prefix = series + "|ranges|";
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        try (RocksIterator it = db.newIterator()) {
            it.seek(prefixBytes);
            while (it.isValid()) {
                String key = new String(it.key(), StandardCharsets.UTF_8);
                if (!key.startsWith(prefix)) {
                    break;
                }
                String[] parts = key.substring(prefix.length()).split("\\|");
                ranges.add(new RangeKey(Long.parseLong(parts[0]), Long.parseLong(parts[1])));
                it.next();
            }
        }
        return ranges;
    }

    @Override
    public void close() {
        db.close();
        options.close();
    }
}
