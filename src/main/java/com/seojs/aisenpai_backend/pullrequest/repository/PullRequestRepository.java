package com.seojs.aisenpai_backend.pullrequest.repository;

import com.seojs.aisenpai_backend.pullrequest.entity.PullRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PullRequestRepository extends JpaRepository<PullRequest, Long>, PullRequestRepositoryCustom {

    /**
     * 특정 저장소의 특정 PR 번호로 조회 (repositoryId 기준)
     */
    Optional<PullRequest> findByRepositoryIdAndPrNumber(Long repositoryId, Integer prNumber);

    /**
     * 특정 저장소의 특정 PR 번호로 조회 (동시성 제어)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PullRequest> findWithLockByRepositoryIdAndPrNumber(Long repositoryId, Integer prNumber);

    /**
     * 특정 저장소의 모든 PR 조회 (repositoryId 기준)
     */
    List<PullRequest> findByRepositoryIdOrderByUpdatedAtDesc(Long repositoryId);
}