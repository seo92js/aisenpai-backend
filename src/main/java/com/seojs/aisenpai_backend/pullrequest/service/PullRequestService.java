
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
import com.seojs.aisenpai_backend.github.entity.RepositoryAiSettings;
import com.seojs.aisenpai_backend.github.service.GithubService;
import com.seojs.aisenpai_backend.github.service.RepositoryAiSettingsService;
import com.seojs.aisenpai_backend.github.service.TokenEncryptionService;
import com.seojs.aisenpai_backend.github.service.WebhookSecurityService;
import com.seojs.aisenpai_backend.notification.entity.NotificationType;
import com.seojs.aisenpai_backend.notification.service.NotificationService;
import com.seojs.aisenpai_backend.pullrequest.dto.PullRequestResponseDto;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewContextDto;
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
    private final ReviewContextService reviewContextService;
    private final ReviewFindingValidationService reviewFindingValidationService;
    private final RepositoryAiSettingsService repositoryAiSettingsService;

    private record ReviewProcessingResult(
            String accessToken,
            AiReviewResponseDto aiResponse,
            List<ReviewCommentDto> enrichedComments) {
    }

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

        RepositoryAiSettings settings = repositoryAiSettingsService.getConfiguredForReview(repositoryId);
        String reviewModel = hasText(model) ? model : settings.getOpenaiModel();
        if (!owner.equals(settings.getOwner()) || !repo.equals(settings.getRepositoryName())) {
            settings.updateRepository(owner, repo);
        }

        if (settings.getOpenAiKey() == null || settings.getOpenAiKey().isEmpty()) {
            throw new OpenAiKeyNotSetEx("OpenAI API key is not set for this repository.");
        }

        List<String> ignorePatterns = settings.getIgnorePatternsAsList();
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
        String systemPrompt = settings.buildSystemPrompt();
        String encryptedOpenAiKey = settings.getOpenAiKey();

        GitTreeResponseDto treeDto = null;
        try {
            String targetTreeSha = "HEAD";
            if (prInfo != null && prInfo.getBase() != null && prInfo.getBase().getSha() != null) {
                targetTreeSha = prInfo.getBase().getSha();
                log.info("Found base branch SHA for PR #{}: {}", prNumber, targetTreeSha);
            }

            treeDto = githubService.getRepositoryTree(accessToken, owner, repo, targetTreeSha, true);
            log.info("Successfully fetched repository tree context for PR #{}", prNumber);
        } catch (Exception e) {
            log.warn(
                    "Failed to fetch repository tree context for PR #{}. Proceeding without structural context. Error: {}",
                    prNumber, e.getMessage());
        }

        ReviewContextDto reviewContext = reviewContextService.buildReviewContext(accessToken, owner, repo, prNumber,
                prInfo, filteredFiles, treeDto, ignorePatterns);
        ReviewContextDto.BudgetDto budget = reviewContext.getBudget();
        log.info("Review request context prepared. repositoryId={}, pr={}, model={}, detailLevel={}, changedFiles={}, "
                        + "relatedFiles={}, usedContentChars={}, usedContextChars={}",
                repositoryId, prNumber, reviewModel, settings.getDetailLevel(),
                reviewContext.getChangedFiles() != null ? reviewContext.getChangedFiles().size() : 0,
                reviewContext.getRelatedFiles() != null ? reviewContext.getRelatedFiles().size() : 0,
                budget != null ? budget.getUsedContentChars() : 0,
                budget != null ? budget.getUsedContextChars() : 0);
        String repositoryTreeString = reviewContext.getRepositoryTree() != null
                ? reviewContext.getRepositoryTree().getSummary()
                : null;

        eventPublisher.publishEvent(
                new ReviewRequestDto(repositoryId, prNumber, filteredFiles, reviewModel, systemPrompt, encryptedOpenAiKey,
                        repositoryTreeString, currentHeadSha, reviewContext));
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

        RepositoryAiSettings settings = repositoryAiSettingsService.getRequired(repositoryId);
        GithubAccount account = settings.getPostingAccount() != null ? settings.getPostingAccount() : pr.getGithubAccount();

        if (status == ReviewStatus.COMPLETED) {
            ReviewProcessingResult reviewProcessingResult = prepareReviewForDisplay(settings, pr, aiReview);

            notificationService.createNotification(
                    account,
                    NotificationType.REVIEW_COMPLETE,
                    pr);

            // GitHub PR에 댓글 자동 게시
            if (Boolean.TRUE.equals(settings.getAutoPostToGithub())) {
                processAndPostReview(settings, pr, aiReview, reviewProcessingResult);
            }
        } else if (status == ReviewStatus.FAILED) {
            notificationService.createNotification(
                    account,
                    NotificationType.REVIEW_FAILED,
                    pr);
        }
    }

    private ReviewProcessingResult prepareReviewForDisplay(RepositoryAiSettings settings, PullRequest pr, String aiReview) {
        try {
            String sanitizedAiReview = sanitizeAiReview(aiReview);
            AiReviewResponseDto aiResponse = objectMapper.readValue(sanitizedAiReview, AiReviewResponseDto.class);
            GithubAccount postingAccount = settings.getPostingAccount();
            if (postingAccount == null) {
                log.warn("Repository settings for {}/{} have no posting account", settings.getOwner(),
                        settings.getRepositoryName());
                return null;
            }
            String accessToken = tokenEncryptionService.decryptToken(postingAccount.getAccessToken());
            List<ChangedFileDto> changedFiles = githubService.getChangedFiles(accessToken,
                    settings.getOwner(), pr.getRepositoryName(), pr.getPrNumber());
            ReviewFindingValidationService.ValidationResult validationResult = reviewFindingValidationService.validate(
                    aiResponse, changedFiles);
            saveEnrichedReviewToDb(pr, validationResult.aiResponse());
            return new ReviewProcessingResult(accessToken, validationResult.aiResponse(),
                    validationResult.anchoredComments());
        } catch (Exception e) {
            log.warn("Failed to normalize AI review for PR #{}: {}", pr.getPrNumber(), e.getMessage());
            return null;
        }
    }

    /**
     * AI 리뷰를 처리하고 GitHub에 게시 (파싱 및 분기 처리)
     */
    private void processAndPostReview(RepositoryAiSettings settings, PullRequest pr, String aiReview,
            ReviewProcessingResult reviewProcessingResult) {
        try {
            if (reviewProcessingResult == null) {
                log.warn("Skipping GitHub auto post for PR #{} because AI review could not be validated",
                        pr.getPrNumber());
                return;
            }

            if (!reviewProcessingResult.enrichedComments().isEmpty()) {
                postCommentsToGitHub(reviewProcessingResult.accessToken(), settings, pr,
                        reviewProcessingResult.aiResponse(), reviewProcessingResult.enrichedComments());
            } else {
                postGeneralComment(reviewProcessingResult.accessToken(), settings, pr,
                        defaultGeneralReview(reviewProcessingResult.aiResponse().getGeneralReview()));
            }
        } catch (Exception e) {
            log.warn("Failed to process and post review to GitHub PR #{}: {}", pr.getPrNumber(), e.getMessage());
        }
    }

    private boolean isValidReviewComment(ReviewCommentDto comment) {
        return comment != null
                && hasText(comment.getPath())
                && hasText(comment.getCodeSnippet())
                && hasText(comment.getBody());
    }

    private void saveEnrichedReviewToDb(PullRequest pr, AiReviewResponseDto validatedResponse) throws Exception {
        String updatedJson = objectMapper.writeValueAsString(validatedResponse);
        pr.updateAiReview(updatedJson);
    }

    private void postCommentsToGitHub(String accessToken, RepositoryAiSettings settings, PullRequest pr,
            AiReviewResponseDto aiResponse, List<ReviewCommentDto> enrichedComments) {
        List<GithubApiCommentDto> commentsToPost = enrichedComments.stream()
                .filter(c -> c.getLine() != null && isValidReviewComment(c))
                .map(c -> GithubApiCommentDto.builder()
                        .path(c.getPath())
                        .line(c.getLine())
                        .side("RIGHT")
                        .body(formatReviewForGithub(c.getBody()))
                        .build())
                .toList();

        StringBuilder manualFallback = new StringBuilder();
        boolean inlinePostFailed = false;

        if (!commentsToPost.isEmpty()) {
            GithubReviewRequestDto reviewRequest = GithubReviewRequestDto.builder()
                    .body(formatReviewForGithub(defaultGeneralReview(aiResponse.getGeneralReview())))
                    .event("COMMENT")
                    .comments(commentsToPost)
                    .build();

            try {
                githubService.postPRReview(accessToken, settings.getOwner(), pr.getRepositoryName(),
                        pr.getPrNumber(), reviewRequest);
            } catch (Exception e) {
                log.warn("Failed to post inline review: {}", e.getMessage());
                inlinePostFailed = true;
            }
        }

        List<ReviewCommentDto> fallbackComments = inlinePostFailed
                ? enrichedComments.stream()
                        .filter(c -> c.getLine() != null && isValidReviewComment(c))
                        .toList()
                : List.of();

        if (inlinePostFailed) {
            manualFallback.append("### 인라인 리뷰 게시 실패\n");
            manualFallback.append("GitHub 인라인 리뷰 API 호출에 실패해 코멘트를 일반 댓글로 남깁니다.\n\n");
        }
        appendFallbackComments(manualFallback, fallbackComments);

        if (manualFallback.length() > 0) {
            boolean includeGeneralReview = inlinePostFailed || commentsToPost.isEmpty();
            String fallbackBody = (includeGeneralReview ? defaultGeneralReview(aiResponse.getGeneralReview()) + "\n\n"
                    : "")
                    + "### 추가 코멘트\n"
                    + manualFallback;
            postGeneralComment(accessToken, settings, pr, fallbackBody);
        } else if (commentsToPost.isEmpty()) {
            postGeneralComment(accessToken, settings, pr, defaultGeneralReview(aiResponse.getGeneralReview()));
        }
    }

    private void appendFallbackComments(StringBuilder fallback, List<ReviewCommentDto> comments) {
        comments.stream()
                .filter(this::isValidReviewComment)
                .forEach(c -> fallback.append(String.format("- **%s**: %s\n  - 스니펫: `%s`\n\n",
                        c.getPath(), c.getBody(), c.getCodeSnippet())));
    }

    private String defaultGeneralReview(String generalReview) {
        return hasText(generalReview) ? generalReview : "검토 결과 요약이 없습니다.";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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
    private void postGeneralComment(String accessToken, RepositoryAiSettings settings, PullRequest pr, String body) {
        String formattedReview = formatReviewForGithub(body);
        githubService.postPRComment(accessToken, settings.getOwner(), pr.getRepositoryName(), pr.getPrNumber(),
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
        String owner = webhookPayload.getRepository().getOwner().getLogin();
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
            createNewPullRequest(repoId, repoName, owner, prNumber, action, title, prState, headSha, baseSha);
        }

        repositoryAiSettingsService.getOrCreatePlaceholder(repoId, owner, repoName);
        repositoryAiSettingsService.getRequired(repoId).getPostingAccount();
        String cacheLogin = repositoryAiSettingsService.getRequired(repoId).getPostingAccountLogin();
        evictRepositoryCache(cacheLogin != null ? cacheLogin : owner);
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
        RepositoryAiSettings settings = repositoryAiSettingsService.getRequired(repoId);
        GithubAccount githubAccount = settings.getPostingAccount();
        if (githubAccount == null) {
            throw new WebhookProcessingEx("Repository posting account is not configured");
        }

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

        if (Boolean.TRUE.equals(settings.getAutoReviewEnabled())) {
            try {
                String accessToken = tokenEncryptionService.decryptToken(githubAccount.getAccessToken());
                String model = settings.getOpenaiModel();
                review(settings.getOwner(), repoName, prNumber, accessToken, model);
                log.info("Auto review triggered for PR #{} in {}/{}", prNumber, settings.getOwner(), repoName);
            } catch (Exception e) {
                log.warn("Auto review failed for PR #{} in {}/{}: {}", prNumber, settings.getOwner(), repoName,
                        e.getMessage());
            }
        }
    }
}
