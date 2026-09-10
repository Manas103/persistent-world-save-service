package com.manas.worldsave.storage;

public class NoSuchChunkException extends RuntimeException {

    public NoSuchChunkException(String objectKey, int chunkIndex) {
        super("no chunk stored for objectKey=" + objectKey + " chunkIndex=" + chunkIndex);
    }
}
