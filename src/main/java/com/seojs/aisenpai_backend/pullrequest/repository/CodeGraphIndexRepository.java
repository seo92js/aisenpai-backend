package com.seojs.aisenpai_backend.pullrequest.repository;

import com.seojs.aisenpai_backend.pullrequest.entity.CodeGraphIndex;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CodeGraphIndexRepository extends JpaRepository<CodeGraphIndex, Long> {
    Optional<CodeGraphIndex> findFirstByRepositoryIdAndDefaultBranchAndStatusOrderByCreatedAtDesc(
            Long repositoryId, Boolean defaultBranch, CodeGraphIndex.Status status);

    List<CodeGraphIndex> findByRepositoryIdOrderByCreatedAtDesc(Long repositoryId);

    Optional<CodeGraphIndex> findByRepositoryIdAndCommitSha(Long repositoryId, String commitSha);
}
