package com.manas.worldsave.api.dto;

public record AcquireLeaseRequest(String sessionId, long ttlSeconds) {
}
