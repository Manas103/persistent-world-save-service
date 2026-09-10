package com.manas.worldsave.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemObjectStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void putThenGetReturnsExactBytes() {
        FilesystemObjectStore store = new FilesystemObjectStore(tempDir);
        byte[] data = new byte[4096];
        new Random(7).nextBytes(data);
        store.putChunk("obj-1", 0, data);
        assertArrayEquals(data, store.getChunk("obj-1", 0));
    }

    @Test
    void hasChunkReflectsPresence() {
        FilesystemObjectStore store = new FilesystemObjectStore(tempDir);
        assertFalse(store.hasChunk("obj-2", 0));
        store.putChunk("obj-2", 0, new byte[]{1, 2, 3});
        assertTrue(store.hasChunk("obj-2", 0));
    }

    @Test
    void getMissingChunkThrows() {
        FilesystemObjectStore store = new FilesystemObjectStore(tempDir);
        assertThrows(NoSuchChunkException.class, () -> store.getChunk("obj-3", 0));
    }

    @Test
    void deleteObjectRemovesAllChunks() {
        FilesystemObjectStore store = new FilesystemObjectStore(tempDir);
        store.putChunk("obj-4", 0, new byte[]{1});
        store.putChunk("obj-4", 1, new byte[]{2});
        store.deleteObject("obj-4", 2);
        assertFalse(store.hasChunk("obj-4", 0));
        assertFalse(store.hasChunk("obj-4", 1));
    }
}
