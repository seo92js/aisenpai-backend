package com.seojs.aisenpai_backend.pullrequest.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "code_graph_index", indexes = {
        @Index(name = "idx_code_graph_index_repo_status", columnList = "repository_id, status"),
        @Index(name = "idx_code_graph_index_repo_sha", columnList = "repository_id, commit_sha")
})
public class CodeGraphIndex {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repository_id", nullable = false)
    private Long repositoryId;

    @Column(name = "ref_name", nullable = false)
    private String refName;

    @Column(name = "commit_sha", nullable = false)
    private String commitSha;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "default_branch", nullable = false)
    private Boolean defaultBranch;

    @Column(name = "parser_type")
    private String parserType;

    @Column(name = "parser_version")
    private String parserVersion;

    @Column(name = "file_count")
    private Integer fileCount;

    @Column(name = "dependency_count")
    private Integer dependencyCount;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum Status {
        READY,
        INDEXING,
        FAILED,
        STALE
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = Status.INDEXING;
        }
        if (defaultBranch == null) {
            defaultBranch = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Builder
    public CodeGraphIndex(Long repositoryId, String refName, String commitSha, Status status,
                          Boolean defaultBranch, String parserType, String parserVersion,
                          Integer fileCount, Integer dependencyCount, String failureReason,
                          LocalDateTime startedAt, LocalDateTime completedAt) {
        this.repositoryId = repositoryId;
        this.refName = refName;
        this.commitSha = commitSha;
        this.status = status != null ? status : Status.INDEXING;
        this.defaultBranch = defaultBranch != null ? defaultBranch : false;
        this.parserType = parserType;
        this.parserVersion = parserVersion;
        this.fileCount = fileCount;
        this.dependencyCount = dependencyCount;
        this.failureReason = failureReason;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    public void updateStatus(Status status) {
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }

    public void markReady(Integer fileCount, Integer dependencyCount, String parserType, String parserVersion) {
        this.status = Status.READY;
        this.fileCount = fileCount;
        this.dependencyCount = dependencyCount;
        this.parserType = parserType;
        this.parserVersion = parserVersion;
        this.failureReason = null;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void markFailed(String failureReason) {
        this.status = Status.FAILED;
        this.failureReason = failureReason != null && failureReason.length() > 1000 
                ? failureReason.substring(0, 1000) 
                : failureReason;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void markStale() {
        this.status = Status.STALE;
        this.updatedAt = LocalDateTime.now();
    }
}
