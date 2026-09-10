package com.manas.worldsave.save;

import com.manas.worldsave.lease.LeaseGrant;
import com.manas.worldsave.lease.LeaseService;
import com.manas.worldsave.lease.StaleLeaseException;
import com.manas.worldsave.storage.ObjectStore;
import com.manas.worldsave.testsupport.ChunkedUpload;
import com.manas.worldsave.util.Sha256;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end tests of the lease + chunked-upload + restore + rollback flow against the filesystem
 * ObjectStore backend selected by src/test/resources/application.yml. SaveServiceS3MockIntegrationTest
 * re-runs the load-bearing scenarios (resumable upload, bit-identical restore, stale-lease
 * rejection) against a real S3-compatible server instead.
 */
@SpringBootTest
class SaveServiceTest {

    @Autowired
    private LeaseService leaseService;
    @Autowired
    private SaveService saveService;
    @Autowired
    private ObjectStore objectStore;
    @Autowired
    private ChunkRecordRepository chunkRecordRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String newWorldId() {
        return "world-" + UUID.randomUUID();
    }

    private LeaseGrant acquireLease(String worldId, String sessionId) {
        return leaseService.acquire(worldId, sessionId, Duration.ofMinutes(5));
    }

    private String uploadWholeInOneGo(String worldId, LeaseGrant grant, ChunkedUpload upload) {
        String versionId = saveService.beginSave(worldId, grant.sessionId(), grant.fencingToken(), upload.totalChunks());
        for (var chunk : upload.chunks()) {
            saveService.uploadChunk(worldId, grant.sessionId(), grant.fencingToken(), versionId,
                    chunk.index(), chunk.data(), chunk.checksum());
        }
        saveService.completeSave(worldId, grant.sessionId(), grant.fencingToken(), versionId);
        return versionId;
    }

    // --- claim: chunked resumable uploads verified by per-chunk checksums ---

    @Test
    void chunkWithWrongChecksumIsRejectedAndNotStored() {
        String worldId = newWorldId();
        LeaseGrant grant = acquireLease(worldId, "session-a");
        ChunkedUpload upload = ChunkedUpload.synthetic(1, 50_000, 8_192);
        String versionId = saveService.beginSave(worldId, grant.sessionId(), grant.fencingToken(), upload.totalChunks());

        var firstChunk = upload.chunks().get(0);
        String wrongChecksum = Sha256.hex("not-the-real-bytes".getBytes());

        assertThrows(ChunkChecksumMismatchException.class, () -> saveService.uploadChunk(
                worldId, grant.sessionId(), grant.fencingToken(), versionId, 0, firstChunk.data(), wrongChecksum));

        assertFalse(objectStore.hasChunk(versionId, 0), "a checksum-rejected chunk must never reach storage");
        assertEquals(0, chunkRecordRepository.countByVersionId(versionId));
    }

    @Test
    void chunkWithCorrectChecksumIsAcceptedAndResumePointAdvances() {
        String worldId = newWorldId();
        LeaseGrant grant = acquireLease(worldId, "session-a");
        ChunkedUpload upload = ChunkedUpload.synthetic(2, 50_000, 8_192);
        String versionId = saveService.beginSave(worldId, grant.sessionId(), grant.fencingToken(), upload.totalChunks());

        assertEquals(0, saveService.resumePoint(versionId));
        var chunk0 = upload.chunks().get(0);
        saveService.uploadChunk(worldId, grant.sessionId(), grant.fencingToken(), versionId, 0, chunk0.data(), chunk0.checksum());
        assertEquals(1, saveService.resumePoint(versionId));
        assertTrue(objectStore.hasChunk(versionId, 0));
    }

    // --- claim: restores bit-identical across save cycles including interrupted ones ---

    @Test
    void fullUploadRestoresBitIdentical() {
        String worldId = newWorldId();
        LeaseGrant grant = acquireLease(worldId, "session-a");
        ChunkedUpload upload = ChunkedUpload.synthetic(3, 200_000, 16_384);

        String versionId = uploadWholeInOneGo(worldId, grant, upload);
        RestoreResult result = saveService.restore(worldId, versionId);

        assertArrayEquals(upload.wholeObject(), result.data());
        assertEquals(Sha256.hex(upload.wholeObject()), result.wholeObjectHash());
    }

    @Test
    void interruptedThenResumedUploadRestoresBitIdentical() {
        String worldId = newWorldId();
        LeaseGrant grant = acquireLease(worldId, "session-a");
        ChunkedUpload upload = ChunkedUpload.synthetic(4, 300_000, 20_000);
        int totalChunks = upload.totalChunks();
        assertTrue(totalChunks >= 4, "test needs enough chunks to genuinely interrupt partway");

        String versionId = saveService.beginSave(worldId, grant.sessionId(), grant.fencingToken(), totalChunks);

        // "Crash" after committing the first half of the chunks.
        int crashAfter = totalChunks / 2;
        for (int i = 0; i < crashAfter; i++) {
            var chunk = upload.chunks().get(i);
            saveService.uploadChunk(worldId, grant.sessionId(), grant.fencingToken(), versionId,
                    chunk.index(), chunk.data(), chunk.checksum());
        }
        // Simulated process restart: nothing in memory survives except versionId and the lease.
        // The resuming client asks the server where it left off rather than starting over.
        int resumeFrom = saveService.resumePoint(versionId);
        assertEquals(crashAfter, resumeFrom, "resume point must be exactly where the crash left off");

        for (int i = resumeFrom; i < totalChunks; i++) {
            var chunk = upload.chunks().get(i);
            saveService.uploadChunk(worldId, grant.sessionId(), grant.fencingToken(), versionId,
                    chunk.index(), chunk.data(), chunk.checksum());
        }
        saveService.completeSave(worldId, grant.sessionId(), grant.fencingToken(), versionId);

        RestoreResult result = saveService.restore(worldId, versionId);
        assertArrayEquals(upload.wholeObject(), result.data(),
                "restore of an interrupted-then-resumed save must be bit-identical to the source");

        // And identical to a save of the same content done in one uninterrupted pass.
        LeaseGrant grant2 = acquireLease(worldId, "session-a");
        String uninterruptedVersionId = uploadWholeInOneGo(worldId, grant2, ChunkedUpload.synthetic(4, 300_000, 20_000));
        RestoreResult uninterruptedResult = saveService.restore(worldId, uninterruptedVersionId);
        assertArrayEquals(result.data(), uninterruptedResult.data());
        assertEquals(result.wholeObjectHash(), uninterruptedResult.wholeObjectHash());
    }

    // --- claim: whole-object hash checked on restore ---

    @Test
    void restoreDetectsPerChunkCorruptionAtRest() {
        String worldId = newWorldId();
        LeaseGrant grant = acquireLease(worldId, "session-a");
        ChunkedUpload upload = ChunkedUpload.synthetic(5, 40_000, 8_000);
        String versionId = uploadWholeInOneGo(worldId, grant, upload);

        // Simulate bit rot / a corrupted chunk at rest, bypassing SaveService entirely.
        objectStore.putChunk(versionId, 0, "corrupted-bytes-not-the-original-chunk".getBytes());

        assertThrows(ChunkChecksumMismatchException.class, () -> saveService.restore(worldId, versionId));
    }

    @Test
    void restoreDetectsWholeObjectHashMismatchEvenWhenEveryChunkIsIndividuallyValid() {
        String worldId = newWorldId();
        LeaseGrant grant = acquireLease(worldId, "session-a");
        ChunkedUpload upload = ChunkedUpload.synthetic(6, 40_000, 8_000);
        String versionId = uploadWholeInOneGo(worldId, grant, upload);

        // Every chunk on disk is still exactly correct and passes its own checksum; only the
        // manifest's recorded whole-object hash is corrupted. Per-chunk verification alone would
        // not catch this; the whole-object hash check is what must.
        int updated = jdbcTemplate.update(
                "update save_version set whole_object_hash = ? where version_id = ?",
                "0".repeat(64), versionId);
        assertEquals(1, updated);

        WholeObjectHashMismatchException ex = assertThrows(WholeObjectHashMismatchException.class,
                () -> saveService.restore(worldId, versionId));
        assertTrue(ex.getMessage().contains(versionId));
    }

    // --- claim: every stale-lease write rejected ---

    @Test
    void staleFencingTokenIsRejectedOnEveryWriteAndNeverReachesStorage() throws InterruptedException {
        String worldId = newWorldId();
        LeaseGrant staleGrant = leaseService.acquire(worldId, "session-a", Duration.ofMillis(30));
        String versionId = saveService.beginSave(worldId, staleGrant.sessionId(), staleGrant.fencingToken(), 3);

        // session-a's lease expires and is reissued to a different session: the sharpest case of
        // "a session whose lease expired and was reissued to someone else".
        Thread.sleep(100);
        LeaseGrant freshGrant = leaseService.acquire(worldId, "session-b-took-over", Duration.ofMinutes(5));

        ChunkedUpload upload = ChunkedUpload.synthetic(7, 10_000, 4_000);
        var chunk = upload.chunks().get(0);

        assertThrows(StaleLeaseException.class, () -> saveService.uploadChunk(
                worldId, staleGrant.sessionId(), staleGrant.fencingToken(), versionId, 0, chunk.data(), chunk.checksum()));
        assertFalse(objectStore.hasChunk(versionId, 0), "a stale-token write must never reach storage");
        assertEquals(0, chunkRecordRepository.countByVersionId(versionId));

        assertThrows(StaleLeaseException.class, () -> saveService.completeSave(
                worldId, staleGrant.sessionId(), staleGrant.fencingToken(), versionId));
        assertThrows(StaleLeaseException.class, () -> saveService.beginSave(
                worldId, staleGrant.sessionId(), staleGrant.fencingToken(), 1));
        assertThrows(StaleLeaseException.class, () -> saveService.rollback(
                worldId, staleGrant.sessionId(), staleGrant.fencingToken(), versionId));

        // The fresh token, by contrast, works.
        saveService.uploadChunk(worldId, freshGrant.sessionId(), freshGrant.fencingToken(), versionId,
                0, chunk.data(), chunk.checksum());
        assertTrue(objectStore.hasChunk(versionId, 0));
    }

    // --- claim: rollback returns the exact prior version ---

    @Test
    void rollbackReturnsExactBytesOfPriorVersion() {
        String worldId = newWorldId();
        LeaseGrant grant1 = acquireLease(worldId, "session-a");
        ChunkedUpload v1 = ChunkedUpload.synthetic(8, 60_000, 12_000);
        String versionId1 = uploadWholeInOneGo(worldId, grant1, v1);

        LeaseGrant grant2 = acquireLease(worldId, "session-a");
        ChunkedUpload v2 = ChunkedUpload.synthetic(9, 60_000, 12_000);
        String versionId2 = uploadWholeInOneGo(worldId, grant2, v2);

        // v2 is definitely different content from v1.
        assertFalse(java.util.Arrays.equals(v1.wholeObject(), v2.wholeObject()));

        LeaseGrant rollbackGrant = acquireLease(worldId, "session-a");
        RestoreResult rolledBack = saveService.rollback(worldId, rollbackGrant.sessionId(), rollbackGrant.fencingToken(), versionId1);

        assertArrayEquals(v1.wholeObject(), rolledBack.data(), "rollback must return the exact bytes of the target version");
        assertEquals(Sha256.hex(v1.wholeObject()), rolledBack.wholeObjectHash());
        assertEquals(versionId1, rolledBack.versionId());

        // A plain restore of the same target version afterward agrees exactly.
        RestoreResult confirmed = saveService.restore(worldId, versionId1);
        assertArrayEquals(rolledBack.data(), confirmed.data());
    }

    // --- invariant: at most one valid lease holder per worldId at any instant ---

    @Test
    void onlyOneSessionsFencingTokenIsEverValidAtOnce() {
        String worldId = newWorldId();
        LeaseGrant a = acquireLease(worldId, "session-a");
        assertEquals(a.fencingToken(), leaseService.currentFencingToken(worldId));

        LeaseGrant b = leaseService.acquire(worldId, "session-a", Duration.ofMinutes(5)); // renewal, same holder
        assertEquals(a.fencingToken(), b.fencingToken());

        // A different, expired-then-reissued holder invalidates the old token.
        LeaseGrant c = acquireLeaseAfterForcedExpiry(worldId);
        assertTrue(c.fencingToken() > a.fencingToken());
        assertThrows(StaleLeaseException.class, () -> leaseService.requireCurrentFencingToken(worldId, a.fencingToken()));
        leaseService.requireCurrentFencingToken(worldId, c.fencingToken());
    }

    private LeaseGrant acquireLeaseAfterForcedExpiry(String worldId) {
        leaseService.acquire(worldId, "session-a", Duration.ofMillis(1));
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return leaseService.acquire(worldId, "session-c", Duration.ofMinutes(5));
    }
}
