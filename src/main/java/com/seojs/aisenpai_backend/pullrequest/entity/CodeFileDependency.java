package com.seojs.aisenpai_backend.pullrequest.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "code_file_dependency", indexes = {
        @Index(name = "idx_code_file_dep_index_source", columnList = "index_id, source_file_path"),
        @Index(name = "idx_code_file_dep_index_target", columnList = "index_id, target_dependency_path")
})
public class CodeFileDependency {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "index_id", nullable = false)
    private CodeGraphIndex codeGraphIndex;

    @Column(name = "source_file_path", nullable = false)
    private String sourceFilePath;

    @Column(name = "target_dependency_path")
    private String targetDependencyPath;

    @Column(name = "raw_import", nullable = false)
    private String rawImport;

    @Column(name = "relation_type", nullable = false)
    @Enumerated(EnumType.STRING)
    private RelationType relationType;

    @Column(name = "resolution_status", nullable = false)
    @Enumerated(EnumType.STRING)
    private ResolutionStatus resolutionStatus;

    @Column(name = "confidence_score", nullable = false)
    private Double confidenceScore;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum RelationType {
        IMPORT,
        EXPORT
    }

    public enum ResolutionStatus {
        RESOLVED,
        UNRESOLVED
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (confidenceScore == null) {
            confidenceScore = 1.0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Builder
    public CodeFileDependency(CodeGraphIndex codeGraphIndex, String sourceFilePath, String targetDependencyPath,
                              String rawImport, RelationType relationType, ResolutionStatus resolutionStatus,
                              Double confidenceScore) {
        this.codeGraphIndex = codeGraphIndex;
        this.sourceFilePath = sourceFilePath;
        this.targetDependencyPath = targetDependencyPath;
        this.rawImport = rawImport;
        this.relationType = relationType != null ? relationType : RelationType.IMPORT;
        this.resolutionStatus = resolutionStatus != null ? resolutionStatus : ResolutionStatus.UNRESOLVED;
        this.confidenceScore = confidenceScore != null ? confidenceScore : 1.0;
    }

    public void updateResolution(String targetDependencyPath, ResolutionStatus resolutionStatus, Double confidenceScore) {
        this.targetDependencyPath = targetDependencyPath;
        this.resolutionStatus = resolutionStatus;
        this.confidenceScore = confidenceScore;
        this.updatedAt = LocalDateTime.now();
    }
}
