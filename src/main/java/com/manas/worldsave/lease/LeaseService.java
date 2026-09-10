package com.manas.worldsave.lease;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Single-writer leasing for a worldId. A session must hold the current
 * lease, and must present the fencing token that lease grant carried, to
 * have any write accepted. The fencing token is the correctness mechanism,
 * not the expiry clock: a delayed write from a superseded session is
 * rejected because its token no longer matches, whether or not its lease
 * had actually expired yet on the wall clock.
 */
@Service
public class LeaseService {

    private final LeaseRepository leaseRepository;

    public LeaseService(LeaseRepository leaseRepository) {
        this.leaseRepository = leaseRepository;
    }

    /**
     * Acquires (or renews) the lease for worldId on behalf of sessionId.
     * Fails with LeaseConflictException if a different session currently
     * holds an unexpired lease. A brand-new grant to a new (or previously
     * expired) holder always strictly increases the fencing token; a
     * renewal by the same still-current session keeps the same token.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LeaseGrant acquire(String worldId, String sessionId, Duration ttl) {
        Instant now = Instant.now();
        LeaseEntity lease = leaseRepository.findByIdForUpdate(worldId)
                .orElseGet(() -> new LeaseEntity(worldId));

        boolean heldByAnotherUnexpired = lease.getHolderSessionId() != null
                && !lease.getHolderSessionId().equals(sessionId)
                && lease.getExpiresAt() != null
                && lease.getExpiresAt().isAfter(now);

        if (heldByAnotherUnexpired) {
            throw new LeaseConflictException(worldId, lease.getHolderSessionId());
        }

        boolean sameHolderStillCurrent = sessionId.equals(lease.getHolderSessionId())
                && lease.getExpiresAt() != null
                && lease.getExpiresAt().isAfter(now);

        Instant expiresAt = now.plus(ttl);
        if (sameHolderStillCurrent) {
            lease.renew(expiresAt, now);
        } else {
            lease.grantNewLease(sessionId, expiresAt, now);
        }
        leaseRepository.save(lease);
        return new LeaseGrant(worldId, sessionId, lease.getCurrentFencingToken(), expiresAt);
    }

    /**
     * Validates that fencingToken is still the current one for worldId.
     * Called at the top of every write path, in the same transaction as the
     * write itself, so a rejection here never lets execution reach storage.
     */
    @Transactional(readOnly = true)
    public void requireCurrentFencingToken(String worldId, long fencingToken) {
        long current = leaseRepository.findById(worldId)
                .map(LeaseEntity::getCurrentFencingToken)
                .orElse(0L);
        if (fencingToken != current) {
            throw new StaleLeaseException(worldId, fencingToken, current);
        }
    }

    /** Read-only view of the current lease state, for diagnostics and tests. */
    @Transactional(readOnly = true)
    public long currentFencingToken(String worldId) {
        return leaseRepository.findById(worldId)
                .map(LeaseEntity::getCurrentFencingToken)
                .orElse(0L);
    }
}
