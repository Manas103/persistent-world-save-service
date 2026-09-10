package com.manas.worldsave.save;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SaveVersionRepository extends JpaRepository<SaveVersionEntity, String> {

    Optional<SaveVersionEntity> findByWorldIdAndActiveTrue(String worldId);
}
