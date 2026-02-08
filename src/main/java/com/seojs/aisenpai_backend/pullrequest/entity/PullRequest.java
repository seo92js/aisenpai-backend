package com.seojs.aisenpai_backend.pullrequest.entity;

import com.seojs.aisenpai_backend.github.entity.GithubAccount;
import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_pullrequest_repo_pr", columnNames = { "repository_id",
        "pr_number" }))
public class PullRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Integer prNumber;

    @Column(nullable = false)
    private Long repositoryId;

    @Column(nullable = false)
    private String repositoryName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "github_account_id", nullable = false)
    private GithubAccount githubAccount;

    private String title;

    @Column(nullable = false)
    private String action;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private ReviewStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Lob
    private String aiReview;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = ReviewStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum ReviewStatus {
        PENDING, // 리뷰 대기 중
        IN_PROGRESS, // 리뷰 진행 중
        COMPLETED, // 리뷰 완료
        FAILED, // 리뷰 실패
        NEW_CHANGES // 리뷰 후 새 변경사항 있음
    }

    public void updateStatus(ReviewStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateAction(String action) {
        this.action = action;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateAiReview(String aiReview) {
        this.aiReview = aiReview;
        this.updatedAt = LocalDateTime.now();
    }

    @Builder
    public PullRequest(Integer prNumber, Long repositoryId, String repositoryName, GithubAccount githubAccount,
            String title,
            String action, ReviewStatus status) {
        this.prNumber = prNumber;
        this.repositoryId = repositoryId;
        this.repositoryName = repositoryName;
        this.githubAccount = githubAccount;
        this.title = title;
        this.action = action;
        this.status = status;
        this.aiReview = null;
    }
}