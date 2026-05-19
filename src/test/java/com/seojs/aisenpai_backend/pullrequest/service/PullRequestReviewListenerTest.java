package com.seojs.aisenpai_backend.pullrequest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seojs.aisenpai_backend.ai.service.AiService;
import com.seojs.aisenpai_backend.github.dto.ChangedFileDto;
import com.seojs.aisenpai_backend.github.service.TokenEncryptionService;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewContextDto;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewRequestDto;
import com.seojs.aisenpai_backend.pullrequest.entity.PullRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class PullRequestReviewListenerTest {

    @Mock
    private AiService aiService;

    @Mock
    private PullRequestService pullRequestService;

    @Mock
    private TokenEncryptionService tokenEncryptionService;

    private PullRequestReviewListener listener;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        listener = new PullRequestReviewListener(aiService, new ObjectMapper(), pullRequestService,
                tokenEncryptionService);
    }

    @Test
    void handleReviewRequested_IncludesReviewContextInAiPayload() {
        // given
        ChangedFileDto changedFile = new ChangedFileDto(
                "src/App.ts", "modified", 1, 0, 1, 10, "sha", "blob", "raw", "contents", "@@ -1 +1 @@\n+a");
        ReviewContextDto reviewContext = ReviewContextDto.builder()
                .pullRequest(ReviewContextDto.PullRequestMetaDto.builder()
                        .owner("owner")
                        .repo("repo")
                        .prNumber(1)
                        .headSha("head")
                        .build())
                .changedFiles(List.of())
                .relatedFiles(List.of())
                .build();
        ReviewRequestDto request = new ReviewRequestDto(1L, 1, List.of(changedFile), "gpt-4o-mini",
                "system", "encrypted-key", "tree", "head", reviewContext);

        when(tokenEncryptionService.decryptToken("encrypted-key")).thenReturn("openai-key");
        when(aiService.callAiChat(eq("openai-key"), eq("system"), anyString(), eq("gpt-4o-mini"), isNull()))
                .thenReturn("{}");

        // when
        listener.handleReviewRequested(request);

        // then
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiService).callAiChat(eq("openai-key"), eq("system"), promptCaptor.capture(), eq("gpt-4o-mini"),
                isNull());
        assertTrue(promptCaptor.getValue().contains("\"reviewContext\""));
        assertTrue(promptCaptor.getValue().contains("\"changedFiles\""));
        assertFalse(promptCaptor.getValue().contains("\"repositoryTree\":\"tree\""));
        verify(pullRequestService).updateAiReview(1L, 1, "{}", PullRequest.ReviewStatus.COMPLETED, "head");
    }
}
