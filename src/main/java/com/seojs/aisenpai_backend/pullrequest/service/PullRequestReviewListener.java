package com.seojs.aisenpai_backend.pullrequest.service;

import tools.jackson.databind.ObjectMapper;
import com.seojs.aisenpai_backend.ai.advisor.CriticFilterAdvisor;
import com.seojs.aisenpai_backend.ai.service.AiService;
import com.seojs.aisenpai_backend.github.dto.AiReviewResponseDto;
import com.seojs.aisenpai_backend.github.dto.ChangedFileDto;
import com.seojs.aisenpai_backend.github.service.GithubService;
import com.seojs.aisenpai_backend.github.service.TokenEncryptionService;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewRequestDto;
import com.seojs.aisenpai_backend.pullrequest.entity.PullRequest.ReviewStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
@Slf4j
public class PullRequestReviewListener {
    private static final String CRITIC_SYSTEM_PROMPT = """
            당신은 AI 코드 리뷰 코멘트 필터링 시스템(Critic)입니다.
            입력으로 전달된 리뷰 코멘트 목록(JSON)에서 아래 규칙을 위반한 코멘트를 지워내고 필터링된 결과만 JSON으로 반환하세요.
            
            [필터링 제약 조건]
            1. 추측성 지적 제거:
               - "~일 경우 위험합니다", "~할 수 있습니다", "~로 보입니다", "주의가 필요합니다"와 같이 실제 버그가 확인되지 않고 가정을 전제로 경고하는 코멘트는 제거하세요.
            2. 단위 테스트 요구 제거:
               - "단위 테스트를 추가하여 정확성을 높이세요" 등 기능 오류가 없음에도 테스트 작성을 요구하는 코멘트는 제거하세요.
            3. 기술적 오탐 제거:
               - 프레임워크 스펙(예: React Query의 enabled 옵션 등)을 오해하여 발생할 수 없는 런타임 에러를 경고하는 코멘트는 제거하세요.
            
            [출력 형식]
            - 반드시 마크다운 블록(```json) 없이 순수한 JSON 문자열로만 응답하세요.
            - 모든 코멘트가 필터링되었거나 지적할 내용이 없으면 comments는 빈 배열( [] )로 두고, generalReview도 비워두거나 간단히 필터링 완료 메시지만 적으세요.
            """;

    private final AiService aiService;
    private final ObjectMapper objectMapper;
    private final PullRequestService pullRequestService;
    private final TokenEncryptionService tokenEncryptionService;
    private final GithubService githubService;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FilePathRequest {
        private String path;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CodeSearchRequest {
        private String query;
    }

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
            
            // Webhook Payload 구성 (Repository Ai Settings에서 posting account 또는 pr.githubAccount를 가져오기 위한 owner, repo 획득)
            String owner = dto.getReviewContext() != null && dto.getReviewContext().getPullRequest() != null 
                    ? dto.getReviewContext().getPullRequest().getOwner() : null;
            String repo = dto.getReviewContext() != null && dto.getReviewContext().getPullRequest() != null 
                    ? dto.getReviewContext().getPullRequest().getRepo() : null;

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

            // 1. fetchFileContent 도구 동적 정의
            ToolCallback fetchFileContentTool = FunctionToolCallback.builder("fetchFileContent", (FilePathRequest req) -> {
                try {
                    log.info("AI dynamically requested file content for path: {}", req.getPath());
                    return githubService.getFileContent(openApiKey, owner, repo, req.getPath(), dto.getReviewStartedHeadSha());
                } catch (Exception e) {
                    log.error("Failed to fetch file content in tool for path: {}", req.getPath(), e);
                    return "Error fetching file: " + e.getMessage();
                }
            })
            .description("PR 리뷰 진행 중 특정 파일의 전체 소스코드를 읽어옵니다. 추가 정보나 의존성 분석이 필요할 때만 선별적으로 호출하세요.")
            .inputType(FilePathRequest.class)
            .build();

            // 2. searchCode 도구 동적 정의
            ToolCallback searchCodeTool = FunctionToolCallback.builder("searchCode", (CodeSearchRequest req) -> {
                try {
                    log.info("AI dynamically requested code search for query: {}", req.getQuery());
                    return githubService.searchCode(openApiKey, owner, repo, req.getQuery());
                } catch (Exception e) {
                    log.error("Failed to search code in tool for query: {}", req.getQuery(), e);
                    return "Error searching code: " + e.getMessage();
                }
            })
            .description("프로젝트 전체 소스코드에서 특정 키워드, 클래스명, 함수명 등을 검색합니다.")
            .inputType(CodeSearchRequest.class)
            .build();

            // 3. listDirectory 도구 동적 정의
            ToolCallback listDirectoryTool = FunctionToolCallback.builder("listDirectory", (FilePathRequest req) -> {
                try {
                    log.info("AI dynamically requested directory list for path: {}", req.getPath());
                    var treeDto = githubService.getRepositoryTree(openApiKey, owner, repo, dto.getReviewStartedHeadSha(), true);
                    if (treeDto == null || treeDto.getTree() == null) {
                        return "Directory empty or tree not found.";
                    }
                    String targetDir = req.getPath().trim();
                    if (!targetDir.endsWith("/") && !targetDir.isEmpty()) {
                        targetDir += "/";
                    }
                    final String finalTargetDir = targetDir;
                    List<String> items = new ArrayList<>();
                    for (var item : treeDto.getTree()) {
                        String itemPath = item.getPath();
                        if (finalTargetDir.isEmpty()) {
                            if (!itemPath.contains("/")) {
                                items.add(item.getType() + ": " + itemPath);
                            }
                        } else if (itemPath.startsWith(finalTargetDir)) {
                            String relativePath = itemPath.substring(finalTargetDir.length());
                            if (!relativePath.isEmpty() && !relativePath.contains("/")) {
                                items.add(item.getType() + ": " + relativePath);
                            }
                        }
                    }
                    if (items.isEmpty()) {
                        return "No items found in directory: " + req.getPath();
                    }
                    return String.join("\n", items);
                } catch (Exception e) {
                    log.error("Failed to list directory in tool for path: {}", req.getPath(), e);
                    return "Error listing directory: " + e.getMessage();
                }
            })
            .description("특정 디렉토리 경로(예: 'src/main/java')를 입력받아 그 아래에 있는 하위 디렉토리 및 파일 목록을 보여줍니다. 루트는 빈 값('') 혹은 '/'를 사용하세요.")
            .inputType(FilePathRequest.class)
            .build();

            // 4. fetchFilePatch 도구 동적 정의
            ToolCallback fetchFilePatchTool = FunctionToolCallback.builder("fetchFilePatch", (FilePathRequest req) -> {
                try {
                    log.info("AI dynamically requested file patch/diff for path: {}", req.getPath());
                    if (changedFiles == null) {
                        return "No changed files available.";
                    }
                    return changedFiles.stream()
                            .filter(file -> file.getFilename().equals(req.getPath()))
                            .map(ChangedFileDto::getPatch)
                            .findFirst()
                            .orElse("File not found or no diff (patch) available for path: " + req.getPath());
                } catch (Exception e) {
                    log.error("Failed to fetch file patch in tool for path: {}", req.getPath(), e);
                    return "Error fetching file patch: " + e.getMessage();
                }
            })
            .description("프롬프트 크기 한계로 인해 일부 또는 전부가 잘렸던(truncated) 특정 파일의 원본 Diff(Patch) 전체 내용을 가져옵니다.")
            .inputType(FilePathRequest.class)
            .build();

            // 5. 2차 Critic 필터 Advisor 정의
            CriticFilterAdvisor criticFilterAdvisor = new CriticFilterAdvisor(
                    aiService, openApiKey, CRITIC_SYSTEM_PROMPT, objectMapper
            );

            // 6. AI 호출 체인 구동 (Function Calling과 Advisor가 포함된 단일 호출)
            AiReviewResponseDto filteredReviewDto = aiService.callAiChatWithStructuredOutput(
                    openApiKey, systemPrompt, userPrompt, model, null, AiReviewResponseDto.class,
                    List.of(criticFilterAdvisor),
                    List.of(fetchFileContentTool, searchCodeTool, listDirectoryTool, fetchFilePatchTool)
            );

            String filteredReviewJson = objectMapper.writeValueAsString(filteredReviewDto);
            pullRequestService.updateAiReview(repositoryId, prNumber, filteredReviewJson, ReviewStatus.COMPLETED,
                    dto.getReviewStartedHeadSha(), dto.getReviewRunId(), dto.getReviewContext());
        } catch (Exception e) {
            String failureCode = ReviewFailureClassifier.codeFor(e);
            String failureMessage = ReviewFailureClassifier.messageFor(e);
            log.error("AI review failed. repositoryId={}, pr={}, reason={}", repositoryId, prNumber, failureCode, e);
            pullRequestService.updateAiReview(repositoryId, prNumber, failureMessage, ReviewStatus.FAILED,
                    dto.getReviewStartedHeadSha(), dto.getReviewRunId());
        }
    }
}
