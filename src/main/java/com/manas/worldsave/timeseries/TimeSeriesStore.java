package com.manas.worldsave.timeseries;

import com.manas.worldsave.lease.LeaseService;
import com.manas.worldsave.storage.ObjectStore;
import com.manas.worldsave.util.Sha256;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the time-series extension: a RocksDB metadata plane (TimeSeriesMetadataStore)
 * fully separated from a pluggable data backend (ObjectStore, either FilesystemObjectStore or
 * S3ObjectStore from the base save service), under the same single-writer fencing lease the
 * base service already proves. A segment is one (series, time range, version) unit: points are
 * delta-of-delta/delta encoded (SegmentCodec), then Zstandard-compressed (ZstdBlob), then
 * written to ObjectStore as one chunk 0 under a fresh UUID object key. Writing the same
 * (series, range) again never overwrites; it allocates the next version and the prior version's
 * bytes remain fetchable exactly, which is what the restatement claim requires.
 */
public final class TimeSeriesStore {

    private final TimeSeriesMetadataStore metadataStore;
    private final ObjectStore objectStore;
    private final LeaseService leaseService;
    private final int zstdLevel;

    public TimeSeriesStore(TimeSeriesMetadataStore metadataStore, ObjectStore objectStore,
                            LeaseService leaseService, int zstdLevel) {
        this.metadataStore = metadataStore;
        this.objectStore = objectStore;
        this.leaseService = leaseService;
        this.zstdLevel = zstdLevel;
    }

    /**
     * Appends one segment's worth of points for (series, [start, end)), requiring the caller's
     * fencingToken to be the current one for worldId=series in the base service's own lease
     * table (same StaleLeaseException path, unchanged). Returns the version written.
     */
    public int appendSegment(String series, long start, long end, List<Point> points, long fencingToken) {
        leaseService.requireCurrentFencingToken(series, fencingToken);

        byte[] raw = SegmentCodec.encode(points);
        byte[] compressed = ZstdBlob.compress(raw, zstdLevel);
        String objectKey = "ts/" + series + "/" + UUID.randomUUID();
        objectStore.putChunk(objectKey, 0, compressed);

        SegmentManifestEntry entry = new SegmentManifestEntry(
                series, start, end, 0, objectKey, points.size(), raw.length, compressed.length,
                Sha256.hex(raw));
        return metadataStore.putNextVersion(series, start, end, entry);
    }

    /** Fetches exactly the named version's points, decompressed and decoded, bytes verified by the stored sha256. */
    public List<Point> readVersion(String series, long start, long end, int version) {
        SegmentManifestEntry entry = metadataStore.getVersion(series, start, end, version);
        return readEntry(entry);
    }

    /**
     * Merges every segment (its latest version only) whose [start, end) range overlaps the
     * query range, decodes each, filters to points inside [queryStart, queryEnd), and returns
     * them sorted by timestamp. This is the "time-range streaming read" the claim refers to.
     */
    public List<Point> queryRange(String series, long queryStart, long queryEnd) {
        List<Point> merged = new ArrayList<>();
        for (TimeSeriesMetadataStore.RangeKey range : metadataStore.listRanges(series)) {
            boolean overlaps = range.startTimeMillis() < queryEnd && range.endTimeMillis() > queryStart;
            if (!overlaps) {
                continue;
            }
            SegmentManifestEntry latest = metadataStore.getLatest(series, range.startTimeMillis(), range.endTimeMillis());
            for (Point p : readEntry(latest)) {
                if (p.timestampMillis() >= queryStart && p.timestampMillis() < queryEnd) {
                    merged.add(p);
                }
            }
        }
        merged.sort(Comparator.comparingLong(Point::timestampMillis));
        return merged;
    }

    private List<Point> readEntry(SegmentManifestEntry entry) {
        byte[] compressed = objectStore.getChunk(entry.objectKey(), 0);
        byte[] raw = ZstdBlob.decompress(compressed, entry.rawBytes());
        String actualHash = Sha256.hex(raw);
        if (!actualHash.equals(entry.sha256Hex())) {
            throw new IllegalStateException("segment hash mismatch for " + entry.objectKey()
                    + ": expected " + entry.sha256Hex() + " got " + actualHash);
        }
        return SegmentCodec.decode(raw);
    }
}
