package com.seojs.aisenpai_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import com.seojs.aisenpai_backend.github.dto.WebhookPayloadDto;
import com.seojs.aisenpai_backend.github.dto.WebhookPayloadDto.PullRequestDto;
import com.seojs.aisenpai_backend.github.dto.WebhookPayloadDto.RepositoryDto;
import com.seojs.aisenpai_backend.github.dto.WebhookPayloadDto.UserDto;
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
        PullRequest pr = PullRequest.builder()
                .repositoryId(repoId)
                .prNumber(prNumber)
                .repositoryName("repo")
                .githubAccount(account)
                .build();

        when(pullRequestRepository.findByRepositoryIdAndPrNumber(repoId, prNumber))
                .thenReturn(Optional.of(pr));

        // when
        pullRequestService.updateAiReview(repoId, prNumber, aiReview, PullRequest.ReviewStatus.COMPLETED);

        // then
        verify(notificationService).createNotification(
                eq(account),
                eq(NotificationType.REVIEW_COMPLETE),
                eq(pr));
    }
}
