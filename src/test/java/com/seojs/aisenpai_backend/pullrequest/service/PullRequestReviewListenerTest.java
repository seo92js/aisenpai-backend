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
import com.seojs.aisenpai_backend.github.dto.GitTreeResponseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
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

    @Test
    void handleReviewRequested_RegistersAllToolsAndExecutesThemCorrectly() throws Exception {
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
                .build();

        when(tokenEncryptionService.decryptToken("encrypted-key")).thenReturn("openai-key");
        when(aiService.callAiChatWithStructuredOutput(eq("openai-key"), eq("system"), anyString(), eq("gpt-4o-mini"), isNull(), eq(AiReviewResponseDto.class), anyList(), anyList()))
                .thenReturn(emptyReview);

        // when
        listener.handleReviewRequested(request);

        // then
        ArgumentCaptor<List> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(aiService).callAiChatWithStructuredOutput(eq("openai-key"), eq("system"), anyString(), eq("gpt-4o-mini"),
                isNull(), eq(AiReviewResponseDto.class), anyList(), toolsCaptor.capture());

        List<ToolCallback> tools = toolsCaptor.getValue();
        assertEquals(4, tools.size());

        // Find tools by name
        ToolCallback fetchFileContentTool = tools.stream().filter(t -> t.getToolDefinition().name().equals("fetchFileContent")).findFirst().orElseThrow();
        ToolCallback searchCodeTool = tools.stream().filter(t -> t.getToolDefinition().name().equals("searchCode")).findFirst().orElseThrow();
        ToolCallback listDirectoryTool = tools.stream().filter(t -> t.getToolDefinition().name().equals("listDirectory")).findFirst().orElseThrow();
        ToolCallback fetchFilePatchTool = tools.stream().filter(t -> t.getToolDefinition().name().equals("fetchFilePatch")).findFirst().orElseThrow();

        // 1. fetchFileContent Tool execution check
        when(githubService.getFileContent(eq("openai-key"), eq("owner"), eq("repo"), eq("src/App.ts"), eq("head"))).thenReturn("source code");
        String fileContentResult = fetchFileContentTool.call("{\"path\":\"src/App.ts\"}");
        assertEquals("source code", objectMapper.readValue(fileContentResult, String.class));

        // 2. searchCode Tool execution check
        ObjectMapper mapper = new ObjectMapper();
        var mockSearchResponse = mapper.createObjectNode().put("total_count", 1);
        when(githubService.searchCode(eq("openai-key"), eq("owner"), eq("repo"), eq("class App"))).thenReturn(mockSearchResponse);
        String searchResult = searchCodeTool.call("{\"query\":\"class App\"}");
        assertTrue(searchResult.contains("total_count"));

        // 3. listDirectory Tool execution check
        GitTreeResponseDto mockTree = new GitTreeResponseDto();
        GitTreeResponseDto.GitTreeItemDto item1 = new GitTreeResponseDto.GitTreeItemDto();
        item1.setPath("src/App.ts");
        item1.setType("blob");
        GitTreeResponseDto.GitTreeItemDto item2 = new GitTreeResponseDto.GitTreeItemDto();
        item2.setPath("src/components/Button.ts");
        item2.setType("blob");
        mockTree.setTree(List.of(item1, item2));

        when(githubService.getRepositoryTree(eq("openai-key"), eq("owner"), eq("repo"), eq("head"), eq(true))).thenReturn(mockTree);
        String listResult = listDirectoryTool.call("{\"path\":\"src\"}");
        String listResultRaw = objectMapper.readValue(listResult, String.class);
        assertTrue(listResultRaw.contains("blob: App.ts"));
        assertFalse(listResultRaw.contains("Button.ts")); // because button is under src/components, not 1-level under src

        // 4. fetchFilePatch Tool execution check
        String patchResult = fetchFilePatchTool.call("{\"path\":\"src/App.ts\"}");
        assertEquals("@@ -1 +1 @@\n+a", objectMapper.readValue(patchResult, String.class));
    }
}
