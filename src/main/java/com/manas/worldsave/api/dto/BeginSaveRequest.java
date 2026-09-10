package com.manas.worldsave.api.dto;

public record BeginSaveRequest(String sessionId, long fencingToken, int totalChunks) {
}
