package com.manas.worldsave.api.dto;

import com.manas.worldsave.lease.LeaseGrant;

public record LeaseGrantResponse(String worldId, String sessionId, long fencingToken, String expiresAt) {

    public static LeaseGrantResponse from(LeaseGrant grant) {
        return new LeaseGrantResponse(grant.worldId(), grant.sessionId(), grant.fencingToken(), grant.expiresAt().toString());
    }
}
