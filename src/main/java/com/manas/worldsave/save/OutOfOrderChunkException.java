package com.manas.worldsave.save;

/** Thrown when a chunk is uploaded before the chunk before it has been committed, leaving a gap. */
public class OutOfOrderChunkException extends RuntimeException {

    public OutOfOrderChunkException(String versionId, int chunkIndex, int expectedIndex) {
        super("out-of-order chunk for versionId=" + versionId + ": got index=" + chunkIndex
                + " but next expected index is=" + expectedIndex);
    }
}
