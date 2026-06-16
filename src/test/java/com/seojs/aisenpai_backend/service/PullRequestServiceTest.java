package com.seojs.aisenpai_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import com.seojs.aisenpai_backend.github.dto.WebhookPayloadDto;
import com.seojs.aisenpai_backend.github.dto.ChangedFileDto;
import com.seojs.aisenpai_backend.github.dto.GitTreeResponseDto;
import com.seojs.aisenpai_backend.github.dto.GithubReviewRequestDto;
import com.seojs.aisenpai_backend.github.dto.WebhookPayloadDto.PullRequestDto;
import com.seojs.aisenpai_backend.github.dto.WebhookPayloadDto.RefDto;
import com.seojs.aisenpai_backend.github.dto.WebhookPayloadDto.RepositoryDto;
import com.seojs.aisenpai_backend.github.dto.WebhookPayloadDto.UserDto;
import com.seojs.aisenpai_backend.github.dto.PullRequestInfoDto;
import com.seojs.aisenpai_backend.github.entity.GithubAccount;
import com.seojs.aisenpai_backend.github.entity.RepositoryAiSettings;
import com.seojs.aisenpai_backend.github.service.GithubService;
import com.seojs.aisenpai_backend.github.service.ReviewAnchorService;
import com.seojs.aisenpai_backend.github.service.RepositoryCacheService;
import com.seojs.aisenpai_backend.github.service.RepositoryAiSettingsService;
import com.seojs.aisenpai_backend.github.service.WebhookSecurityService;
import com.seojs.aisenpai_backend.github.service.TokenEncryptionService;
import com.seojs.aisenpai_backend.pullrequest.dto.PullRequestResponseDto;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewContextDto;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewRequestDto;
import com.seojs.aisenpai_backend.pullrequest.entity.PullRequest;
import com.seojs.aisenpai_backend.pullrequest.repository.PullRequestRepository;
import com.seojs.aisenpai_backend.pullrequest.service.ReviewFindingValidationService;
import com.seojs.aisenpai_backend.pullrequest.service.ReviewContextService;
import com.seojs.aisenpai_backend.pullrequest.service.PullRequestService;
import com.seojs.aisenpai_backend.notification.service.NotificationService;
import com.seojs.aisenpai_backend.notification.entity.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PullRequestServiceTest {

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private GithubService githubService;

    @Mock
    private WebhookSecurityService webhookSecurityService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private TokenEncryptionService tokenEncryptionService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private ReviewContextService reviewContextService;

    @Mock
    private RepositoryAiSettingsService repositoryAiSettingsService;

    @Mock
    private RepositoryCacheService repositoryCacheService;

    private PullRequestService pullRequestService;
    private ReviewFindingValidationService reviewFindingValidationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        reviewFindingValidationService = new ReviewFindingValidationService(new ReviewAnchorService());
        pullRequestService = new PullRequestService(pullRequestRepository, githubService,
                webhookSecurityService, objectMapper, eventPublisher, tokenEncryptionService,
                notificationService, reviewContextService, reviewFindingValidationService,
                repositoryAiSettingsService, repositoryCacheService);
    }

    private RepositoryAiSettings repositorySettings(Long repositoryId, String owner, String repo,
            GithubAccount postingAccount) {
        return RepositoryAiSettings.builder()
                .repositoryId(repositoryId)
                .owner(owner)
                .repositoryName(repo)
                .webhookSecret("secret")
                .webhookRegisteredBy(postingAccount)
                .postingAccount(postingAccount)
                .build();
    }

    @Test
    void failStuckInProgressReviews_MarksOldJobsFailedAndNotifiesPostingAccount() {
        // given
        Long repoId = 1L;
        GithubAccount prAccount = GithubAccount.builder().loginId("pr-author").build();
        GithubAccount postingAccount = GithubAccount.builder().loginId("posting-user").build();
        PullRequest pr = PullRequest.builder()
                .repositoryId(repoId)
                .repositoryName("repo")
                .githubAccount(prAccount)
                .prNumber(7)
                .action("opened")
                .status(PullRequest.ReviewStatus.IN_PROGRESS)
                .headSha("head")
                .baseSha("base")
                .build();
        pr.markReviewStarted("head", "run-1");

        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30);
        when(pullRequestRepository.findByStatusAndUpdatedAtBefore(PullRequest.ReviewStatus.IN_PROGRESS, cutoff))
                .thenReturn(List.of(pr));
        when(repositoryAiSettingsService.getRequired(repoId))
                .thenReturn(repositorySettings(repoId, "owner", "repo", postingAccount));

        // when
        int failedCount = pullRequestService.failStuckInProgressReviews(cutoff);

        // then
        assertEquals(1, failedCount);
        assertEquals(PullRequest.ReviewStatus.FAILED, pr.getStatus());
        assertEquals("head", pr.getReviewCompletedHeadSha());
        assertNull(pr.getReviewRunId());
        assertTrue(pr.getAiReview().contains("오래 응답하지 않아 실패 처리"));
        verify(notificationService).createNotification(
                postingAccount,
                NotificationType.REVIEW_FAILED,
                pr);
    }

    @Test
    void updateAiReview_TimedOutJobCompletion_DoesNotOverwriteFailedStatus() {
        // given
        Long repoId = 1L;
        Integer prNumber = 7;
        GithubAccount account = GithubAccount.builder().loginId("user").build();
        PullRequest pr = PullRequest.builder()
                .repositoryId(repoId)
                .repositoryName("repo")
                .githubAccount(account)
                .prNumber(prNumber)
                .action("opened")
                .status(PullRequest.ReviewStatus.IN_PROGRESS)
                .headSha("head")
                .baseSha("base")
                .build();
        pr.markReviewStarted("head", "run-1");
        pr.markReviewTimedOut("AI review failed: 리뷰 작업이 오래 응답하지 않아 실패 처리되었습니다. 다시 요청해 주세요.");

        when(pullRequestRepository.findWithLockByRepositoryIdAndPrNumber(repoId, prNumber))
                .thenReturn(Optional.of(pr));

        // when
        pullRequestService.updateAiReview(repoId, prNumber, "late success", PullRequest.ReviewStatus.COMPLETED,
                "head", "run-1");

        // then
        assertEquals(PullRequest.ReviewStatus.FAILED, pr.getStatus());
        assertTrue(pr.getAiReview().contains("오래 응답하지 않아 실패 처리"));
        verify(notificationService, never()).createNotification(
                any(GithubAccount.class),
                eq(NotificationType.REVIEW_COMPLETE),
                any(PullRequest.class));
    }

    @Test
    void getPullRequestList_Success() {
        // given
        Long repositoryId = 1L;
        String owner = "test-owner";
        String repo = "test-repo";
        String accessToken = "test-token";

        PullRequest pr1 = PullRequest.builder()
                .prNumber(1)
                .repositoryId(repositoryId)
                .title("PR 1")
                .status(PullRequest.ReviewStatus.PENDING)
                .build();

        PullRequest pr2 = PullRequest.builder()
                .prNumber(2)
                .repositoryId(repositoryId)
                .title("PR 2")
                .status(PullRequest.ReviewStatus.COMPLETED)
                .build();

        List<PullRequest> pullRequests = Arrays.asList(pr1, pr2);

        when(githubService.getRepositoryId(accessToken, owner, repo)).thenReturn(repositoryId);
        when(pullRequestRepository.findByRepositoryIdOrderByUpdatedAtDesc(repositoryId))
                .thenReturn(pullRequests);

        // when
        List<PullRequestResponseDto> result = pullRequestService.getPullRequestList(owner, repo, accessToken);

        // then
        assertEquals(2, result.size());
        assertEquals(1, result.get(0).getPrNumber());
        assertEquals(2, result.get(1).getPrNumber());
        verify(githubService).getRepositoryId(accessToken, owner, repo);
        verify(pullRequestRepository).findByRepositoryIdOrderByUpdatedAtDesc(repositoryId);
    }

    @Test
    void getPullRequestWithChanges_Success() {
        // given
        Long repositoryId = 1L;
        Integer prNumber = 123;
        String accessToken = "test-access-token";
        String owner = "test-owner";
        String repo = "test-repo";

        PullRequest pullRequest = PullRequest.builder()
                .repositoryId(repositoryId)
                .prNumber(prNumber)
                .repositoryName(repo)
                .build();

        when(githubService.getRepositoryId(accessToken, owner, repo)).thenReturn(repositoryId);
        when(pullRequestRepository.findByRepositoryIdAndPrNumber(repositoryId, prNumber))
                .thenReturn(Optional.of(pullRequest));

        when(githubService.getChangedFiles(accessToken, owner, repo, prNumber))
                .thenReturn(Collections.emptyList());

        // when
        pullRequestService.getPullRequestWithChanges(owner, repo, prNumber, accessToken);

        // then
        verify(githubService).getRepositoryId(accessToken, owner, repo);
        verify(pullRequestRepository).findByRepositoryIdAndPrNumber(repositoryId, prNumber);
        verify(githubService).getChangedFiles(accessToken, owner, repo, prNumber);
    }

    @Test
    void processAndSaveWebhook_NewPR_CreatesNotification() throws Exception {
        // given
        String payload = "{}";
        String signature = "sig";
        Long repoId = 100L;
        String repoName = "test-repo";
        String ownerLogin = "test-owner";
        Integer prNumber = 10;
        String title = "New Feature";

        WebhookPayloadDto dto = mock(WebhookPayloadDto.class);
        RepositoryDto repoDto = new RepositoryDto(repoId, repoName, ownerLogin,
                new UserDto(ownerLogin, 1, "url"));
        PullRequestDto prDto = new PullRequestDto(prNumber, title, "body", "open",
                new UserDto(ownerLogin, 1, "url"), "url", "diff");

        when(dto.getAction()).thenReturn("opened");
        when(dto.getRepository()).thenReturn(repoDto);
        when(dto.getPullRequest()).thenReturn(prDto);

        when(objectMapper.readValue(payload, WebhookPayloadDto.class)).thenReturn(dto);
        when(pullRequestRepository.findWithLockByRepositoryIdAndPrNumber(repoId, prNumber))
                .thenReturn(Optional.empty());

        GithubAccount account = GithubAccount.builder().loginId(ownerLogin).build();
        RepositoryAiSettings settings = repositorySettings(repoId, ownerLogin, repoName, account);
        when(repositoryAiSettingsService.getRequired(repoId)).thenReturn(settings);

        // when
        pullRequestService.processAndSaveWebhook(payload, signature);

        // then
        verify(notificationService).createNotification(
                any(GithubAccount.class),
                eq(NotificationType.NEW_PR),
                any(PullRequest.class));
        verify(pullRequestRepository).save(any(PullRequest.class));
    }

    @Test
    void updateAiReview_Completed_CreatesNotification() {
        // given
        Long repoId = 1L;
        Integer prNumber = 1;
        String aiReview = "Good job";

        GithubAccount account = GithubAccount.builder().loginId("user").build();
        when(repositoryAiSettingsService.getRequired(repoId))
                .thenReturn(repositorySettings(repoId, "user", "repo", account));
        PullRequest pr = PullRequest.builder()
                .repositoryId(repoId)
                .prNumber(prNumber)
                .repositoryName("repo")
                .githubAccount(account)
                .headSha("head-1")
                .build();

        when(pullRequestRepository.findWithLockByRepositoryIdAndPrNumber(repoId, prNumber))
                .thenReturn(Optional.of(pr));

        // when
        pullRequestService.updateAiReview(repoId, prNumber, aiReview, PullRequest.ReviewStatus.COMPLETED, "head-1");

        // then
        assertEquals(PullRequest.ReviewStatus.COMPLETED, pr.getStatus());
        assertEquals("head-1", pr.getReviewCompletedHeadSha());
        verify(notificationService).createNotification(
                eq(account),
                eq(NotificationType.REVIEW_COMPLETE),
                eq(pr));
    }

    @Test
    void updateAiReview_StaleHead_DoesNotCreateCompletedNotification() {
        // given
        Long repoId = 1L;
        Integer prNumber = 1;
        String staleReview = "Review for old commit";

        GithubAccount account = GithubAccount.builder().loginId("user").build();
        when(repositoryAiSettingsService.getRequired(repoId))
                .thenReturn(repositorySettings(repoId, "user", "repo", account));
        PullRequest pr = PullRequest.builder()
                .repositoryId(repoId)
                .prNumber(prNumber)
                .repositoryName("repo")
                .githubAccount(account)
                .headSha("new-head")
                .build();

        when(pullRequestRepository.findWithLockByRepositoryIdAndPrNumber(repoId, prNumber))
                .thenReturn(Optional.of(pr));

        // when
        pullRequestService.updateAiReview(repoId, prNumber, staleReview, PullRequest.ReviewStatus.COMPLETED,
                "old-head");

        // then
        assertEquals(PullRequest.ReviewStatus.STALE, pr.getStatus());
        assertEquals(staleReview, pr.getAiReview());
        verify(notificationService, never()).createNotification(
                any(GithubAccount.class),
                eq(NotificationType.REVIEW_COMPLETE),
                any(PullRequest.class));
    }

    @Test
    void updateAiReview_OldReviewCompletion_DoesNotOverwriteNewerInProgressReview() {
        // given
        Long repoId = 1L;
        Integer prNumber = 1;

        GithubAccount account = GithubAccount.builder().loginId("user").build();
        when(repositoryAiSettingsService.getRequired(repoId))
                .thenReturn(repositorySettings(repoId, "user", "repo", account));
        PullRequest pr = PullRequest.builder()
                .repositoryId(repoId)
                .prNumber(prNumber)
                .repositoryName("repo")
                .githubAccount(account)
                .headSha("new-head")
                .build();
        pr.markReviewStarted("new-head");

        when(pullRequestRepository.findWithLockByRepositoryIdAndPrNumber(repoId, prNumber))
                .thenReturn(Optional.of(pr));

        // when
        pullRequestService.updateAiReview(repoId, prNumber, "old result", PullRequest.ReviewStatus.COMPLETED,
                "old-head");

        // then
        assertEquals(PullRequest.ReviewStatus.IN_PROGRESS, pr.getStatus());
        assertNull(pr.getAiReview());
        verify(notificationService, never()).createNotification(
                any(GithubAccount.class),
                any(NotificationType.class),
                any(PullRequest.class));
    }

    @Test
    void processAndSaveWebhook_SynchronizeDuringReview_MarksStaleAndUpdatesHead() throws Exception {
        // given
        String payload = "{}";
        String signature = "sig";
        Long repoId = 100L;
        String repoName = "test-repo";
        String ownerLogin = "test-owner";
        Integer prNumber = 10;
        String title = "New Feature";

        WebhookPayloadDto dto = mock(WebhookPayloadDto.class);
        RepositoryDto repoDto = new RepositoryDto(repoId, repoName, ownerLogin,
                new UserDto(ownerLogin, 1, "url"));
        PullRequestDto prDto = new PullRequestDto(prNumber, title, "body", "open",
                new UserDto(ownerLogin, 1, "url"), "url", "diff",
                new RefDto("feature", "new-head"), new RefDto("main", "base-head"), false);

        GithubAccount account = GithubAccount.builder().loginId(ownerLogin).build();
        PullRequest existingPr = PullRequest.builder()
                .repositoryId(repoId)
                .repositoryName(repoName)
                .githubAccount(account)
                .prNumber(prNumber)
                .action("opened")
                .status(PullRequest.ReviewStatus.IN_PROGRESS)
                .headSha("old-head")
                .baseSha("base-head")
                .build();

        when(dto.getAction()).thenReturn("synchronize");
        when(dto.getRepository()).thenReturn(repoDto);
        when(dto.getPullRequest()).thenReturn(prDto);
        when(objectMapper.readValue(payload, WebhookPayloadDto.class)).thenReturn(dto);
        when(pullRequestRepository.findWithLockByRepositoryIdAndPrNumber(repoId, prNumber))
                .thenReturn(Optional.of(existingPr));
        when(repositoryAiSettingsService.getOrCreatePlaceholder(repoId, ownerLogin, repoName))
                .thenReturn(repositorySettings(repoId, ownerLogin, repoName, account));
        when(repositoryAiSettingsService.getRequired(repoId))
                .thenReturn(repositorySettings(repoId, ownerLogin, repoName, account));

        // when
        pullRequestService.processAndSaveWebhook(payload, signature);

        // then
        assertEquals(PullRequest.ReviewStatus.STALE, existingPr.getStatus());
        assertEquals("new-head", existingPr.getHeadSha());
        assertEquals(PullRequest.PullRequestState.OPEN, existingPr.getPrState());
        verify(pullRequestRepository).save(existingPr);
    }

    @Test
    void processAndSaveWebhook_ClosedMerged_UpdatesPrState() throws Exception {
        // given
        String payload = "{}";
        String signature = "sig";
        Long repoId = 100L;
        String repoName = "test-repo";
        String ownerLogin = "test-owner";
        Integer prNumber = 10;

        WebhookPayloadDto dto = mock(WebhookPayloadDto.class);
        RepositoryDto repoDto = new RepositoryDto(repoId, repoName, ownerLogin,
                new UserDto(ownerLogin, 1, "url"));
        PullRequestDto prDto = new PullRequestDto(prNumber, "title", "body", "closed",
                new UserDto(ownerLogin, 1, "url"), "url", "diff",
                new RefDto("feature", "head"), new RefDto("main", "base"), true);

        GithubAccount account = GithubAccount.builder().loginId(ownerLogin).build();
        PullRequest existingPr = PullRequest.builder()
                .repositoryId(repoId)
                .repositoryName(repoName)
                .githubAccount(account)
                .prNumber(prNumber)
                .action("opened")
                .status(PullRequest.ReviewStatus.COMPLETED)
                .headSha("head")
                .baseSha("base")
                .build();

        when(dto.getAction()).thenReturn("closed");
        when(dto.getRepository()).thenReturn(repoDto);
        when(dto.getPullRequest()).thenReturn(prDto);
        when(objectMapper.readValue(payload, WebhookPayloadDto.class)).thenReturn(dto);
        when(pullRequestRepository.findWithLockByRepositoryIdAndPrNumber(repoId, prNumber))
                .thenReturn(Optional.of(existingPr));
        when(repositoryAiSettingsService.getOrCreatePlaceholder(repoId, ownerLogin, repoName))
                .thenReturn(repositorySettings(repoId, ownerLogin, repoName, account));

        // when
        pullRequestService.processAndSaveWebhook(payload, signature);

        // then
        assertEquals(PullRequest.PullRequestState.MERGED, existingPr.getPrState());
        assertEquals(PullRequest.ReviewStatus.COMPLETED, existingPr.getStatus());
        verify(pullRequestRepository).save(existingPr);
        verify(repositoryCacheService).evictAllAfterCommit();
    }

    @Test
    void processAndSaveWebhook_ClosedDuringReview_MarksStale() throws Exception {
        // given
        String payload = "{}";
        String signature = "sig";
        Long repoId = 100L;
        String repoName = "test-repo";
        String ownerLogin = "test-owner";
        Integer prNumber = 10;

        WebhookPayloadDto dto = mock(WebhookPayloadDto.class);
        RepositoryDto repoDto = new RepositoryDto(repoId, repoName, ownerLogin,
                new UserDto(ownerLogin, 1, "url"));
        PullRequestDto prDto = new PullRequestDto(prNumber, "title", "body", "closed",
                new UserDto(ownerLogin, 1, "url"), "url", "diff",
                new RefDto("feature", "head"), new RefDto("main", "base"), false);

        GithubAccount account = GithubAccount.builder().loginId(ownerLogin).build();
        PullRequest existingPr = PullRequest.builder()
                .repositoryId(repoId)
                .repositoryName(repoName)
                .githubAccount(account)
                .prNumber(prNumber)
                .action("opened")
                .status(PullRequest.ReviewStatus.IN_PROGRESS)
                .headSha("head")
                .baseSha("base")
                .build();

        when(dto.getAction()).thenReturn("closed");
        when(dto.getRepository()).thenReturn(repoDto);
        when(dto.getPullRequest()).thenReturn(prDto);
        when(objectMapper.readValue(payload, WebhookPayloadDto.class)).thenReturn(dto);
        when(pullRequestRepository.findWithLockByRepositoryIdAndPrNumber(repoId, prNumber))
                .thenReturn(Optional.of(existingPr));
        when(repositoryAiSettingsService.getOrCreatePlaceholder(repoId, ownerLogin, repoName))
                .thenReturn(repositorySettings(repoId, ownerLogin, repoName, account));
        when(repositoryAiSettingsService.getRequired(repoId))
                .thenReturn(repositorySettings(repoId, ownerLogin, repoName, account));

        // when
        pullRequestService.processAndSaveWebhook(payload, signature);

        // then
        assertEquals(PullRequest.PullRequestState.CLOSED, existingPr.getPrState());
        assertEquals(PullRequest.ReviewStatus.STALE, existingPr.getStatus());
        verify(pullRequestRepository).save(existingPr);
    }

    @Test
    void updateAiReview_ClosedPr_DoesNotCompleteOrNotify() {
        // given
        Long repoId = 1L;
        Integer prNumber = 1;

        GithubAccount account = GithubAccount.builder().loginId("user").build();
        PullRequest pr = PullRequest.builder()
                .repositoryId(repoId)
                .prNumber(prNumber)
                .repositoryName("repo")
                .githubAccount(account)
                .headSha("head")
                .build();
        pr.updatePullRequestSnapshot("closed", PullRequest.PullRequestState.CLOSED, "head", "base");

        when(pullRequestRepository.findWithLockByRepositoryIdAndPrNumber(repoId, prNumber))
                .thenReturn(Optional.of(pr));

        // when
        pullRequestService.updateAiReview(repoId, prNumber, "late result", PullRequest.ReviewStatus.COMPLETED,
                "head");

        // then
        assertEquals(PullRequest.ReviewStatus.STALE, pr.getStatus());
        verify(notificationService, never()).createNotification(
                any(GithubAccount.class),
                any(NotificationType.class),
                any(PullRequest.class));
    }

    @Test
    void updateAiReview_AutoPost_PostsOnlyAnchoredCommentsAndDiscardsUnanchored() {
        // given
        Long repoId = 1L;
        Integer prNumber = 1;
        PullRequestService service = new PullRequestService(pullRequestRepository, githubService,
                webhookSecurityService, new ObjectMapper(), eventPublisher, tokenEncryptionService,
                notificationService, reviewContextService,
                new ReviewFindingValidationService(new ReviewAnchorService()), repositoryAiSettingsService,
                repositoryCacheService);

        GithubAccount account = GithubAccount.builder()
                .loginId("user")
                .accessToken("encrypted-token")
                .build();
        RepositoryAiSettings settings = repositorySettings(repoId, "user", "repo", account);
        settings.updateReviewSettings(null, null, null, false, true, "gpt-4o-mini");

        PullRequest pr = PullRequest.builder()
                .repositoryId(repoId)
                .prNumber(prNumber)
                .repositoryName("repo")
                .githubAccount(account)
                .headSha("head")
                .build();

        String aiReview = """
                {
                  "generalReview": "전체 요약입니다.",
                  "comments": [
                    {
                      "path": "src/App.java",
                      "codeSnippet": "private final String name;",
                      "body": "이 값은 생성자에서 검증하세요."
                    },
                    {
                      "path": "src/App.java",
                      "codeSnippet": "void run() {}",
                      "body": "기존 라인에 대한 의견입니다."
                    }
                  ]
                }
                """;
        String patch = """
                @@ -1,3 +1,4 @@
                 class App {
                +    private final String name;
                     void run() {}
                 }
                """;
        ChangedFileDto changedFile = new ChangedFileDto(
                "src/App.java", "modified", 1, 0, 1, 4, "sha", "blob", "raw", "contents", patch);

        when(pullRequestRepository.findWithLockByRepositoryIdAndPrNumber(repoId, prNumber))
                .thenReturn(Optional.of(pr));
        when(repositoryAiSettingsService.getRequired(repoId)).thenReturn(settings);
        when(tokenEncryptionService.decryptToken("encrypted-token")).thenReturn("access-token");
        when(githubService.getChangedFiles("access-token", "user", "repo", prNumber))
                .thenReturn(List.of(changedFile));

        // when
        service.updateAiReview(repoId, prNumber, aiReview, PullRequest.ReviewStatus.COMPLETED, "head");

        // then
        ArgumentCaptor<GithubReviewRequestDto> reviewCaptor = ArgumentCaptor.forClass(GithubReviewRequestDto.class);
        verify(githubService).postPRReview(eq("access-token"), eq("user"), eq("repo"), eq(prNumber),
                reviewCaptor.capture());
        assertEquals(1, reviewCaptor.getValue().getComments().size());
        assertEquals(2, reviewCaptor.getValue().getComments().get(0).getLine());
        assertTrue(pr.getAiReview().contains("이 값은 생성자에서 검증하세요."));
        assertFalse(pr.getAiReview().contains("기존 라인에 대한 의견입니다."));

        verify(githubService, never()).postPRComment(anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void updateAiReview_AutoPost_UnanchoredCommentsOnly_PostsGeneralReviewOnly() {
        // given
        Long repoId = 1L;
        Integer prNumber = 1;
        PullRequestService service = new PullRequestService(pullRequestRepository, githubService,
                webhookSecurityService, new ObjectMapper(), eventPublisher, tokenEncryptionService,
                notificationService, reviewContextService,
                new ReviewFindingValidationService(new ReviewAnchorService()), repositoryAiSettingsService,
                repositoryCacheService);

        GithubAccount account = GithubAccount.builder()
                .loginId("user")
                .accessToken("encrypted-token")
                .build();
        RepositoryAiSettings settings = repositorySettings(repoId, "user", "repo", account);
        settings.updateReviewSettings(null, null, null, false, true, "gpt-4o-mini");

        PullRequest pr = PullRequest.builder()
                .repositoryId(repoId)
                .prNumber(prNumber)
                .repositoryName("repo")
                .githubAccount(account)
                .headSha("head")
                .build();

        String aiReview = """
                {
                  "generalReview": "전체 요약입니다.",
                  "comments": [
                    {
                      "path": "src/App.java",
                      "codeSnippet": "System.out.println(\\"deleted\\");",
                      "body": "삭제된 라인에 대한 잘못된 의견입니다."
                    },
                    {
                      "path": "src/Wrong.java",
                      "codeSnippet": "private final String name;",
                      "body": "경로가 틀린 의견입니다."
                    }
                  ]
                }
                """;
        String patch = """
                @@ -1,4 +1,3 @@
                 class App {
                -    System.out.println("deleted");
                     void run() {}
                 }
                """;
        ChangedFileDto changedFile = new ChangedFileDto(
                "src/App.java", "modified", 0, 1, 1, 3, "sha", "blob", "raw", "contents", patch);

        when(pullRequestRepository.findWithLockByRepositoryIdAndPrNumber(repoId, prNumber))
                .thenReturn(Optional.of(pr));
        when(repositoryAiSettingsService.getRequired(repoId)).thenReturn(settings);
        when(tokenEncryptionService.decryptToken("encrypted-token")).thenReturn("access-token");
        when(githubService.getChangedFiles("access-token", "user", "repo", prNumber))
                .thenReturn(List.of(changedFile));

        // when
        service.updateAiReview(repoId, prNumber, aiReview, PullRequest.ReviewStatus.COMPLETED, "head");

        // then
        verify(githubService, never()).postPRReview(anyString(), anyString(), anyString(), anyInt(), any());
        ArgumentCaptor<String> commentCaptor = ArgumentCaptor.forClass(String.class);
        verify(githubService).postPRComment(eq("access-token"), eq("user"), eq("repo"), eq(prNumber),
                commentCaptor.capture());
        assertTrue(commentCaptor.getValue().contains("변경 파일 1개를 검토했으며, 이번 diff에서 명백한 문제는 발견되지 않았습니다."));
        assertFalse(commentCaptor.getValue().contains("추가 코멘트"));
        assertFalse(pr.getAiReview().contains("삭제된 라인에 대한 잘못된 의견입니다."));
        assertFalse(pr.getAiReview().contains("경로가 틀린 의견입니다."));
    }

    @Test
    void updateAiReview_AutoPost_ParseFailure_DoesNotPostUnverifiedRawReview() {
        // given
        Long repoId = 1L;
        Integer prNumber = 1;

        GithubAccount account = GithubAccount.builder()
                .loginId("user")
                .accessToken("encrypted-token")
                .build();
        RepositoryAiSettings settings = repositorySettings(repoId, "user", "repo", account);
        settings.updateReviewSettings(null, null, null, false, true, "gpt-4o-mini");

        PullRequest pr = PullRequest.builder()
                .repositoryId(repoId)
                .prNumber(prNumber)
                .repositoryName("repo")
                .githubAccount(account)
                .headSha("head")
                .build();

        when(pullRequestRepository.findWithLockByRepositoryIdAndPrNumber(repoId, prNumber))
                .thenReturn(Optional.of(pr));
        when(repositoryAiSettingsService.getRequired(repoId)).thenReturn(settings);

        // when
        pullRequestService.updateAiReview(repoId, prNumber, "not-json", PullRequest.ReviewStatus.COMPLETED, "head");

        // then
        verify(githubService, never()).postPRReview(anyString(), anyString(), anyString(), anyInt(), any());
        verify(githubService, never()).postPRComment(anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void updateAiReview_AutoPostDisabled_StoresEnrichedReviewForDisplay() {
        // given
        Long repoId = 1L;
        Integer prNumber = 1;
        PullRequestService service = new PullRequestService(pullRequestRepository, githubService,
                webhookSecurityService, new ObjectMapper(), eventPublisher, tokenEncryptionService,
                notificationService, reviewContextService,
                new ReviewFindingValidationService(new ReviewAnchorService()), repositoryAiSettingsService,
                repositoryCacheService);

        GithubAccount account = GithubAccount.builder()
                .loginId("user")
                .accessToken("encrypted-token")
                .build();
        RepositoryAiSettings settings = repositorySettings(repoId, "user", "repo", account);

        PullRequest pr = PullRequest.builder()
                .repositoryId(repoId)
                .prNumber(prNumber)
                .repositoryName("repo")
                .githubAccount(account)
                .headSha("head")
                .build();

        String aiReview = """
                {
                  "generalReview": "전체 요약입니다.",
                  "comments": [
                    {
                      "path": "src/App.java",
                      "codeSnippet": "private final String name;",
                      "body": "이 값은 생성자에서 검증하세요."
                    }
                  ]
                }
                """;
        String patch = """
                @@ -1,3 +1,4 @@
                 class App {
                +    private final String name;
                     void run() {}
                 }
                """;
        ChangedFileDto changedFile = new ChangedFileDto(
                "src/App.java", "modified", 1, 0, 1, 4, "sha", "blob", "raw", "contents", patch);

        when(pullRequestRepository.findWithLockByRepositoryIdAndPrNumber(repoId, prNumber))
                .thenReturn(Optional.of(pr));
        when(repositoryAiSettingsService.getRequired(repoId)).thenReturn(settings);
        when(tokenEncryptionService.decryptToken("encrypted-token")).thenReturn("access-token");
        when(githubService.getChangedFiles("access-token", "user", "repo", prNumber))
                .thenReturn(List.of(changedFile));

        // when
        service.updateAiReview(repoId, prNumber, aiReview, PullRequest.ReviewStatus.COMPLETED, "head");

        // then
        assertTrue(pr.getAiReview().contains("\"line\":2"));
        verify(githubService, never()).postPRReview(anyString(), anyString(), anyString(), anyInt(), any());
        verify(githubService, never()).postPRComment(anyString(), anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void updateAiReview_WithReviewContext_StoresEnrichedReviewWithContextFiles() {
        // given
        Long repoId = 1L;
        Integer prNumber = 1;
        PullRequestService service = new PullRequestService(pullRequestRepository, githubService,
                webhookSecurityService, new ObjectMapper(), eventPublisher, tokenEncryptionService,
                notificationService, reviewContextService,
                new ReviewFindingValidationService(new ReviewAnchorService()), repositoryAiSettingsService,
                repositoryCacheService);

        GithubAccount account = GithubAccount.builder()
                .loginId("user")
                .accessToken("encrypted-token")
                .build();
        RepositoryAiSettings settings = repositorySettings(repoId, "user", "repo", account);

        PullRequest pr = PullRequest.builder()
                .repositoryId(repoId)
                .prNumber(prNumber)
                .repositoryName("repo")
                .githubAccount(account)
                .headSha("head")
                .build();

        String aiReview = """
                {
                  "generalReview": "전체 요약입니다.",
                  "comments": []
                }
                """;

        ReviewContextDto reviewContext = ReviewContextDto.builder()
                .changedFiles(List.of(
                        ReviewContextDto.ChangedFileContextDto.builder()
                                .filename("src/App.java")
                                .contentFetchStatus(ReviewContextDto.ContentFetchStatus.FETCHED)
                                .build(),
                        ReviewContextDto.ChangedFileContextDto.builder()
                                .filename("src/Skip.java")
                                .contentFetchStatus(ReviewContextDto.ContentFetchStatus.SKIPPED)
                                .contentSkipReason("too large")
                                .build()
                ))
                .relatedFiles(List.of(
                        ReviewContextDto.RelatedFileContextDto.builder()
                                .path("src/Util.java")
                                .contentFetchStatus(ReviewContextDto.ContentFetchStatus.FETCHED)
                                .build()
                ))
                .build();

        when(pullRequestRepository.findWithLockByRepositoryIdAndPrNumber(repoId, prNumber))
                .thenReturn(Optional.of(pr));
        when(repositoryAiSettingsService.getRequired(repoId)).thenReturn(settings);
        when(tokenEncryptionService.decryptToken("encrypted-token")).thenReturn("access-token");
        when(githubService.getChangedFiles("access-token", "user", "repo", prNumber))
                .thenReturn(List.of());

        // when
        service.updateAiReview(repoId, prNumber, aiReview, PullRequest.ReviewStatus.COMPLETED, "head", null, reviewContext);

        // then
        assertTrue(pr.getAiReview().contains("\"path\":\"src/App.java\""));
        assertTrue(pr.getAiReview().contains("\"type\":\"changed\""));
        assertTrue(pr.getAiReview().contains("\"status\":\"diff + content\""));
        assertTrue(pr.getAiReview().contains("\"path\":\"src/Skip.java\""));
        assertTrue(pr.getAiReview().contains("\"status\":\"diff only (too large)\""));
        assertTrue(pr.getAiReview().contains("\"path\":\"src/Util.java\""));
        assertTrue(pr.getAiReview().contains("\"type\":\"related\""));
        assertTrue(pr.getAiReview().contains("\"status\":\"content read\""));
    }

    @Test
    void review_ClosedPr_DoesNotPublishReviewEvent() {
        // given
        Long repoId = 1L;
        Integer prNumber = 1;
        String owner = "user";
        String repo = "repo";
        String accessToken = "token";

        GithubAccount account = GithubAccount.builder().loginId(owner).build();

        PullRequest pr = PullRequest.builder()
                .repositoryId(repoId)
                .repositoryName(repo)
                .githubAccount(account)
                .prNumber(prNumber)
                .action("closed")
                .status(PullRequest.ReviewStatus.PENDING)
                .headSha("head")
                .baseSha("base")
                .build();
        pr.updatePullRequestSnapshot("closed", PullRequest.PullRequestState.CLOSED, "head", "base");

        PullRequestInfoDto prInfo = new PullRequestInfoDto();
        PullRequestInfoDto.PullRequestRefDto head = new PullRequestInfoDto.PullRequestRefDto();
        head.setSha("head");
        PullRequestInfoDto.PullRequestRefDto base = new PullRequestInfoDto.PullRequestRefDto();
        base.setSha("base");
        prInfo.setHead(head);
        prInfo.setBase(base);
        prInfo.setState("closed");

        when(githubService.getRepositoryId(accessToken, owner, repo)).thenReturn(repoId);
        when(pullRequestRepository.findWithLockByRepositoryIdAndPrNumber(repoId, prNumber))
                .thenReturn(Optional.of(pr));
        when(githubService.getPullRequestInfo(accessToken, owner, repo, prNumber)).thenReturn(prInfo);

        // when
        pullRequestService.review(owner, repo, prNumber, accessToken, "gpt-4o-mini");

        // then
        verify(githubService, never()).getChangedFiles(anyString(), anyString(), anyString(), anyInt());
        verify(eventPublisher, never()).publishEvent(any());
        assertEquals(PullRequest.PullRequestState.CLOSED, pr.getPrState());
    }

    @Test
    void review_DuplicateInProgressForSameHead_DoesNotPublishNewReviewEvent() {
        // given
        Long repoId = 1L;
        Integer prNumber = 1;
        String owner = "user";
        String repo = "repo";
        String accessToken = "token";
        String headSha = "head-1";

        GithubAccount account = GithubAccount.builder().loginId(owner).build();

        PullRequest pr = PullRequest.builder()
                .repositoryId(repoId)
                .repositoryName(repo)
                .githubAccount(account)
                .prNumber(prNumber)
                .action("opened")
                .status(PullRequest.ReviewStatus.IN_PROGRESS)
                .headSha(headSha)
                .baseSha("base-1")
                .build();
        pr.markReviewStarted(headSha);

        PullRequestInfoDto prInfo = new PullRequestInfoDto();
        PullRequestInfoDto.PullRequestRefDto head = new PullRequestInfoDto.PullRequestRefDto();
        head.setSha(headSha);
        PullRequestInfoDto.PullRequestRefDto base = new PullRequestInfoDto.PullRequestRefDto();
        base.setSha("base-1");
        prInfo.setHead(head);
        prInfo.setBase(base);
        prInfo.setState("open");

        when(githubService.getRepositoryId(accessToken, owner, repo)).thenReturn(repoId);
        when(pullRequestRepository.findWithLockByRepositoryIdAndPrNumber(repoId, prNumber))
                .thenReturn(Optional.of(pr));
        when(githubService.getPullRequestInfo(accessToken, owner, repo, prNumber)).thenReturn(prInfo);

        // when
        pullRequestService.review(owner, repo, prNumber, accessToken, "gpt-4o-mini");

        // then
        verify(githubService, never()).getChangedFiles(anyString(), anyString(), anyString(), anyInt());
        verify(eventPublisher, never()).publishEvent(any());
        assertEquals(PullRequest.ReviewStatus.IN_PROGRESS, pr.getStatus());
    }

    @Test
    void review_OpenPr_BuildsReviewContextAndPublishesReviewEvent() {
        // given
        Long repoId = 1L;
        Integer prNumber = 1;
        String owner = "user";
        String repo = "repo";
        String accessToken = "token";

        GithubAccount account = GithubAccount.builder().loginId(owner).build();
        RepositoryAiSettings settings = repositorySettings(repoId, owner, repo, account);
        settings.updateOpenAiKey("encrypted-key");

        PullRequest pr = PullRequest.builder()
                .repositoryId(repoId)
                .repositoryName(repo)
                .githubAccount(account)
                .prNumber(prNumber)
                .action("opened")
                .status(PullRequest.ReviewStatus.PENDING)
                .headSha("old-head")
                .baseSha("old-base")
                .build();

        PullRequestInfoDto prInfo = new PullRequestInfoDto();
        PullRequestInfoDto.PullRequestRefDto head = new PullRequestInfoDto.PullRequestRefDto();
        head.setSha("head");
        PullRequestInfoDto.PullRequestRefDto base = new PullRequestInfoDto.PullRequestRefDto();
        base.setSha("base");
        prInfo.setHead(head);
        prInfo.setBase(base);
        prInfo.setState("open");

        ChangedFileDto changedFile = new ChangedFileDto(
                "src/App.java", "modified", 1, 0, 1, 10, "sha", "blob", "raw", "contents",
                "@@ -1 +1 @@\n+class App {}");
        GitTreeResponseDto treeDto = new GitTreeResponseDto();
        ReviewContextDto reviewContext = ReviewContextDto.builder()
                .repositoryTree(ReviewContextDto.RepositoryTreeContextDto.builder()
                        .summary("- blob: src/App.java\n")
                        .truncated(false)
                        .build())
                .changedFiles(List.of())
                .relatedFiles(List.of())
                .build();

        when(githubService.getRepositoryId(accessToken, owner, repo)).thenReturn(repoId);
        when(pullRequestRepository.findWithLockByRepositoryIdAndPrNumber(repoId, prNumber))
                .thenReturn(Optional.of(pr));
        when(githubService.getPullRequestInfo(accessToken, owner, repo, prNumber)).thenReturn(prInfo);
        when(githubService.getChangedFiles(accessToken, owner, repo, prNumber)).thenReturn(List.of(changedFile));
        when(repositoryAiSettingsService.getConfiguredForReview(repoId)).thenReturn(settings);
        when(githubService.getRepositoryTree(accessToken, owner, repo, "base", true)).thenReturn(treeDto);
        when(reviewContextService.buildReviewContext(eq(accessToken), eq(owner), eq(repo), eq(prNumber), eq(prInfo),
                eq(List.of(changedFile)), eq(treeDto), anyList())).thenReturn(reviewContext);

        // when
        pullRequestService.review(owner, repo, prNumber, accessToken, "gpt-4o-mini");

        // then
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        ReviewRequestDto event = (ReviewRequestDto) eventCaptor.getValue();
        assertEquals(reviewContext, event.getReviewContext());
        assertEquals("- blob: src/App.java\n", event.getRepositoryTree());
        assertEquals("head", event.getReviewStartedHeadSha());
        assertNotNull(event.getReviewRunId());
        assertEquals(event.getReviewRunId(), pr.getReviewRunId());
        assertEquals(PullRequest.ReviewStatus.IN_PROGRESS, pr.getStatus());
    }
}
