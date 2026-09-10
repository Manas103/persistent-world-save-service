package com.manas.worldsave.save;

import com.manas.worldsave.lease.LeaseService;
import com.manas.worldsave.storage.ObjectStore;
import com.manas.worldsave.util.Sha256;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Chunked, resumable, checksum-verified world saves with single-writer
 * fencing and versioned rollback. Every write method (beginSave,
 * uploadChunk, completeSave, rollback) validates the caller's fencing
 * token before touching the object store or the chunk/version tables, in
 * the same transaction as the validation, so a stale write can never
 * partially land.
 */
@Service
public class SaveService {

    private final LeaseService leaseService;
    private final SaveVersionRepository versionRepository;
    private final ChunkRecordRepository chunkRepository;
    private final ObjectStore objectStore;

    public SaveService(LeaseService leaseService, SaveVersionRepository versionRepository,
                        ChunkRecordRepository chunkRepository, ObjectStore objectStore) {
        this.leaseService = leaseService;
        this.versionRepository = versionRepository;
        this.chunkRepository = chunkRepository;
        this.objectStore = objectStore;
    }

    /** Starts a new save version for worldId. Its parent is whatever version is currently active. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String beginSave(String worldId, String sessionId, long fencingToken, int totalChunks) {
        leaseService.requireCurrentFencingToken(worldId, fencingToken);
        String parentVersionId = versionRepository.findByWorldIdAndActiveTrue(worldId)
                .map(SaveVersionEntity::getVersionId)
                .orElse(null);
        String versionId = UUID.randomUUID().toString();
        SaveVersionEntity version = new SaveVersionEntity(versionId, worldId, totalChunks, parentVersionId, Instant.now());
        versionRepository.save(version);
        return versionId;
    }

    /**
     * The next chunk index the caller should upload for versionId: the count of already-committed
     * chunks, since chunks are required to commit contiguously from 0. A client that crashed after
     * committing chunks 0..N-1 resumes here, not from the beginning.
     */
    @Transactional(readOnly = true)
    public int resumePoint(String versionId) {
        return (int) chunkRepository.countByVersionId(versionId);
    }

    /**
     * Uploads one chunk. data's actual SHA-256 must match declaredChecksum, or the chunk is
     * rejected before it ever reaches the object store. Re-uploading an already-committed index
     * with matching content is treated as an idempotent retry, not an error.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String uploadChunk(String worldId, String sessionId, long fencingToken, String versionId,
                               int chunkIndex, byte[] data, String declaredChecksum) {
        leaseService.requireCurrentFencingToken(worldId, fencingToken);

        SaveVersionEntity version = versionRepository.findById(versionId)
                .orElseThrow(() -> new NoSuchVersionException(versionId));
        if (!version.getWorldId().equals(worldId)) {
            throw new NoSuchVersionException(versionId);
        }

        String actualChecksum = Sha256.hex(data);
        if (!actualChecksum.equals(declaredChecksum)) {
            // Rejected before any storage write and before any ChunkRecordEntity exists for this
            // attempt: the caller can simply retry the same chunkIndex.
            throw new ChunkChecksumMismatchException(versionId, chunkIndex, declaredChecksum, actualChecksum);
        }

        int nextExpected = (int) chunkRepository.countByVersionId(versionId);
        var existing = chunkRepository.findByVersionIdAndChunkIndex(versionId, chunkIndex);
        if (existing.isPresent()) {
            // idempotent retry of an already-committed chunk: verify, don't re-append a duplicate row
            if (!existing.get().getChecksum().equals(actualChecksum)) {
                throw new ChunkChecksumMismatchException(versionId, chunkIndex, existing.get().getChecksum(), actualChecksum);
            }
            objectStore.putChunk(versionId, chunkIndex, data);
            return actualChecksum;
        }
        if (chunkIndex != nextExpected) {
            throw new OutOfOrderChunkException(versionId, chunkIndex, nextExpected);
        }

        objectStore.putChunk(versionId, chunkIndex, data);
        chunkRepository.save(new ChunkRecordEntity(versionId, chunkIndex, actualChecksum, data.length, Instant.now()));
        return actualChecksum;
    }

    /**
     * Finalizes versionId: requires every chunk committed, reassembles the object once to compute
     * the whole-object hash recorded in the manifest, and makes this version the new active one.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String completeSave(String worldId, String sessionId, long fencingToken, String versionId) {
        leaseService.requireCurrentFencingToken(worldId, fencingToken);

        SaveVersionEntity version = versionRepository.findById(versionId)
                .orElseThrow(() -> new NoSuchVersionException(versionId));
        if (!version.getWorldId().equals(worldId)) {
            throw new NoSuchVersionException(versionId);
        }

        long committed = chunkRepository.countByVersionId(versionId);
        if (committed != version.getTotalChunks()) {
            throw new IncompleteUploadException(versionId, committed, version.getTotalChunks());
        }

        byte[] assembled = assemble(versionId, version.getTotalChunks());
        String wholeObjectHash = Sha256.hex(assembled);
        version.markComplete(wholeObjectHash, assembled.length, Instant.now());

        versionRepository.findByWorldIdAndActiveTrue(worldId).ifPresent(prev -> {
            if (!prev.getVersionId().equals(versionId)) {
                prev.setActive(false);
                versionRepository.save(prev);
            }
        });
        version.setActive(true);
        versionRepository.save(version);
        return wholeObjectHash;
    }

    /**
     * Reads versionId back: verifies every chunk's checksum, reassembles, and checks the whole
     * reassembled object's hash against the manifest before returning. Read-only; does not require
     * a fencing token, since it does not mutate which version is active or any world state.
     */
    @Transactional(readOnly = true)
    public RestoreResult restore(String worldId, String versionId) {
        SaveVersionEntity version = versionRepository.findById(versionId)
                .orElseThrow(() -> new NoSuchVersionException(versionId));
        if (!version.getWorldId().equals(worldId) || version.getStatus() != SaveVersionStatus.COMPLETE) {
            throw new NoSuchVersionException(versionId);
        }

        byte[] assembled = assemble(versionId, version.getTotalChunks());
        String actualHash = Sha256.hex(assembled);
        if (!actualHash.equals(version.getWholeObjectHash())) {
            throw new WholeObjectHashMismatchException(versionId, version.getWholeObjectHash(), actualHash);
        }
        return new RestoreResult(versionId, assembled, actualHash);
    }

    /**
     * Rolls worldId back to targetVersionId: validates the fencing token, restores and
     * hash-verifies the target version's exact bytes, then makes it the active version again so
     * the next save branches from it. Returns the exact bytes of that prior version, not a
     * recomputation or approximation of them.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RestoreResult rollback(String worldId, String sessionId, long fencingToken, String targetVersionId) {
        leaseService.requireCurrentFencingToken(worldId, fencingToken);

        RestoreResult result = restore(worldId, targetVersionId);

        SaveVersionEntity target = versionRepository.findById(targetVersionId).orElseThrow();
        versionRepository.findByWorldIdAndActiveTrue(worldId).ifPresent(prev -> {
            if (!prev.getVersionId().equals(targetVersionId)) {
                prev.setActive(false);
                versionRepository.save(prev);
            }
        });
        target.setActive(true);
        versionRepository.save(target);
        return result;
    }

    private byte[] assemble(String versionId, int totalChunks) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<ChunkRecordEntity> records = chunkRepository.findByVersionIdOrderByChunkIndexAsc(versionId);
        if (records.size() != totalChunks) {
            throw new IncompleteUploadException(versionId, records.size(), totalChunks);
        }
        for (ChunkRecordEntity record : records) {
            byte[] chunkBytes = objectStore.getChunk(versionId, record.getChunkIndex());
            String actual = Sha256.hex(chunkBytes);
            if (!actual.equals(record.getChecksum())) {
                throw new ChunkChecksumMismatchException(versionId, record.getChunkIndex(), record.getChecksum(), actual);
            }
            out.writeBytes(chunkBytes);
        }
        return out.toByteArray();
    }
}
