package com.manas.worldsave.storage;

import com.adobe.testing.s3mock.junit5.S3MockExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises S3ObjectStore against a real, genuinely embedded S3-compatible HTTP server started
 * in-process by S3MockExtension (com.adobe.testing:s3mock-junit5), talked to over the real AWS
 * SDK v2 S3Client. No Docker, no network mock, no in-memory stand-in: this is a real running
 * server on a real (ephemeral) port, exactly the pattern this portfolio already uses for embedded
 * Redis in limited-release-drop-checkout.
 */
class S3ObjectStoreS3MockTest {

    @RegisterExtension
    static final S3MockExtension S3_MOCK = S3MockExtension.builder().silent().withSecureConnection(false).build();

    private static final String BUCKET = "world-saves-test";

    private static S3Client s3Client;

    @BeforeAll
    static void createBucket() {
        s3Client = S3_MOCK.createS3ClientV2();
        s3Client.createBucket(builder -> builder.bucket(BUCKET));
    }

    @Test
    void putThenGetReturnsExactBytesFromRealS3CompatibleServer() {
        S3ObjectStore store = new S3ObjectStore(s3Client, BUCKET);
        byte[] data = new byte[8192];
        new Random(11).nextBytes(data);
        store.putChunk("obj-1", 0, data);
        assertArrayEquals(data, store.getChunk("obj-1", 0));
    }

    @Test
    void hasChunkReflectsRealServerState() {
        S3ObjectStore store = new S3ObjectStore(s3Client, BUCKET);
        assertFalse(store.hasChunk("obj-2", 0));
        store.putChunk("obj-2", 0, new byte[]{9, 8, 7});
        assertTrue(store.hasChunk("obj-2", 0));
    }

    @Test
    void getMissingChunkThrows() {
        S3ObjectStore store = new S3ObjectStore(s3Client, BUCKET);
        assertThrows(NoSuchChunkException.class, () -> store.getChunk("obj-3", 0));
    }

    @Test
    void deleteObjectRemovesAllChunksFromRealServer() {
        S3ObjectStore store = new S3ObjectStore(s3Client, BUCKET);
        store.putChunk("obj-4", 0, new byte[]{1});
        store.putChunk("obj-4", 1, new byte[]{2});
        store.deleteObject("obj-4", 2);
        assertFalse(store.hasChunk("obj-4", 0));
        assertFalse(store.hasChunk("obj-4", 1));
    }
}
