package com.seojs.aisenpai_backend.pullrequest.dto;

import com.seojs.aisenpai_backend.github.dto.ChangedFileDto;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
public class ReviewRequestDto {
    private Long repositoryId;
    private Integer prNumber;
    private List<ChangedFileDto> changedFiles;
    private String model;
    private String systemPrompt;
    private String encryptedOpenAiKey;
    private String repositoryTree;
    private String reviewStartedHeadSha;
    private String reviewRunId;
    private ReviewContextDto reviewContext;

    public ReviewRequestDto(Long repositoryId, Integer prNumber, List<ChangedFileDto> changedFiles, String model,
            String systemPrompt, String encryptedOpenAiKey) {
        this.repositoryId = repositoryId;
        this.prNumber = prNumber;
        this.changedFiles = changedFiles;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.encryptedOpenAiKey = encryptedOpenAiKey;
    }

    public ReviewRequestDto(Long repositoryId, Integer prNumber, List<ChangedFileDto> changedFiles, String model,
            String systemPrompt, String encryptedOpenAiKey, String repositoryTree, String reviewStartedHeadSha,
            ReviewContextDto reviewContext) {
        this(repositoryId, prNumber, changedFiles, model, systemPrompt, encryptedOpenAiKey, repositoryTree,
                reviewStartedHeadSha, null, reviewContext);
    }

    public ReviewRequestDto(Long repositoryId, Integer prNumber, List<ChangedFileDto> changedFiles, String model,
            String systemPrompt, String encryptedOpenAiKey, String repositoryTree, String reviewStartedHeadSha,
            String reviewRunId, ReviewContextDto reviewContext) {
        this.repositoryId = repositoryId;
        this.prNumber = prNumber;
        this.changedFiles = changedFiles;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.encryptedOpenAiKey = encryptedOpenAiKey;
        this.repositoryTree = repositoryTree;
        this.reviewStartedHeadSha = reviewStartedHeadSha;
        this.reviewRunId = reviewRunId;
        this.reviewContext = reviewContext;
    }
}
