package com.seojs.aisenpai_backend.pullrequest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seojs.aisenpai_backend.ai.service.AiService;
import com.seojs.aisenpai_backend.github.dto.ChangedFileDto;
import com.seojs.aisenpai_backend.github.service.TokenEncryptionService;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewRequestDto;
import com.seojs.aisenpai_backend.pullrequest.entity.PullRequest.ReviewStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
@Slf4j
public class PullRequestReviewListener {
    private final AiService aiService;
    private final ObjectMapper objectMapper;
    private final PullRequestService pullRequestService;
    private final TokenEncryptionService tokenEncryptionService;

    @Async
    @EventListener
    public void handleReviewRequested(ReviewRequestDto dto) {
        List<ChangedFileDto> changedFiles = dto.getChangedFiles();
        Long repositoryId = dto.getRepositoryId();
        Integer prNumber = dto.getPrNumber();
        String model = dto.getModel();

        String systemPrompt = dto.getSystemPrompt();
        String encryptedKey = dto.getEncryptedOpenAiKey();

        try {
            String openApiKey = tokenEncryptionService.decryptToken(encryptedKey);
            Map<String, Object> aiPayload = new HashMap<>();
            if (dto.getReviewContext() != null) {
                aiPayload.put("reviewContext", dto.getReviewContext());
            } else {
                aiPayload.put("changedFiles", changedFiles);
                if (dto.getRepositoryTree() != null) {
                    aiPayload.put("repositoryTree", dto.getRepositoryTree());
                }
            }

            String userPrompt = objectMapper.writeValueAsString(aiPayload);
            String review = aiService.callAiChat(openApiKey, systemPrompt, userPrompt, model, null);
            pullRequestService.updateAiReview(repositoryId, prNumber, review, ReviewStatus.COMPLETED,
                    dto.getReviewStartedHeadSha());
        } catch (Exception e) {
            String failureCode = ReviewFailureClassifier.codeFor(e);
            String failureMessage = ReviewFailureClassifier.messageFor(e);
            log.error("AI review failed. repositoryId={}, pr={}, reason={}", repositoryId, prNumber, failureCode, e);
            pullRequestService.updateAiReview(repositoryId, prNumber, failureMessage, ReviewStatus.FAILED,
                    dto.getReviewStartedHeadSha());
        }
    }
}
