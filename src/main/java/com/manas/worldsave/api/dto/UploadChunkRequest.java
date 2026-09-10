package com.manas.worldsave.api.dto;

public record UploadChunkRequest(String sessionId, long fencingToken, int chunkIndex, String checksum, byte[] data) {
}
