package com.manas.worldsave.save;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/** One committed chunk of one save version, with the checksum it was verified against at upload time. */
@Entity
@Table(name = "chunk_record", uniqueConstraints = @UniqueConstraint(columnNames = {"version_id", "chunk_index"}))
public class ChunkRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "version_id", nullable = false, length = 64)
    private String versionId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "checksum", nullable = false, length = 64)
    private String checksum;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "committed_at", nullable = false)
    private Instant committedAt;

    protected ChunkRecordEntity() {
        // JPA
    }

    public ChunkRecordEntity(String versionId, int chunkIndex, String checksum, int sizeBytes, Instant committedAt) {
        this.versionId = versionId;
        this.chunkIndex = chunkIndex;
        this.checksum = checksum;
        this.sizeBytes = sizeBytes;
        this.committedAt = committedAt;
    }

    public Long getId() {
        return id;
    }

    public String getVersionId() {
        return versionId;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getChecksum() {
        return checksum;
    }

    public int getSizeBytes() {
        return sizeBytes;
    }

    public Instant getCommittedAt() {
        return committedAt;
    }
}
