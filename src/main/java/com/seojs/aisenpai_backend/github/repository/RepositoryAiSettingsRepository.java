package com.seojs.aisenpai_backend.github.repository;

import com.seojs.aisenpai_backend.github.entity.RepositoryAiSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RepositoryAiSettingsRepository extends JpaRepository<RepositoryAiSettings, Long> {
    Optional<RepositoryAiSettings> findByRepositoryId(Long repositoryId);

    Optional<RepositoryAiSettings> findByOwnerAndRepositoryName(String owner, String repositoryName);
}
