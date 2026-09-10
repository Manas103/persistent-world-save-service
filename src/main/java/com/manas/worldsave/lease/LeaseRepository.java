package com.manas.worldsave.lease;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface LeaseRepository extends JpaRepository<LeaseEntity, String> {

    /**
     * Row-locks the lease for worldId (SELECT ... FOR UPDATE) so two concurrent
     * acquire() calls for the same worldId serialize: only one of them can ever
     * see itself as "the one that gets the next fencing token". This is what
     * makes "at most one valid lease holder per worldId at any instant" true
     * under contention, not just in the single-threaded case.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from LeaseEntity l where l.worldId = :worldId")
    Optional<LeaseEntity> findByIdForUpdate(String worldId);
}
