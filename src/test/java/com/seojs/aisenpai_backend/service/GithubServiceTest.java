package com.seojs.aisenpai_backend.service;

import com.seojs.aisenpai_backend.ai.service.AiService;
import com.seojs.aisenpai_backend.exception.GithubAccountNotFoundEx;
import com.seojs.aisenpai_backend.github.dto.GithubContentResponseDto;
import com.seojs.aisenpai_backend.github.dto.GitRepositoryResponseDto;
import com.seojs.aisenpai_backend.github.dto.ReviewSettingsDto;
import com.seojs.aisenpai_backend.github.dto.WebhookResponseDto;
import com.seojs.aisenpai_backend.github.entity.DetailLevel;
import com.seojs.aisenpai_backend.github.entity.GithubAccount;
import com.seojs.aisenpai_backend.github.entity.ReviewFocus;
import com.seojs.aisenpai_backend.github.entity.ReviewTone;
import com.seojs.aisenpai_backend.github.repository.GithubAccountRepository;
import com.seojs.aisenpai_backend.github.service.GithubService;
import com.seojs.aisenpai_backend.github.service.TokenEncryptionService;
import com.seojs.aisenpai_backend.pullrequest.repository.PullRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@SuppressWarnings({ "rawtypes", "unchecked" })
class GithubServiceTest {

    @Mock
    private WebClient.Builder webClientBuilder;
    @Mock
    private WebClient webClient;
    @Mock
    private WebClient.RequestHeadersUriSpec requestHeadersUriSpec;
    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;
    @Mock
    private WebClient.ResponseSpec responseSpec;
    @Mock
    private WebClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock
    private WebClient.RequestBodySpec requestBodySpec;

    @Mock
    private GithubAccountRepository githubAccountRepository;

    @Mock
    private TokenEncryptionService tokenEncryptionService;

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private AiService aiService;

    private Executor githubApiExecutor = Runnable::run;

    private GithubService githubService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(webClientBuilder.build()).thenReturn(webClient);
        when(webClient.get()).thenReturn(requestHeadersUriSpec);
        when(webClient.post()).thenReturn(requestBodyUriSpec);
        when(webClient.delete()).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersUriSpec.uri(ArgumentMatchers.<Function<UriBuilder, URI>>any()))
                .thenReturn(requestHeadersSpec);
        when(requestHeadersUriSpec.uri(anyString(), any(Object[].class))).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.header(anyString(), anyString())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);

        when(requestBodyUriSpec.uri(anyString(), any(Object[].class))).thenReturn(requestBodySpec);
        when(requestBodySpec.header(anyString(), anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.contentType(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.bodyValue(any())).thenReturn(requestHeadersSpec);

        githubService = new GithubService(webClientBuilder, githubAccountRepository, tokenEncryptionService,
                        pullRequestRepository, aiService, githubApiExecutor);
        ReflectionTestUtils.setField(githubService, "webhookUrl", "http://test.com/webhook");
    }

    @Test
    void getRepositories_성공() {
        // given
        String accessToken = "test-token";
        List<GitRepositoryResponseDto> repos = Arrays.asList(
                new GitRepositoryResponseDto(),
                new GitRepositoryResponseDto());

        when(responseSpec.bodyToFlux(GitRepositoryResponseDto.class)).thenReturn(Flux.fromIterable(repos));

        // when
        List<GitRepositoryResponseDto> result = githubService.getRepositories(accessToken);

        // then
        assertEquals(2, result.size());
        verify(webClientBuilder).build();
    }

    @Test
    void isWebhook_웹훅존재시_True반환() {
        // given
        String accessToken = "test-token";
        String owner = "test-owner";
        String repo = "test-repo";
        String webhookUrl = "http://test.com/webhook";

        Map<String, String> config = new HashMap<>();
        config.put("url", webhookUrl);

        WebhookResponseDto dto = new WebhookResponseDto();
        dto.setConfig(config);

        when(responseSpec.bodyToFlux(WebhookResponseDto.class)).thenReturn(Flux.just(dto));

        // when
        boolean result = githubService.isWebhook(accessToken, owner, repo);

        // then
        assertTrue(result);
    }

    @Test
    void isWebhook_웹훅없을시_False반환() {
        // given
        String accessToken = "test-token";
        String owner = "test-owner";
        String repo = "test-repo";
        String differentUrl = "http://different.com/webhook";

        Map<String, String> config = new HashMap<>();
        config.put("url", differentUrl);

        WebhookResponseDto webhookDto = new WebhookResponseDto();
        webhookDto.setConfig(config);

        when(responseSpec.bodyToFlux(WebhookResponseDto.class)).thenReturn(Flux.just(webhookDto));

        // when
        boolean result = githubService.isWebhook(accessToken, owner, repo);

        // then
        assertFalse(result);
    }

    @Test
    void getRepositoriesWithWebhookStatus_성공() {
        // given
        String accessToken = "test-token";

        GitRepositoryResponseDto repo1 = new GitRepositoryResponseDto();
        ReflectionTestUtils.setField(repo1, "id", 1L);
        ReflectionTestUtils.setField(repo1, "name", "repo1");
        ReflectionTestUtils.setField(repo1, "owner", "owner");

        GitRepositoryResponseDto repo2 = new GitRepositoryResponseDto();
        ReflectionTestUtils.setField(repo2, "id", 2L);
        ReflectionTestUtils.setField(repo2, "name", "repo2");
        ReflectionTestUtils.setField(repo2, "owner", "owner");

        List<GitRepositoryResponseDto> repos = Arrays.asList(repo1, repo2);

        when(requestHeadersUriSpec.uri("https://api.github.com/user/repos"))
                .thenReturn(requestHeadersSpec);
        when(responseSpec.bodyToFlux(GitRepositoryResponseDto.class)).thenReturn(Flux.fromIterable(repos));
        when(responseSpec.bodyToFlux(WebhookResponseDto.class)).thenReturn(Flux.empty());

        WebClient.RequestHeadersUriSpec specificUriSpec = mock(WebClient.RequestHeadersUriSpec.class);
        WebClient.RequestHeadersSpec specificHeadersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec specificResponseSpec = mock(WebClient.ResponseSpec.class);

        when(webClient.get()).thenReturn(specificUriSpec);
        when(specificUriSpec.uri("https://api.github.com/user/repos")).thenReturn(specificHeadersSpec);
        when(specificHeadersSpec.header(anyString(), anyString())).thenReturn(specificHeadersSpec);
        when(specificHeadersSpec.retrieve()).thenReturn(specificResponseSpec);
        when(specificResponseSpec.bodyToFlux(GitRepositoryResponseDto.class))
                .thenReturn(Flux.fromIterable(repos));

        when(specificUriSpec.uri(matches(".*hooks.*"), any(), any())).thenReturn(specificHeadersSpec);
        when(specificResponseSpec.bodyToFlux(WebhookResponseDto.class)).thenReturn(Flux.empty());

        // when
        var result = githubService.getRepositoriesWithWebhookStatus(accessToken);

        // then
        assertEquals(2, result.size());
        assertFalse(result.get(0).isHasWebhook());
        assertFalse(result.get(1).isHasWebhook());
    }

    @Test
    void registerWebhook_성공() {
        // given
        String accessToken = "test-token";
        String owner = "test-owner";
        String repo = "test-repo";
        String webhookSecret = "test-webhook-secret";

        GithubAccount mockAccount = GithubAccount.builder()
                .loginId(owner)
                .accessToken("encrypted-token")
                .webhookSecret(webhookSecret)
                .build();

        when(githubAccountRepository.findByLoginId(owner)).thenReturn(Optional.of(mockAccount));

        when(responseSpec.bodyToFlux(WebhookResponseDto.class)).thenReturn(Flux.empty());

        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(Mono.empty());

        // when
        githubService.registerWebhook(accessToken, owner, repo);

        // then
        verify(webClient).post();
        verify(githubAccountRepository).findByLoginId(owner);
    }

    @Test
    void findAccessTokenByLoginId_성공() {
        // given
        String loginId = "test-owner";
        String encryptedToken = "encrypted-token";
        String decryptedToken = "test-access-token";

        GithubAccount account = GithubAccount.builder()
                .loginId(loginId)
                .accessToken(encryptedToken)
                .build();

        when(githubAccountRepository.findByLoginId(loginId))
                .thenReturn(Optional.of(account));
        when(tokenEncryptionService.decryptToken(encryptedToken))
                .thenReturn(decryptedToken);

        // when
        String result = githubService.findAccessTokenByLoginId(loginId);

        // then
        assertEquals(decryptedToken, result);
    }

    @Test
    void findAccessTokenByLoginId_계정없을시_예외발생() {
        // given
        String loginId = "non-existent-owner";

        when(githubAccountRepository.findByLoginId(loginId))
                .thenReturn(Optional.empty());

        // when & then
        GithubAccountNotFoundEx exception = assertThrows(GithubAccountNotFoundEx.class,
                () -> githubService.findAccessTokenByLoginId(loginId));

        assertEquals("No accessToken for loginId: " + loginId, exception.getMessage());
    }

    @Test
    void getFileContent_Base64Content_DecodesContent() {
        // given
        String encodedContent = Base64.getMimeEncoder().encodeToString("hello world".getBytes());
        GithubContentResponseDto contentResponse = new GithubContentResponseDto();
        contentResponse.setEncoding("base64");
        contentResponse.setContent(encodedContent);

        when(responseSpec.bodyToMono(GithubContentResponseDto.class)).thenReturn(Mono.just(contentResponse));

        // when
        String result = githubService.getFileContent("token", "owner", "repo", "src/App.ts", "head-sha");

        // then
        assertEquals("hello world", result);
        verify(requestHeadersUriSpec).uri(any(Function.class));
    }

    @Test
    void getReviewSettings_성공() {
        // given
        String loginId = "test-user";

        GithubAccount account = GithubAccount.builder()
                .loginId(loginId)
                .accessToken("test-token")
                .webhookSecret("test-secret")
                .build();

        when(githubAccountRepository.findByLoginId(loginId))
                .thenReturn(Optional.of(account));

        // when
        var result = githubService.getReviewSettings(loginId);

        // then
        assertEquals(ReviewTone.NEUTRAL, result.getTone());
        assertEquals(ReviewFocus.BOTH, result.getFocus());
        assertEquals(DetailLevel.STANDARD, result.getDetailLevel());

        verify(githubAccountRepository).findByLoginId(loginId);
    }

    @Test
    void getReviewSettings_계정없을시_예외발생() {
        // given
        String loginId = "non-existent-user";

        when(githubAccountRepository.findByLoginId(loginId))
                .thenReturn(Optional.empty());

        // when & then
        GithubAccountNotFoundEx exception = assertThrows(GithubAccountNotFoundEx.class,
                () -> githubService.getReviewSettings(loginId));

        assertEquals("GithubAccount not found for loginId: " + loginId, exception.getMessage());
    }

    @Test
    void updateReviewSettings_성공() {
        // given
        String loginId = "test-user";
        Long expectedId = 1L;

        GithubAccount account = GithubAccount.builder()
                .loginId(loginId)
                .accessToken("test-token")
                .webhookSecret("test-secret")
                .build();
        ReflectionTestUtils.setField(account, "id", expectedId);

        when(githubAccountRepository.findByLoginId(loginId))
                .thenReturn(Optional.of(account));

        ReviewSettingsDto dto = new ReviewSettingsDto(
                ReviewTone.FRIENDLY,
                ReviewFocus.PRAISE_ONLY,
                DetailLevel.DETAILED,
                true,
                false,
                "gpt-4o-mini");

        // when
        Long result = githubService.updateReviewSettings(loginId, dto);

        // then
        assertEquals(expectedId, result);
        assertEquals(ReviewTone.FRIENDLY, account.getAiSettings().getReviewTone());
    }

    @Test
    void updateReviewSettings_계정없을시_예외발생() {
        // given
        String loginId = "non-existent-user";
        ReviewSettingsDto dto = new ReviewSettingsDto(
                ReviewTone.STRICT, ReviewFocus.IMPROVEMENT_ONLY, DetailLevel.CONCISE, false,
                false,
                "gpt-4o-mini");

        when(githubAccountRepository.findByLoginId(loginId))
                .thenReturn(Optional.empty());

        // when & then
        GithubAccountNotFoundEx exception = assertThrows(GithubAccountNotFoundEx.class,
                () -> githubService.updateReviewSettings(loginId, dto));

        assertEquals("GithubAccount not found for loginId: " + loginId, exception.getMessage());
    }

    @Test
    void validateOpenAiKey_성공() {
        // given
        String validKey = "sk-valid-key";
        when(aiService.validateApiKey(validKey)).thenReturn(true);

        // when
        boolean result = githubService.validateOpenAiKey(validKey);

        // then
        assertTrue(result);
        verify(aiService).validateApiKey(validKey);
    }

    @Test
    void validateOpenAiKey_실패() {
        // given
        String invalidKey = "invalid-key";
        when(aiService.validateApiKey(invalidKey)).thenReturn(false);

        // when
        boolean result = githubService.validateOpenAiKey(invalidKey);

        // then
        assertFalse(result);
        verify(aiService).validateApiKey(invalidKey);
    }
}
