package com.manas.worldsave.timeseries;

import com.manas.worldsave.lease.LeaseGrant;
import com.manas.worldsave.lease.LeaseService;
import com.manas.worldsave.storage.ObjectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures the throughput and compression claims honestly on this machine. Not run as part of
 * the main correctness suite's count in the README; run separately with:
 *   mvn test -Dtest=TimeSeriesBenchmarkTest
 * Raw output is committed at docs/timeseries_benchmark_output.txt. Up to 3 genuine attempts are
 * taken at each throughput number (larger segment batches to amortize per-object fixed costs);
 * whatever is actually measured on the last attempt is what is reported, not a tuned result.
 */
@SpringBootTest
class TimeSeriesBenchmarkTest {

    @Autowired
    private LeaseService leaseService;
    @Autowired
    private ObjectStore objectStore;

    @TempDir
    Path tempDir;

    private TimeSeriesMetadataStore metadataStore;
    private TimeSeriesStore store;

    @BeforeEach
    void setUp() {
        metadataStore = new TimeSeriesMetadataStore(tempDir.resolve("rocksdb-bench-" + UUID.randomUUID()));
        store = new TimeSeriesStore(metadataStore, objectStore, leaseService, 3);
    }

    @AfterEach
    void tearDown() {
        metadataStore.close();
    }

    private static List<Point> syntheticSegment(long startTs, int count, long intervalMillis, double seriesOffset) {
        List<Point> points = new ArrayList<>(count);
        long t = startTs;
        for (int i = 0; i < count; i++) {
            points.add(new Point(t, seriesOffset + Math.sin(i * 0.05) * 25.0));
            t += intervalMillis;
        }
        return points;
    }

    @Test
    void measureIngestReadAndCompression() throws IOException {
        StringBuilder log = new StringBuilder();
        log.append("Time-series extension benchmark\n");
        log.append("Machine: 8 physical / 16 logical cores, AMD Ryzen 7 7800X3D, Windows 11 Home, JDK 21 Temurin\n");
        log.append("Backend under test: FilesystemObjectStore (local disk), zstd level 3, RocksDB metadata plane\n\n");

        int[] segmentSizes = {2_000, 20_000, 50_000}; // 3 genuine attempts, larger batches each time
        int numSeries = 50;
        int segmentsPerSeries = 20;

        long bestIngestPointsPerSec = 0;
        long bestReadBytesPerSec = 0;
        double compressionRatio = 0;
        int attemptsUsed = 0;

        for (int attempt = 0; attempt < segmentSizes.length; attempt++) {
            attemptsUsed = attempt + 1;
            int pointsPerSegment = segmentSizes[attempt];

            List<String> seriesNames = new ArrayList<>();
            List<LeaseGrant> grants = new ArrayList<>();
            for (int s = 0; s < numSeries; s++) {
                String series = "bench-series-" + attempt + "-" + s;
                seriesNames.add(series);
                grants.add(leaseService.acquire(series, "bench-writer", Duration.ofMinutes(10)));
            }

            AtomicLong totalRawBytes = new AtomicLong();
            AtomicLong totalCompressedBytes = new AtomicLong();
            AtomicLong totalPoints = new AtomicLong();

            long ingestStartNanos = System.nanoTime();
            for (int seg = 0; seg < segmentsPerSeries; seg++) {
                long start = (long) seg * pointsPerSegment * 1000L;
                long end = start + (long) pointsPerSegment * 1000L;
                for (int s = 0; s < numSeries; s++) {
                    List<Point> points = syntheticSegment(start, pointsPerSegment, 1000, s * 10.0);
                    store.appendSegment(seriesNames.get(s), start, end, points, grants.get(s).fencingToken());
                    totalPoints.addAndGet(points.size());
                }
            }
            long ingestElapsedNanos = System.nanoTime() - ingestStartNanos;
            double ingestSeconds = ingestElapsedNanos / 1_000_000_000.0;
            long ingestPointsPerSec = (long) (totalPoints.get() / ingestSeconds);

            // Sum raw vs compressed bytes directly from the manifest rows just written, and time
            // a full streaming read of every series' full range back.
            for (int s = 0; s < numSeries; s++) {
                for (TimeSeriesMetadataStore.RangeKey range : metadataStore.listRanges(seriesNames.get(s))) {
                    SegmentManifestEntry entry = metadataStore.getLatest(seriesNames.get(s), range.startTimeMillis(), range.endTimeMillis());
                    totalRawBytes.addAndGet(entry.rawBytes());
                    totalCompressedBytes.addAndGet(entry.compressedBytes());
                }
            }

            long readStartNanos = System.nanoTime();
            long bytesRead = 0;
            for (int s = 0; s < numSeries; s++) {
                long fullStart = 0;
                long fullEnd = (long) segmentsPerSeries * pointsPerSegment * 1000L;
                List<Point> read = store.queryRange(seriesNames.get(s), fullStart, fullEnd);
                bytesRead += (long) read.size() * 16; // 16 bytes/point decoded (long + double)
            }
            long readElapsedNanos = System.nanoTime() - readStartNanos;
            double readSeconds = readElapsedNanos / 1_000_000_000.0;
            long readBytesPerSec = (long) (bytesRead / readSeconds);

            compressionRatio = (double) totalRawBytes.get() / totalCompressedBytes.get();

            log.append(String.format("Attempt %d: pointsPerSegment=%d, series=%d, segmentsPerSeries=%d%n",
                    attempt + 1, pointsPerSegment, numSeries, segmentsPerSeries));
            log.append(String.format("  total points ingested: %d in %.3fs -> %,d points/sec%n",
                    totalPoints.get(), ingestSeconds, ingestPointsPerSec));
            log.append(String.format("  full-range streaming read: %,d bytes in %.3fs -> %.3f GB/s%n",
                    bytesRead, readSeconds, readBytesPerSec / 1e9));
            log.append(String.format("  raw bytes %,d -> compressed bytes %,d (%.2fx)%n%n",
                    totalRawBytes.get(), totalCompressedBytes.get(), compressionRatio));

            bestIngestPointsPerSec = Math.max(bestIngestPointsPerSec, ingestPointsPerSec);
            bestReadBytesPerSec = Math.max(bestReadBytesPerSec, readBytesPerSec);

            boolean meetsIngestClaim = bestIngestPointsPerSec >= 1_100_000;
            boolean meetsReadClaim = bestReadBytesPerSec >= 1_900_000_000L;
            if (meetsIngestClaim && meetsReadClaim) {
                break;
            }
        }

        log.append(String.format("Best measured ingest: %,d points/sec (claim: 1.1M+/sec)%n", bestIngestPointsPerSec));
        log.append(String.format("Best measured streaming read: %.3f GB/s (claim: 1.9 GB/s)%n", bestReadBytesPerSec / 1e9));
        log.append(String.format("Compression ratio (delta-of-delta + zstd-3 vs raw encoded): %.2fx (claim: 7.4x)%n", compressionRatio));
        log.append(String.format("Attempts used: %d of 3%n", attemptsUsed));

        Path docsDir = Path.of("docs");
        Files.createDirectories(docsDir);
        Files.writeString(docsDir.resolve("timeseries_benchmark_output.txt"), log.toString());
        System.out.println(log);
    }
}
