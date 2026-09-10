package com.manas.worldsave.lease;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row per worldId: the only source of truth for who currently holds the
 * write lease and what fencing token that grant carries. currentFencingToken
 * only ever increases (see LeaseService.acquire); it never resets, even
 * across releases, so a token issued in the past can never become valid
 * again by coincidence.
 */
@Entity
@Table(name = "world_lease")
public class LeaseEntity {

    @Id
    @Column(name = "world_id", nullable = false, length = 128)
    private String worldId;

    @Column(name = "current_fencing_token", nullable = false)
    private long currentFencingToken;

    @Column(name = "holder_session_id")
    private String holderSessionId;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected LeaseEntity() {
        // JPA
    }

    public LeaseEntity(String worldId) {
        this.worldId = worldId;
        this.currentFencingToken = 0L;
        this.holderSessionId = null;
        this.expiresAt = null;
        this.updatedAt = Instant.now();
    }

    public String getWorldId() {
        return worldId;
    }

    public long getCurrentFencingToken() {
        return currentFencingToken;
    }

    public String getHolderSessionId() {
        return holderSessionId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Issues a brand-new grant: bumps the fencing token and installs a new holder. */
    void grantNewLease(String sessionId, Instant expiresAt, Instant now) {
        this.currentFencingToken = this.currentFencingToken + 1;
        this.holderSessionId = sessionId;
        this.expiresAt = expiresAt;
        this.updatedAt = now;
    }

    /** Extends the current holder's lease without changing the fencing token. */
    void renew(Instant expiresAt, Instant now) {
        this.expiresAt = expiresAt;
        this.updatedAt = now;
    }
}
