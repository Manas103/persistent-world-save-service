package com.manas.worldsave.lease;

import java.time.Instant;

/** The result of a successful lease acquisition or renewal. */
public record LeaseGrant(String worldId, String sessionId, long fencingToken, Instant expiresAt) {
}
