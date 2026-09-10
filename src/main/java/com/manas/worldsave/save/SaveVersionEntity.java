package com.manas.worldsave.save;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One version of one world's save. IN_PROGRESS while chunks are still
 * being uploaded; COMPLETE once every chunk has been committed, the whole
 * object was reassembled once to compute wholeObjectHash, and totalSizeBytes
 * is known. "active" marks the version a restore-with-no-explicit-version
 * or the next save's parent lineage points at; rollback moves it.
 */
@Entity
@Table(name = "save_version")
public class SaveVersionEntity {

    @Id
    @Column(name = "version_id", nullable = false, length = 64)
    private String versionId;

    @Column(name = "world_id", nullable = false, length = 128)
    private String worldId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private SaveVersionStatus status;

    @Column(name = "total_chunks", nullable = false)
    private int totalChunks;

    @Column(name = "whole_object_hash", length = 64)
    private String wholeObjectHash;

    @Column(name = "total_size_bytes")
    private Long totalSizeBytes;

    @Column(name = "parent_version_id", length = 64)
    private String parentVersionId;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected SaveVersionEntity() {
        // JPA
    }

    public SaveVersionEntity(String versionId, String worldId, int totalChunks, String parentVersionId, Instant createdAt) {
        this.versionId = versionId;
        this.worldId = worldId;
        this.status = SaveVersionStatus.IN_PROGRESS;
        this.totalChunks = totalChunks;
        this.parentVersionId = parentVersionId;
        this.active = false;
        this.createdAt = createdAt;
    }

    public void markComplete(String wholeObjectHash, long totalSizeBytes, Instant completedAt) {
        this.status = SaveVersionStatus.COMPLETE;
        this.wholeObjectHash = wholeObjectHash;
        this.totalSizeBytes = totalSizeBytes;
        this.completedAt = completedAt;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getVersionId() {
        return versionId;
    }

    public String getWorldId() {
        return worldId;
    }

    public SaveVersionStatus getStatus() {
        return status;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public String getWholeObjectHash() {
        return wholeObjectHash;
    }

    public Long getTotalSizeBytes() {
        return totalSizeBytes;
    }

    public String getParentVersionId() {
        return parentVersionId;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
