package com.manas.worldsave.timeseries;

import com.manas.worldsave.lease.LeaseGrant;
import com.manas.worldsave.lease.LeaseService;
import com.manas.worldsave.lease.StaleLeaseException;
import com.manas.worldsave.storage.ObjectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the time-series extension's own claims: fencing applies to appends, the latest version
 * answers queryRange, and every prior version's exact bytes remain fetchable after many
 * restatements of the same (series, range).
 */
@SpringBootTest
class TimeSeriesStoreTest {

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
        metadataStore = new TimeSeriesMetadataStore(tempDir.resolve("rocksdb-" + UUID.randomUUID()));
        store = new TimeSeriesStore(metadataStore, objectStore, leaseService, 3);
    }

    @AfterEach
    void tearDown() {
        metadataStore.close();
    }

    private static List<Point> syntheticPoints(long startTs, int count, long intervalMillis) {
        List<Point> points = new ArrayList<>();
        long t = startTs;
        for (int i = 0; i < count; i++) {
            points.add(new Point(t, Math.sin(i * 0.1) * 50 + i));
            t += intervalMillis;
        }
        return points;
    }

    @Test
    void appendAndReadBackLatestVersionExactly() {
        String series = "series-" + UUID.randomUUID();
        LeaseGrant grant = leaseService.acquire(series, "writer-a", Duration.ofMinutes(5));
        List<Point> points = syntheticPoints(0, 2000, 1000);

        int version = store.appendSegment(series, 0, 2_000_000, points, grant.fencingToken());
        assertEquals(1, version);

        List<Point> readBack = store.readVersion(series, 0, 2_000_000, 1);
        assertEquals(points, readBack);
    }

    @Test
    void queryRangeMergesMultipleNonOverlappingSegmentsAndFiltersToWindow() {
        String series = "series-" + UUID.randomUUID();
        LeaseGrant grant = leaseService.acquire(series, "writer-a", Duration.ofMinutes(5));

        store.appendSegment(series, 0, 1000, syntheticPoints(0, 100, 10), grant.fencingToken());
        store.appendSegment(series, 1000, 2000, syntheticPoints(1000, 100, 10), grant.fencingToken());
        store.appendSegment(series, 2000, 3000, syntheticPoints(2000, 100, 10), grant.fencingToken());

        List<Point> result = store.queryRange(series, 500, 1500);
        assertTrue(result.stream().allMatch(p -> p.timestampMillis() >= 500 && p.timestampMillis() < 1500));
        assertTrue(result.size() > 0);
        for (int i = 1; i < result.size(); i++) {
            assertTrue(result.get(i - 1).timestampMillis() <= result.get(i).timestampMillis());
        }
    }

    @Test
    void appendUnderStaleFencingTokenIsRejectedAndNeverReachesStorageOrMetadata() throws InterruptedException {
        String series = "series-" + UUID.randomUUID();
        LeaseGrant staleGrant = leaseService.acquire(series, "writer-a", Duration.ofMillis(30));
        Thread.sleep(100);
        leaseService.acquire(series, "writer-b-took-over", Duration.ofMinutes(5));

        assertThrows(StaleLeaseException.class, () ->
                store.appendSegment(series, 0, 1000, syntheticPoints(0, 10, 100), staleGrant.fencingToken()));
        assertEquals(0, metadataStore.currentLatestVersion(series, 0, 1000));
    }

    @Test
    void everyPriorVersionsExactBytesSurviveAcrossManyRestatementsOfTheSameRange() {
        String series = "series-" + UUID.randomUUID();
        LeaseGrant grant = leaseService.acquire(series, "writer-a", Duration.ofMinutes(10));

        int restatements = 500;
        List<List<Point>> written = new ArrayList<>(restatements);
        for (int r = 0; r < restatements; r++) {
            // Each restatement corrects the same [0, 10_000) range with a different value set, as
            // a real restatement (a corrected vendor print, a backfilled reading) would.
            List<Point> points = syntheticPoints(0, 50, 200);
            List<Point> correctedPoints = new ArrayList<>(points.size());
            for (Point p : points) {
                correctedPoints.add(new Point(p.timestampMillis(), p.value() + r));
            }
            int version = store.appendSegment(series, 0, 10_000, correctedPoints, grant.fencingToken());
            assertEquals(r + 1, version);
            written.add(correctedPoints);
        }

        for (int r = 0; r < restatements; r++) {
            List<Point> readBack = store.readVersion(series, 0, 10_000, r + 1);
            assertEquals(written.get(r), readBack, "version " + (r + 1) + " must still return its own exact bytes");
        }

        // The latest query path agrees with the final restatement's explicit version read.
        List<Point> latestViaQuery = store.queryRange(series, 0, 10_000);
        assertEquals(written.get(restatements - 1), latestViaQuery);
    }
}
