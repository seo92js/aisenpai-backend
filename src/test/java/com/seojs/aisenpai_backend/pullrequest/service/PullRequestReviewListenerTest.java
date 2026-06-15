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
import org.springframework.web.client.ResourceAccessException;

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
                "system", "encrypted-key", "tree", "head", "run-1", reviewContext);

        when(tokenEncryptionService.decryptToken("encrypted-key")).thenReturn("openai-key");
        when(aiService.callAiChat(eq("openai-key"), eq("system"), anyString(), eq("gpt-4o-mini"), isNull()))
                .thenReturn("{}");
        when(aiService.callAiChat(eq("openai-key"), contains("Critic"), anyString(), eq("gpt-4o-mini"), eq(0.1)))
                .thenReturn(null);

        // when
        listener.handleReviewRequested(request);

        // then
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiService).callAiChat(eq("openai-key"), eq("system"), promptCaptor.capture(), eq("gpt-4o-mini"),
                isNull());
        assertTrue(promptCaptor.getValue().contains("\"reviewContext\""));
        assertTrue(promptCaptor.getValue().contains("\"changedFiles\""));
        assertFalse(promptCaptor.getValue().contains("\"repositoryTree\":\"tree\""));
        verify(pullRequestService).updateAiReview(1L, 1, "{}", PullRequest.ReviewStatus.COMPLETED, "head", "run-1");
    }

    @Test
    void handleReviewRequested_AppliesCriticFilterToCleanNoiseReviews() {
        // given
        ChangedFileDto changedFile = new ChangedFileDto(
                "src/App.ts", "modified", 1, 0, 1, 10, "sha", "blob", "raw", "contents", "@@ -1 +1 @@\n+a");
        ReviewRequestDto request = new ReviewRequestDto(1L, 1, List.of(changedFile), "gpt-4o-mini",
                "system", "encrypted-key", "tree", "head", "run-1", null);

        String rawReview = "{\"comments\": [{\"path\":\"src/App.ts\",\"codeSnippet\":\"a\",\"body\":\"This is a bug.\"},{\"path\":\"src/App.ts\",\"codeSnippet\":\"a\",\"body\":\"단위 테스트를 추가하세요.\"}]}";
        String filteredReview = "{\"comments\": [{\"path\":\"src/App.ts\",\"codeSnippet\":\"a\",\"body\":\"This is a bug.\"}]}";

        when(tokenEncryptionService.decryptToken("encrypted-key")).thenReturn("openai-key");
        when(aiService.callAiChat(eq("openai-key"), eq("system"), anyString(), eq("gpt-4o-mini"), isNull()))
                .thenReturn(rawReview);
        when(aiService.callAiChat(eq("openai-key"), contains("Critic"), eq(rawReview), eq("gpt-4o-mini"), eq(0.1)))
                .thenReturn(filteredReview);

        // when
        listener.handleReviewRequested(request);

        // then
        verify(pullRequestService).updateAiReview(1L, 1, filteredReview, PullRequest.ReviewStatus.COMPLETED, "head", "run-1");
    }

    @Test
    void handleReviewRequested_StoresClassifiedFailureMessage() {
        // given
        ReviewRequestDto request = new ReviewRequestDto(1L, 1, List.of(), "gpt-4o-mini",
                "system", "encrypted-key", "tree", "head", "run-1", null);
        when(tokenEncryptionService.decryptToken("encrypted-key")).thenReturn("openai-key");
        when(aiService.callAiChat(eq("openai-key"), eq("system"), anyString(), eq("gpt-4o-mini"), isNull()))
                .thenThrow(new ResourceAccessException("Read timed out"));

        // when
        listener.handleReviewRequested(request);

        // then
        verify(pullRequestService).updateAiReview(1L, 1,
                "AI review failed: OpenAI 응답 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.",
                PullRequest.ReviewStatus.FAILED, "head", "run-1");
    }
}
