package com.seojs.aisenpai_backend.pullrequest.service;

import tools.jackson.databind.ObjectMapper;
import com.seojs.aisenpai_backend.ai.service.AiService;
import com.seojs.aisenpai_backend.github.dto.AiReviewResponseDto;
import com.seojs.aisenpai_backend.github.dto.ChangedFileDto;
import com.seojs.aisenpai_backend.github.dto.ReviewCommentDto;
import com.seojs.aisenpai_backend.github.service.GithubService;
import com.seojs.aisenpai_backend.github.service.TokenEncryptionService;
import com.seojs.aisenpai_backend.ai.advisor.CriticFilterAdvisor;
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

    @Mock
    private GithubService githubService;

    private PullRequestReviewListener listener;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        objectMapper = new ObjectMapper();
        listener = new PullRequestReviewListener(aiService, objectMapper, pullRequestService,
                tokenEncryptionService, githubService);
    }

    @Test
    void handleReviewRequested_IncludesReviewContextInAiPayload() throws Exception {
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

        AiReviewResponseDto emptyReview = AiReviewResponseDto.builder()
                .generalReview("Review")
                .comments(List.of())
                .contextFiles(List.of())
                .build();

        when(tokenEncryptionService.decryptToken("encrypted-key")).thenReturn("openai-key");
        when(aiService.callAiChatWithStructuredOutput(eq("openai-key"), eq("system"), anyString(), eq("gpt-4o-mini"), isNull(), eq(AiReviewResponseDto.class), anyList(), anyList()))
                .thenReturn(emptyReview);

        // when
        listener.handleReviewRequested(request);

        // then
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiService).callAiChatWithStructuredOutput(eq("openai-key"), eq("system"), promptCaptor.capture(), eq("gpt-4o-mini"),
                isNull(), eq(AiReviewResponseDto.class), anyList(), anyList());
        assertTrue(promptCaptor.getValue().contains("\"reviewContext\""));
        assertTrue(promptCaptor.getValue().contains("\"changedFiles\""));
        assertFalse(promptCaptor.getValue().contains("\"repositoryTree\":\"tree\""));

        String expectedJson = objectMapper.writeValueAsString(emptyReview);
        verify(pullRequestService).updateAiReview(eq(1L), eq(1), eq(expectedJson), eq(PullRequest.ReviewStatus.COMPLETED), eq("head"), eq("run-1"), eq(reviewContext));
    }

    @Test
    void handleReviewRequested_AppliesCriticFilterToCleanNoiseReviews() throws Exception {
        // given
        ChangedFileDto changedFile = new ChangedFileDto(
                "src/App.ts", "modified", 1, 0, 1, 10, "sha", "blob", "raw", "contents", "@@ -1 +1 @@\n+a");
        ReviewRequestDto request = new ReviewRequestDto(1L, 1, List.of(changedFile), "gpt-4o-mini",
                "system", "encrypted-key", "tree", "head", "run-1", null);

        AiReviewResponseDto rawReview = AiReviewResponseDto.builder()
                .comments(List.of())
                .build();

        when(tokenEncryptionService.decryptToken("encrypted-key")).thenReturn("openai-key");
        when(aiService.callAiChatWithStructuredOutput(eq("openai-key"), eq("system"), anyString(), eq("gpt-4o-mini"), isNull(), eq(AiReviewResponseDto.class), anyList(), anyList()))
                .thenReturn(rawReview);

        // when
        listener.handleReviewRequested(request);

        // then
        ArgumentCaptor<List> advisorsCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiService).callAiChatWithStructuredOutput(eq("openai-key"), eq("system"), anyString(), eq("gpt-4o-mini"),
                isNull(), eq(AiReviewResponseDto.class), advisorsCaptor.capture(), anyList());
        
        List advisors = advisorsCaptor.getValue();
        assertFalse(advisors.isEmpty());
        assertTrue(advisors.get(0) instanceof CriticFilterAdvisor);

        String expectedJson = objectMapper.writeValueAsString(rawReview);
        verify(pullRequestService).updateAiReview(eq(1L), eq(1), eq(expectedJson), eq(PullRequest.ReviewStatus.COMPLETED), eq("head"), eq("run-1"), isNull());
    }

    @Test
    void handleReviewRequested_StoresClassifiedFailureMessage() {
        // given
        ReviewRequestDto request = new ReviewRequestDto(1L, 1, List.of(), "gpt-4o-mini",
                "system", "encrypted-key", "tree", "head", "run-1", null);
        when(tokenEncryptionService.decryptToken("encrypted-key")).thenReturn("openai-key");
        when(aiService.callAiChatWithStructuredOutput(eq("openai-key"), eq("system"), anyString(), eq("gpt-4o-mini"), isNull(), eq(AiReviewResponseDto.class), anyList(), anyList()))
                .thenThrow(new ResourceAccessException("Read timed out"));

        // when
        listener.handleReviewRequested(request);

        // then
        verify(pullRequestService).updateAiReview(1L, 1,
                "AI review failed: OpenAI 응답 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.",
                PullRequest.ReviewStatus.FAILED, "head", "run-1");
    }
}
