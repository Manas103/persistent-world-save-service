package com.manas.worldsave.lease;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class LeaseServiceTest {

    @Autowired
    private LeaseService leaseService;

    @Test
    void firstAcquisitionGrantsFencingTokenOne() {
        String worldId = "world-" + UUID.randomUUID();
        LeaseGrant grant = leaseService.acquire(worldId, "session-a", Duration.ofSeconds(30));
        assertEquals(1L, grant.fencingToken());
    }

    @Test
    void renewalBySameHolderKeepsSameFencingToken() {
        String worldId = "world-" + UUID.randomUUID();
        LeaseGrant first = leaseService.acquire(worldId, "session-a", Duration.ofSeconds(30));
        LeaseGrant renewed = leaseService.acquire(worldId, "session-a", Duration.ofSeconds(30));
        assertEquals(first.fencingToken(), renewed.fencingToken());
        assertTrue(renewed.expiresAt().isAfter(first.expiresAt()) || renewed.expiresAt().equals(first.expiresAt()));
    }

    @Test
    void acquisitionByAnotherSessionWhileUnexpiredIsRejected() {
        String worldId = "world-" + UUID.randomUUID();
        leaseService.acquire(worldId, "session-a", Duration.ofSeconds(30));
        assertThrows(LeaseConflictException.class,
                () -> leaseService.acquire(worldId, "session-b", Duration.ofSeconds(30)));
    }

    @Test
    void acquisitionAfterExpiryReissuesToNewHolderWithHigherToken() throws InterruptedException {
        String worldId = "world-" + UUID.randomUUID();
        LeaseGrant first = leaseService.acquire(worldId, "session-a", Duration.ofMillis(50));
        Thread.sleep(150);
        LeaseGrant second = leaseService.acquire(worldId, "session-b", Duration.ofSeconds(30));
        assertTrue(second.fencingToken() > first.fencingToken());
        assertEquals("session-b", second.sessionId());
    }

    @Test
    void requireCurrentFencingTokenRejectsStaleToken() {
        String worldId = "world-" + UUID.randomUUID();
        LeaseGrant grant = leaseService.acquire(worldId, "session-a", Duration.ofSeconds(30));
        assertThrows(StaleLeaseException.class,
                () -> leaseService.requireCurrentFencingToken(worldId, grant.fencingToken() - 1));
        leaseService.requireCurrentFencingToken(worldId, grant.fencingToken());
    }

    /** Invariant: repeated re-issuance of a lease for one worldId strictly increases the fencing token. */
    @Test
    void fencingTokenIsStrictlyMonotonicAcrossManyReissuances() throws InterruptedException {
        String worldId = "world-" + UUID.randomUUID();
        long previous = 0;
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            LeaseGrant grant = leaseService.acquire(worldId, "session-" + i, Duration.ofMillis(1));
            assertTrue(grant.fencingToken() > previous, "fencing token must strictly increase");
            assertTrue(seen.add(grant.fencingToken()), "fencing token must never repeat");
            previous = grant.fencingToken();
            Thread.sleep(5);
        }
        assertEquals(25, seen.size());
    }
}
