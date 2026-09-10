package com.manas.worldsave.storage;

/**
 * A dumb, content-addressed-by-caller chunk blob store. It knows nothing
 * about leases, versions or checksums; SaveService owns all of that. Two
 * implementations exist: S3ObjectStore (a real S3-compatible client) and
 * FilesystemObjectStore (a real local-disk fallback). Both satisfy this
 * same interface so the rest of the application is storage-backend-blind.
 */
public interface ObjectStore {

    /** Writes one chunk's bytes under (objectKey, chunkIndex). Overwrites if already present. */
    void putChunk(String objectKey, int chunkIndex, byte[] data);

    /** Reads one chunk's bytes back. Throws NoSuchChunkException if absent. */
    byte[] getChunk(String objectKey, int chunkIndex);

    /** True if a chunk has been durably written. */
    boolean hasChunk(String objectKey, int chunkIndex);

    /** Deletes every chunk stored under objectKey. Used for cleanup in tests. */
    void deleteObject(String objectKey, int chunkCountUpperBound);
}
