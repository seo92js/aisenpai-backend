
package com.seojs.aisenpai_backend.pullrequest.service;

import com.seojs.aisenpai_backend.exception.OpenAiKeyNotSetEx;
import com.seojs.aisenpai_backend.exception.PullRequestNotFoundEx;
import com.seojs.aisenpai_backend.exception.WebhookProcessingEx;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seojs.aisenpai_backend.github.dto.AiReviewResponseDto;
import com.seojs.aisenpai_backend.github.dto.ChangedFileDto;
import com.seojs.aisenpai_backend.github.dto.GitTreeResponseDto;
import com.seojs.aisenpai_backend.github.dto.GithubReviewRequestDto;
import com.seojs.aisenpai_backend.github.dto.PullRequestInfoDto;
import com.seojs.aisenpai_backend.github.dto.ReviewCommentDto;
import com.seojs.aisenpai_backend.github.dto.WebhookPayloadDto;
import com.seojs.aisenpai_backend.github.entity.GithubAccount;
import com.seojs.aisenpai_backend.github.service.GithubService;
import com.seojs.aisenpai_backend.github.service.ReviewAnchorService;
import com.seojs.aisenpai_backend.github.service.TokenEncryptionService;
import com.seojs.aisenpai_backend.github.service.WebhookSecurityService;
import com.seojs.aisenpai_backend.notification.entity.NotificationType;
import com.seojs.aisenpai_backend.notification.service.NotificationService;
import com.seojs.aisenpai_backend.pullrequest.dto.PullRequestResponseDto;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewRequestDto;
import com.seojs.aisenpai_backend.pullrequest.entity.PullRequest;
import com.seojs.aisenpai_backend.pullrequest.entity.PullRequest.PullRequestState;
import com.seojs.aisenpai_backend.pullrequest.entity.PullRequest.ReviewStatus;
import com.seojs.aisenpai_backend.pullrequest.repository.PullRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.List;

import com.seojs.aisenpai_backend.github.dto.GithubApiCommentDto;

@Slf4j
@RequiredArgsConstructor
@Service
public class PullRequestService {
    private final PullRequestRepository pullRequestRepository;
    private final GithubService githubService;
    private final WebhookSecurityService webhookSecurityService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final TokenEncryptionService tokenEncryptionService;
    private final NotificationService notificationService;
    private final ReviewAnchorService reviewAnchorService;

    /**
     * PR 웹훅 이벤트를 처리하고 데이터베이스에 저장
     */
    @Transactional
    public void processAndSaveWebhook(String payload, String signature) {
        webhookSecurityService.validateWebhookSignature(payload, signature);
        processWebhookPayload(payload);
    }

    /**
     * 웹훅 페이로드 처리
     */
    private void processWebhookPayload(String payload) {
        try {
            WebhookPayloadDto webhookPayload = objectMapper.readValue(payload, WebhookPayloadDto.class);
            String action = webhookPayload.getAction();

            // PR 관련 액션만 처리
            if (isPrAction(action)) {
                savePullRequest(webhookPayload);
            }
        } catch (Exception e) {
            throw new WebhookProcessingEx("Webhook processing failed", e);
        }
    }

    /**
     * 특정 저장소의 PR 목록 조회 (owner/repo 기준 - GitHub API로 repositoryId 조회 후 사용)
     */
    @Transactional(readOnly = true)
    public List<PullRequestResponseDto> getPullRequestList(String owner, String repo, String accessToken) {
        Long repositoryId = githubService.getRepositoryId(accessToken, owner, repo);
        return pullRequestRepository
                .findByRepositoryIdOrderByUpdatedAtDesc(repositoryId).stream()
                .map(PullRequestResponseDto::fromEntity)
                .toList();
    }

    /**
     * PR 변경된 파일 목록 조회 (owner/repo 기준 - GitHub API로 repositoryId 조회 후 사용)
     */
    @Transactional(readOnly = true)
    public List<ChangedFileDto> getPullRequestWithChanges(String owner, String repo, Integer prNumber,
            String accessToken) {
        Long repositoryId = githubService.getRepositoryId(accessToken, owner, repo);
        PullRequest pr = findByRepositoryIdAndPrNumberOrThrow(repositoryId, prNumber);
        return githubService.getChangedFiles(accessToken, owner, repo, prNumber);
    }

    /**
     * 특정 저장소의 특정 PR 번호로 조회 - 존재하지 않으면 예외 발생
     */
    private PullRequest findByRepositoryIdAndPrNumberOrThrow(Long repositoryId, Integer prNumber) {
        return pullRequestRepository
                .findByRepositoryIdAndPrNumber(repositoryId, prNumber)
                .orElseThrow(() -> new PullRequestNotFoundEx("Pull request not found for repositoryId: "
                        + repositoryId + ", prNumber: " + prNumber));
    }

    private PullRequest findWithLockByRepositoryIdAndPrNumberOrThrow(Long repositoryId, Integer prNumber) {
        return pullRequestRepository
                .findWithLockByRepositoryIdAndPrNumber(repositoryId, prNumber)
                .orElseThrow(() -> new PullRequestNotFoundEx("Pull request not found for repositoryId: "
                        + repositoryId + ", prNumber: " + prNumber));
    }

    /**
     * ai 리뷰 시작
     */
    @Transactional
    public void review(String owner, String repo, Integer prNumber, String accessToken, String model) {
        Long repositoryId = githubService.getRepositoryId(accessToken, owner, repo);
        PullRequest pr = findWithLockByRepositoryIdAndPrNumberOrThrow(repositoryId, prNumber);

        PullRequestInfoDto prInfo = githubService.getPullRequestInfo(accessToken, owner, repo, prNumber);
        String currentHeadSha = extractHeadSha(prInfo, pr.getHeadSha());
        String currentBaseSha = extractBaseSha(prInfo, pr.getBaseSha());
        pr.updatePullRequestSnapshot(pr.getAction(), resolvePrState(prInfo, pr.getPrState()), currentHeadSha,
                currentBaseSha);

        if (pr.getPrState() != PullRequestState.OPEN) {
            log.info("Skipping review request for PR #{} because PR state is {}", prNumber, pr.getPrState());
            return;
        }

        if (pr.getStatus() == ReviewStatus.IN_PROGRESS && pr.isReviewForCurrentHead(currentHeadSha)) {
            log.info("Skipping duplicate review request for PR #{} at head {}", prNumber, currentHeadSha);
            return;
        }

        List<ChangedFileDto> changedFiles = githubService.getChangedFiles(accessToken, owner, repo, prNumber);

        GithubAccount githubAccount = pr.getGithubAccount();

        if (githubAccount.getAiSettings().getOpenAiKey() == null
                || githubAccount.getAiSettings().getOpenAiKey().isEmpty()) {
            throw new OpenAiKeyNotSetEx("OpenAI API key is not set. Please set it in the settings.");
        }

        List<String> ignorePatterns = githubAccount.getAiSettings().getIgnorePatternsAsList();
        List<ChangedFileDto> filteredFiles = changedFiles;

        if (!ignorePatterns.isEmpty()) {
            List<PathMatcher> matchers = ignorePatterns.stream()
                    .map(this::convertUserPatternToGlob)
                    .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                    .toList();

            filteredFiles = changedFiles.stream()
                    .filter(file -> matchers.stream()
                            .noneMatch(matcher -> matcher.matches(Paths.get(file.getFilename()))))
                    .toList();
        }

        pr.markReviewStarted(currentHeadSha);

        // LLM 호출은 이벤트 리스너에서 수행
        String systemPrompt = githubAccount.getAiSettings().buildSystemPrompt();
        String encryptedOpenAiKey = githubAccount.getAiSettings().getOpenAiKey();

        // 트리 구조 가져오기
        String repositoryTreeString = null;
        try {
            String targetTreeSha = "HEAD";
            if (prInfo != null && prInfo.getBase() != null && prInfo.getBase().getSha() != null) {
                targetTreeSha = prInfo.getBase().getSha();
                log.info("Found base branch SHA for PR #{}: {}", prNumber, targetTreeSha);
            }

            GitTreeResponseDto treeDto = githubService.getRepositoryTree(accessToken, owner, repo, targetTreeSha, true);
            if (treeDto != null && treeDto.getTree() != null) {
                StringBuilder sb = new StringBuilder();
                for (GitTreeResponseDto.GitTreeItemDto item : treeDto.getTree()) {
                    sb.append("- ").append(item.getType()).append(": ").append(item.getPath()).append("\n");
                }
                repositoryTreeString = sb.toString();
                log.info("Successfully fetched repository tree context for PR #{}", prNumber);
            }
        } catch (Exception e) {
            log.warn(
                    "Failed to fetch repository tree context for PR #{}. Proceeding without structural context. Error: {}",
                    prNumber, e.getMessage());
        }

        eventPublisher.publishEvent(
                new ReviewRequestDto(repositoryId, prNumber, filteredFiles, model, systemPrompt, encryptedOpenAiKey,
                        repositoryTreeString, currentHeadSha));
    }

    /**
     * ai 리뷰 결과 업데이트
     */
    @Transactional
    public void updateAiReview(Long repositoryId, Integer prNumber, String aiReview,
            ReviewStatus status) {
        updateAiReview(repositoryId, prNumber, aiReview, status, null);
    }

    @Transactional
    public void updateAiReview(Long repositoryId, Integer prNumber, String aiReview,
            ReviewStatus status, String reviewStartedHeadSha) {
        PullRequest pr = findWithLockByRepositoryIdAndPrNumberOrThrow(repositoryId, prNumber);

        if (reviewStartedHeadSha != null && pr.getPrState() != PullRequestState.OPEN) {
            pr.markReviewStale(aiReview);
            log.info("Marked review for PR #{} as stale because PR state is {}. reviewedHead={}", prNumber,
                    pr.getPrState(), reviewStartedHeadSha);
            return;
        }

        if (reviewStartedHeadSha != null && !pr.isReviewForCurrentHead(reviewStartedHeadSha)) {
            if (pr.getReviewStartedHeadSha() != null && !reviewStartedHeadSha.equals(pr.getReviewStartedHeadSha())) {
                log.info("Ignoring stale review completion for PR #{} because a newer review is in progress. "
                        + "completedHead={}, activeReviewHead={}, currentHead={}", prNumber, reviewStartedHeadSha,
                        pr.getReviewStartedHeadSha(), pr.getHeadSha());
                return;
            }
            pr.markReviewStale(aiReview);
            log.info("Marked stale review for PR #{}. reviewedHead={}, currentHead={}", prNumber,
                    reviewStartedHeadSha, pr.getHeadSha());
            return;
        }

        if (status == ReviewStatus.COMPLETED) {
            pr.markReviewCompleted(aiReview, reviewStartedHeadSha);
        } else if (status == ReviewStatus.FAILED) {
            pr.markReviewFailed(aiReview, reviewStartedHeadSha);
        } else {
            pr.updateAiReview(aiReview);
            pr.updateStatus(status);
        }

        GithubAccount account = pr.getGithubAccount();

        if (status == ReviewStatus.COMPLETED) {
            notificationService.createNotification(
                    account,
                    NotificationType.REVIEW_COMPLETE,
                    pr);

            // GitHub PR에 댓글 자동 게시
            if (Boolean.TRUE.equals(account.getAiSettings().getAutoPostToGithub())) {
                processAndPostReview(account, pr, aiReview);
            }
        } else if (status == ReviewStatus.FAILED) {
            notificationService.createNotification(
                    account,
                    NotificationType.REVIEW_FAILED,
                    pr);
        }
    }

    /**
     * AI 리뷰를 처리하고 GitHub에 게시 (파싱 및 분기 처리)
     */
    private void processAndPostReview(GithubAccount account, PullRequest pr, String aiReview) {
        try {
            String accessToken = tokenEncryptionService.decryptToken(account.getAccessToken());
            String sanitizedAiReview = sanitizeAiReview(aiReview);

            // Diff 정보 가져오기 (라인 매칭용)
            List<ChangedFileDto> changedFiles = githubService.getChangedFiles(accessToken,
                    pr.getGithubAccount().getLoginId(), pr.getRepositoryName(), pr.getPrNumber());

            try {
                AiReviewResponseDto aiResponse = objectMapper.readValue(sanitizedAiReview, AiReviewResponseDto.class);

                if (aiResponse.getComments() != null && !aiResponse.getComments().isEmpty()) {
                    List<ReviewCommentDto> enrichedComments = calculateLineNumbers(aiResponse.getComments(),
                            changedFiles);

                    // DB 업데이트 (라인 번호 포함된 데이터 저장)
                    saveEnrichedReviewToDb(pr, aiResponse, enrichedComments);

                    // GitHub 게시 (API용 DTO 변환 후 전송)
                    postCommentsToGitHub(accessToken, account, pr, aiResponse, enrichedComments);

                } else {
                    // 코멘트가 없으면 일반 리뷰 게시 (총평만)
                    String body = aiResponse.getGeneralReview() != null ? aiResponse.getGeneralReview() : aiReview;
                    postGeneralComment(accessToken, account, pr, body);
                }
            } catch (Exception e) {
                log.warn("Failed to parse AI review as JSON, falling back to comment. Error: {}", e.getMessage());
                postGeneralComment(accessToken, account, pr, aiReview);
            }
        } catch (Exception e) {
            log.warn("Failed to process and post review to GitHub PR #{}: {}", pr.getPrNumber(), e.getMessage());
        }
    }

    private List<ReviewCommentDto> calculateLineNumbers(List<ReviewCommentDto> comments,
            List<ChangedFileDto> changedFiles) {
        List<ReviewCommentDto> enrichedComments = new java.util.ArrayList<>();
        for (var comment : comments) {
            String filePatch = changedFiles.stream()
                    .filter(f -> f.getFilename().equals(comment.getPath()))
                    .findFirst()
                    .map(ChangedFileDto::getPatch)
                    .orElse(null);
            Integer line = reviewAnchorService.findLineNumber(filePatch, comment.getCodeSnippet());

            enrichedComments.add(ReviewCommentDto.builder()
                    .path(comment.getPath())
                    .codeSnippet(comment.getCodeSnippet())
                    .line(line)
                    .side(line != null ? "RIGHT" : null)
                    .body(comment.getBody())
                    .build());
        }
        return enrichedComments;
    }

    private void saveEnrichedReviewToDb(PullRequest pr, AiReviewResponseDto originalResponse,
            List<ReviewCommentDto> enrichedComments) throws Exception {
        AiReviewResponseDto updatedResponse = AiReviewResponseDto.builder()
                .generalReview(originalResponse.getGeneralReview())
                .comments(enrichedComments)
                .build();

        String updatedJson = objectMapper.writeValueAsString(updatedResponse);
        pr.updateAiReview(updatedJson);
    }

    private void postCommentsToGitHub(String accessToken, GithubAccount account, PullRequest pr,
            AiReviewResponseDto aiResponse, List<ReviewCommentDto> enrichedComments) {
        List<GithubApiCommentDto> commentsToPost = enrichedComments.stream()
                .filter(c -> c.getLine() != null)
                .map(c -> GithubApiCommentDto.builder()
                        .path(c.getPath())
                        .line(c.getLine())
                        .side("RIGHT")
                        .body(formatReviewForGithub(c.getBody()))
                        .build())
                .toList();

        StringBuilder manualFallback = new StringBuilder();

        // 1. 인라인 코멘트 게시
        if (!commentsToPost.isEmpty()) {
            GithubReviewRequestDto reviewRequest = GithubReviewRequestDto.builder()
                    .body(formatReviewForGithub(aiResponse.getGeneralReview()))
                    .event("COMMENT")
                    .comments(commentsToPost)
                    .build();

            try {
                githubService.postPRReview(accessToken, account.getLoginId(), pr.getRepositoryName(),
                        pr.getPrNumber(), reviewRequest);
            } catch (Exception e) {
                log.warn("Failed to post inline review: {}", e.getMessage());
                manualFallback.append("\n(Also failed to post inline comments due to error)\n");
            }
        }

        // Fallback 코멘트 준비 (라인 매칭 실패한 것들)
        enrichedComments.stream().filter(c -> c.getLine() == null).forEach(c -> {
            manualFallback.append(String.format("- **%s**: %s\n> `%s`\n\n",
                    c.getPath(), c.getBody(), c.getCodeSnippet()));
        });

        // Fallback 게시 (혹은 인라인 실패 시에도 게시)
        if (manualFallback.length() > 0) {
            String fallbackBody = (commentsToPost.isEmpty() ? aiResponse.getGeneralReview() + "\n\n" : "") +
                    "### 추가 코멘트 (라인 매칭 실패)\n" + manualFallback.toString();
            postGeneralComment(accessToken, account, pr, fallbackBody);
        } else if (commentsToPost.isEmpty()) {
            postGeneralComment(accessToken, account, pr, aiResponse.getGeneralReview());
        }
    }

    /**
     * AI 응답에서 마크다운 코드 블록 제거
     */
    private String sanitizeAiReview(String aiReview) {
        String sanitized = aiReview.trim();
        if (sanitized.startsWith("```json")) {
            sanitized = sanitized.substring(7);
        } else if (sanitized.startsWith("```")) {
            sanitized = sanitized.substring(3);
        }
        if (sanitized.endsWith("```")) {
            sanitized = sanitized.substring(0, sanitized.length() - 3);
        }
        return sanitized.trim();
    }

    /**
     * 일반 코멘트 게시
     */
    private void postGeneralComment(String accessToken, GithubAccount account, PullRequest pr, String body) {
        String formattedReview = formatReviewForGithub(body);
        githubService.postPRComment(accessToken, account.getLoginId(), pr.getRepositoryName(), pr.getPrNumber(),
                formattedReview);
    }

    /**
     * GitHub 댓글용 리뷰 포맷팅
     */
    private String formatReviewForGithub(String aiReview) {
        return "## 🤖 AI Code Review by AISenpai\n\n" + aiReview
                + "\n\n---\n*Powered by [AISenpai](https://aisenpai.dev)*";
    }

    /**
     * ai 리뷰 결과 조회
     */
    @Transactional(readOnly = true)
    public String getAiReview(String owner, String repo, Integer prNumber, String accessToken) {
        Long repositoryId = githubService.getRepositoryId(accessToken, owner, repo);
        PullRequest pr = findByRepositoryIdAndPrNumberOrThrow(repositoryId, prNumber);
        return pr.getAiReview();
    }

    /**
     * PR 관련 액션인지 확인
     */
    private boolean isPrAction(String action) {
        return List.of("opened", "synchronize", "reopened", "closed", "merged").contains(action);
    }

    private PullRequestState resolvePrState(WebhookPayloadDto webhookPayload) {
        String action = webhookPayload.getAction();
        WebhookPayloadDto.PullRequestDto pullRequest = webhookPayload.getPullRequest();

        if ("closed".equals(action)) {
            return Boolean.TRUE.equals(pullRequest.getMerged()) ? PullRequestState.MERGED : PullRequestState.CLOSED;
        }
        if ("merged".equals(action)) {
            return PullRequestState.MERGED;
        }
        if ("closed".equals(pullRequest.getState())) {
            return Boolean.TRUE.equals(pullRequest.getMerged()) ? PullRequestState.MERGED : PullRequestState.CLOSED;
        }
        return PullRequestState.OPEN;
    }

    private String extractHeadSha(WebhookPayloadDto webhookPayload) {
        WebhookPayloadDto.RefDto head = webhookPayload.getPullRequest().getHead();
        return head != null ? head.getSha() : null;
    }

    private String extractBaseSha(WebhookPayloadDto webhookPayload) {
        WebhookPayloadDto.RefDto base = webhookPayload.getPullRequest().getBase();
        return base != null ? base.getSha() : null;
    }

    private String extractHeadSha(PullRequestInfoDto prInfo, String fallback) {
        if (prInfo != null && prInfo.getHead() != null && prInfo.getHead().getSha() != null) {
            return prInfo.getHead().getSha();
        }
        return fallback;
    }

    private String extractBaseSha(PullRequestInfoDto prInfo, String fallback) {
        if (prInfo != null && prInfo.getBase() != null && prInfo.getBase().getSha() != null) {
            return prInfo.getBase().getSha();
        }
        return fallback;
    }

    private PullRequestState resolvePrState(PullRequestInfoDto prInfo, PullRequestState fallback) {
        if (prInfo == null || prInfo.getState() == null) {
            return fallback != null ? fallback : PullRequestState.OPEN;
        }
        return "closed".equals(prInfo.getState()) ? PullRequestState.CLOSED : PullRequestState.OPEN;
    }

    /**
     * PR 정보를 데이터베이스에 저장
     */
    private void savePullRequest(WebhookPayloadDto webhookPayload) {
        Long repoId = webhookPayload.getRepository().getId();
        String repoName = webhookPayload.getRepository().getName();
        String loginId = webhookPayload.getRepository().getOwner().getLogin();
        Integer prNumber = webhookPayload.getPullRequest().getNumber();
        String action = webhookPayload.getAction();
        String title = webhookPayload.getPullRequest().getTitle();
        PullRequestState prState = resolvePrState(webhookPayload);
        String headSha = extractHeadSha(webhookPayload);
        String baseSha = extractBaseSha(webhookPayload);

        PullRequest existingPr = pullRequestRepository
                .findWithLockByRepositoryIdAndPrNumber(repoId, prNumber)
                .orElse(null);

        if (existingPr != null) {
            updateExistingPullRequest(existingPr, action, prState, headSha, baseSha);
        } else {
            createNewPullRequest(repoId, repoName, loginId, prNumber, action, title, prState, headSha, baseSha);
        }

        evictRepositoryCache(loginId);
    }

    private void evictRepositoryCache(String loginId) {
        try {
            String accessToken = githubService.findAccessTokenByLoginId(loginId);
            if (accessToken != null) {
                githubService.evictRepositoryCache(accessToken);
            }
        } catch (Exception e) {
            log.warn("Failed to evict repository cache for loginId {}: {}", loginId, e.getMessage());
        }
    }

    /**
     * 기존 PR 업데이트
     */
    private void updateExistingPullRequest(PullRequest existingPr, String action, PullRequestState prState,
            String headSha, String baseSha) {
        ReviewStatus currentStatus = existingPr.getStatus();
        String nextHeadSha = headSha != null ? headSha : existingPr.getHeadSha();
        String nextBaseSha = baseSha != null ? baseSha : existingPr.getBaseSha();

        if (prState == PullRequestState.OPEN && "synchronize".equals(action)) {
            if (currentStatus == ReviewStatus.IN_PROGRESS) {
                existingPr.updateStatus(ReviewStatus.STALE);
            } else if (currentStatus == ReviewStatus.COMPLETED || currentStatus == ReviewStatus.FAILED
                    || currentStatus == ReviewStatus.STALE) {
                existingPr.updateStatus(ReviewStatus.NEW_CHANGES);
            }
        } else if (prState != PullRequestState.OPEN && currentStatus == ReviewStatus.IN_PROGRESS) {
            existingPr.updateStatus(ReviewStatus.STALE);
        }

        existingPr.updatePullRequestSnapshot(action, prState, nextHeadSha, nextBaseSha);
        pullRequestRepository.save(existingPr);
    }

    /**
     * 사용자 입력 패턴을 Glob 패턴으로 변환 (gitignore 스타일 지원)
     */
    private String convertUserPatternToGlob(String pattern) {
        pattern = pattern.trim();
        boolean isDirectory = pattern.endsWith("/");
        if (isDirectory) {
            pattern = pattern.substring(0, pattern.length() - 1);
        }

        boolean isRooted = pattern.startsWith("/");
        if (isRooted) {
            pattern = pattern.substring(1);
        }

        boolean hasSlash = pattern.contains("/");

        StringBuilder glob = new StringBuilder();

        if (!isRooted && !hasSlash) {
            glob.append("{**/,}");
        }

        glob.append(pattern);

        if (isDirectory) {
            glob.append("/**");
        } else {
            glob.append("{,/**}");
        }

        return glob.toString();
    }

    /**
     * 새 PR 생성
     */
    private void createNewPullRequest(Long repoId, String repoName, String loginId, Integer prNumber, String action,
            String title, PullRequestState prState, String headSha, String baseSha) {
        GithubAccount githubAccount = githubService.findByLoginIdOrThrow(loginId);

        PullRequest newPr = PullRequest.builder()
                .repositoryId(repoId)
                .repositoryName(repoName)
                .githubAccount(githubAccount)
                .prNumber(prNumber)
                .action(action)
                .title(title)
                .status(ReviewStatus.PENDING)
                .prState(prState)
                .headSha(headSha)
                .baseSha(baseSha)
                .build();

        pullRequestRepository.save(newPr);

        notificationService.createNotification(
                githubAccount,
                NotificationType.NEW_PR,
                newPr);

        if (Boolean.TRUE.equals(githubAccount.getAiSettings().getAutoReviewEnabled())) {
            try {
                String accessToken = tokenEncryptionService.decryptToken(githubAccount.getAccessToken());
                String model = githubAccount.getAiSettings().getOpenaiModel();
                review(loginId, repoName, prNumber, accessToken, model);
                log.info("Auto review triggered for PR #{} in {}/{}", prNumber, loginId, repoName);
            } catch (Exception e) {
                log.warn("Auto review failed for PR #{} in {}/{}: {}", prNumber, loginId, repoName, e.getMessage());
            }
        }
    }
}
