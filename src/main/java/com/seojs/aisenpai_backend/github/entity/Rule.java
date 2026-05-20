package com.seojs.aisenpai_backend.github.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor
public class Rule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repository_settings_id")
    private RepositoryAiSettings repositorySettings;

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(nullable = false)
    private boolean isEnabled;

    @Column(length = 200)
    private String targetFilePattern; // 적용 대상 파일 패턴 (Glob 패턴, 예: "**/*.java")

    @Builder
    public Rule(RepositoryAiSettings repositorySettings, String content, boolean isEnabled, String targetFilePattern) {
        this.repositorySettings = repositorySettings;
        this.content = content;
        this.isEnabled = isEnabled;
        this.targetFilePattern = targetFilePattern;
    }

    public void update(String content, boolean isEnabled, String targetFilePattern) {
        this.content = content;
        this.isEnabled = isEnabled;
        this.targetFilePattern = targetFilePattern;
    }

    public void toggle() {
        this.isEnabled = !this.isEnabled;
    }
}
