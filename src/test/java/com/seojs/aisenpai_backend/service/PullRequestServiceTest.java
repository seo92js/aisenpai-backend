package com.seojs.aisenpai_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import com.seojs.aisenpai_backend.github.dto.WebhookPayloadDto;
import com.seojs.aisenpai_backend.github.dto.WebhookPayloadDto.PullRequestDto;
import com.seojs.aisenpai_backend.github.dto.WebhookPayloadDto.RefDto;
import com.seojs.aisenpai_backend.github.dto.WebhookPayloadDto.RepositoryDto;
import com.seojs.aisenpai_backend.github.dto.WebhookPayloadDto.UserDto;
import com.seojs.aisenpai_backend.github.dto.PullRequestInfoDto;
import com.seojs.aisenpai_backend.github.entity.GithubAccount;
import com.seojs.aisenpai_backend.github.service.GithubService;
import com.seojs.aisenpai_backend.github.service.ReviewAnchorService;
import com.seojs.aisenpai_backend.github.service.WebhookSecurityService;
import com.seojs.aisenpai_backend.github.service.TokenEncryptionService;
import com.seojs.aisenpai_backend.pullrequest.dto.PullRequestResponseDto;
import com.seojs.aisenpai_backend.pullrequest.entity.PullRequest;
import com.seojs.aisenpai_backend.pullrequest.repository.PullRequestRepository;
import com.seojs.aisenpai_backend.pullrequest.service.PullRequestService;
import com.seojs.aisenpai_backend.notification.service.NotificationService;
import com.seojs.aisenpai_backend.notification.entity.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

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
    private ReviewAnchorService reviewAnchorService;

    private PullRequestService pullRequestService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        pullRequestService = new PullRequestService(pullRequestRepository, githubService,
                webhookSecurityService, objectMapper, eventPublisher, tokenEncryptionService,
                notificationService, reviewAnchorService);
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
        account.initializeAiSettings();
        when(githubService.findByLoginIdOrThrow(ownerLogin)).thenReturn(account);

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
        account.initializeAiSettings();
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
        account.initializeAiSettings();
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
        account.initializeAiSettings();
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
        when(githubService.findAccessTokenByLoginId(ownerLogin)).thenReturn("access-token");

        // when
        pullRequestService.processAndSaveWebhook(payload, signature);

        // then
        assertEquals(PullRequest.PullRequestState.MERGED, existingPr.getPrState());
        assertEquals(PullRequest.ReviewStatus.COMPLETED, existingPr.getStatus());
        verify(pullRequestRepository).save(existingPr);
        verify(githubService).evictRepositoryCache("access-token");
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
        account.initializeAiSettings();
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
    void review_ClosedPr_DoesNotPublishReviewEvent() {
        // given
        Long repoId = 1L;
        Integer prNumber = 1;
        String owner = "user";
        String repo = "repo";
        String accessToken = "token";

        GithubAccount account = GithubAccount.builder().loginId(owner).build();
        account.initializeAiSettings();
        account.getAiSettings().updateOpenAiKey("encrypted-key");

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
        account.initializeAiSettings();
        account.getAiSettings().updateOpenAiKey("encrypted-key");

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
}
