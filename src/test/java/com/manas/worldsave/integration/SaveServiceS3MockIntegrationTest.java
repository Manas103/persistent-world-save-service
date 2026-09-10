package com.manas.worldsave.integration;

import com.adobe.testing.s3mock.junit5.S3MockExtension;
import com.manas.worldsave.lease.LeaseGrant;
import com.manas.worldsave.lease.LeaseService;
import com.manas.worldsave.lease.StaleLeaseException;
import com.manas.worldsave.save.RestoreResult;
import com.manas.worldsave.save.SaveService;
import com.manas.worldsave.storage.ObjectStore;
import com.manas.worldsave.testsupport.ChunkedUpload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Re-runs the load-bearing save/restore/rollback scenarios with worldsave.storage.backend
 * switched to "s3", against a real, genuinely embedded S3-compatible server started in-process by
 * S3MockExtension (no Docker). This is what proves the S3-compatible storage claim end to end
 * rather than only at the ObjectStore unit level (see S3ObjectStoreS3MockTest).
 */
@SpringBootTest(properties = {
        "worldsave.storage.backend=s3",
        "worldsave.storage.s3.bucket=world-saves-integration"
})
class SaveServiceS3MockIntegrationTest {

    @RegisterExtension
    static final S3MockExtension S3_MOCK = S3MockExtension.builder().silent().withSecureConnection(false).build();

    @DynamicPropertySource
    static void s3Properties(DynamicPropertyRegistry registry) {
        registry.add("worldsave.storage.s3.endpoint", () -> S3_MOCK.getServiceEndpoint());
    }

    @Autowired
    private LeaseService leaseService;
    @Autowired
    private SaveService saveService;
    @Autowired
    private ObjectStore objectStore;

    private String newWorldId() {
        return "world-" + UUID.randomUUID();
    }

    @Test
    void objectStoreBeanIsTheRealS3BackedImplementation() {
        assertEquals("com.manas.worldsave.storage.S3ObjectStore", objectStore.getClass().getName());
    }

    @Test
    void interruptedThenResumedUploadRestoresBitIdenticalAgainstRealS3CompatibleServer() {
        String worldId = newWorldId();
        LeaseGrant grant = leaseService.acquire(worldId, "session-a", Duration.ofMinutes(5));
        ChunkedUpload upload = ChunkedUpload.synthetic(101, 260_000, 17_000);
        int totalChunks = upload.totalChunks();
        assertTrue(totalChunks >= 4);

        String versionId = saveService.beginSave(worldId, grant.sessionId(), grant.fencingToken(), totalChunks);

        int crashAfter = totalChunks / 2;
        for (int i = 0; i < crashAfter; i++) {
            var chunk = upload.chunks().get(i);
            saveService.uploadChunk(worldId, grant.sessionId(), grant.fencingToken(), versionId,
                    chunk.index(), chunk.data(), chunk.checksum());
        }
        int resumeFrom = saveService.resumePoint(versionId);
        assertEquals(crashAfter, resumeFrom);
        for (int i = resumeFrom; i < totalChunks; i++) {
            var chunk = upload.chunks().get(i);
            saveService.uploadChunk(worldId, grant.sessionId(), grant.fencingToken(), versionId,
                    chunk.index(), chunk.data(), chunk.checksum());
        }
        saveService.completeSave(worldId, grant.sessionId(), grant.fencingToken(), versionId);

        RestoreResult result = saveService.restore(worldId, versionId);
        assertArrayEquals(upload.wholeObject(), result.data());
    }

    @Test
    void staleLeaseWriteRejectedAndNeverReachesRealS3CompatibleServer() throws InterruptedException {
        String worldId = newWorldId();
        LeaseGrant staleGrant = leaseService.acquire(worldId, "session-a", Duration.ofMillis(30));
        String versionId = saveService.beginSave(worldId, staleGrant.sessionId(), staleGrant.fencingToken(), 2);
        Thread.sleep(100);
        leaseService.acquire(worldId, "session-b", Duration.ofMinutes(5));

        ChunkedUpload upload = ChunkedUpload.synthetic(102, 20_000, 8_000);
        var chunk = upload.chunks().get(0);

        assertThrows(StaleLeaseException.class, () -> saveService.uploadChunk(
                worldId, staleGrant.sessionId(), staleGrant.fencingToken(), versionId, 0, chunk.data(), chunk.checksum()));
        assertFalse(objectStore.hasChunk(versionId, 0));
    }

    @Test
    void rollbackReturnsExactPriorVersionAgainstRealS3CompatibleServer() {
        String worldId = newWorldId();
        LeaseGrant grant1 = leaseService.acquire(worldId, "session-a", Duration.ofMinutes(5));
        ChunkedUpload v1 = ChunkedUpload.synthetic(103, 40_000, 9_000);
        String versionId1 = saveService.beginSave(worldId, grant1.sessionId(), grant1.fencingToken(), v1.totalChunks());
        for (var chunk : v1.chunks()) {
            saveService.uploadChunk(worldId, grant1.sessionId(), grant1.fencingToken(), versionId1, chunk.index(), chunk.data(), chunk.checksum());
        }
        saveService.completeSave(worldId, grant1.sessionId(), grant1.fencingToken(), versionId1);

        LeaseGrant grant2 = leaseService.acquire(worldId, "session-a", Duration.ofMinutes(5));
        ChunkedUpload v2 = ChunkedUpload.synthetic(104, 40_000, 9_000);
        String versionId2 = saveService.beginSave(worldId, grant2.sessionId(), grant2.fencingToken(), v2.totalChunks());
        for (var chunk : v2.chunks()) {
            saveService.uploadChunk(worldId, grant2.sessionId(), grant2.fencingToken(), versionId2, chunk.index(), chunk.data(), chunk.checksum());
        }
        saveService.completeSave(worldId, grant2.sessionId(), grant2.fencingToken(), versionId2);

        LeaseGrant grant3 = leaseService.acquire(worldId, "session-a", Duration.ofMinutes(5));
        RestoreResult rolledBack = saveService.rollback(worldId, grant3.sessionId(), grant3.fencingToken(), versionId1);
        assertArrayEquals(v1.wholeObject(), rolledBack.data());
    }
}
