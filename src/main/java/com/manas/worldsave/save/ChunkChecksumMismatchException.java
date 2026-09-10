package com.manas.worldsave.save;

/**
 * Thrown when a chunk's actual SHA-256 does not match the checksum it was
 * uploaded (or, on restore, previously committed) with. The chunk that
 * failed is never written to storage as a committed chunk.
 */
public class ChunkChecksumMismatchException extends RuntimeException {

    public ChunkChecksumMismatchException(String versionId, int chunkIndex, String expected, String actual) {
        super("chunk checksum mismatch for versionId=" + versionId + " chunkIndex=" + chunkIndex
                + " expected=" + expected + " actual=" + actual);
    }
}
