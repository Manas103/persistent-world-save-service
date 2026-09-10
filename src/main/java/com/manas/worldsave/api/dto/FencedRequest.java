package com.manas.worldsave.api.dto;

public record FencedRequest(String sessionId, long fencingToken) {
}
