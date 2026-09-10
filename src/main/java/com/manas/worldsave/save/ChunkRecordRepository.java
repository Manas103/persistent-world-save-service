package com.manas.worldsave.save;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChunkRecordRepository extends JpaRepository<ChunkRecordEntity, Long> {

    List<ChunkRecordEntity> findByVersionIdOrderByChunkIndexAsc(String versionId);

    Optional<ChunkRecordEntity> findByVersionIdAndChunkIndex(String versionId, int chunkIndex);

    long countByVersionId(String versionId);
}
