package com.seojs.aisenpai_backend.github.service;

import com.seojs.aisenpai_backend.ai.service.AiService;
import com.seojs.aisenpai_backend.exception.GitHubApiEx;
import com.seojs.aisenpai_backend.exception.GithubRateLimitEx;
import com.seojs.aisenpai_backend.exception.GithubAccountNotFoundEx;
import com.seojs.aisenpai_backend.exception.WebhookRegistrationEx;
import com.seojs.aisenpai_backend.github.dto.*;
import com.seojs.aisenpai_backend.github.entity.AiReviewSettings;
import com.seojs.aisenpai_backend.github.entity.GithubAccount;
import com.seojs.aisenpai_backend.github.repository.GithubAccountRepository;
import com.seojs.aisenpai_backend.pullrequest.repository.PullRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@Service
public class GithubService {
    private static final int GITHUB_PAGE_SIZE = 100;

    private final WebClient.Builder webClientBuilder;
    private final GithubAccountRepository githubAccountRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final PullRequestRepository pullRequestRepository;
    private final AiService aiService;
    @Qualifier("githubApiExecutor")
    private final Executor githubApiExecutor;

    @Value("${github.webhook.url}")
    private String webhookUrl;

    /**
     * 사용자의 GitHub 저장소 목록을 조회
     */
    public List<GitRepositoryResponseDto> getRepositories(String accessToken) {
        return webClientBuilder.build()
                .get()
                .uri("https://api.github.com/user/repos")
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .bodyToFlux(GitRepositoryResponseDto.class)
                .collectList()
                .block();
    }

    /**
     * 특정 저장소의 ID를 GitHub API로 조회
     */
    public Long getRepositoryId(String accessToken, String owner, String repo) {
        try {
            GitRepositoryResponseDto repository = webClientBuilder.build()
                    .get()
                    .uri("https://api.github.com/repos/{owner}/{repo}", owner, repo)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .bodyToMono(GitRepositoryResponseDto.class)
                    .block();

            return repository != null ? repository.getId() : null;
        } catch (Exception e) {
            throw new GitHubApiEx("Failed to get repository: " + owner + "/" + repo, e);
        }
    }

    /**
     * 특정 저장소에 지정된 webhook이 등록되어 있는지 확인
     */
    public boolean isWebhook(String accessToken, String owner, String repo) {
        List<WebhookResponseDto> webhooks = webClientBuilder.build()
                .get()
                .uri("https://api.github.com/repos/{owner}/{repo}/hooks", owner, repo)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .bodyToFlux(WebhookResponseDto.class)
                .collectList()
                .block();

        if (webhooks != null) {
            return webhooks.stream()
                    .anyMatch(hook -> {
                        Object configUrl = hook.getConfig().get("url");
                        return configUrl != null && configUrl.toString().equals(webhookUrl);
                    });
        }

        return false;
    }

    /**
     * 사용자의 모든 저장소와 각 저장소의 webhook 등록 상태를 조회 (병렬 처리)
     */
    @Cacheable(value = "repositories", key = "#accessToken")
    public List<GitRepositoryWithWebhookResponseDto> getRepositoriesWithWebhookStatus(String accessToken) {
        List<GitRepositoryResponseDto> repositories = getRepositories(accessToken);

        List<CompletableFuture<GitRepositoryWithWebhookResponseDto>> futures = repositories.stream()
                .map(repo -> CompletableFuture.supplyAsync(() -> {
                    boolean hasWebhook = isWebhook(accessToken, repo.getOwner(), repo.getName());
                    boolean existsOpenPr = pullRequestRepository.existsOpenPrByRepositoryId(repo.getId());

                    return GitRepositoryWithWebhookResponseDto.builder()
                            .repository(repo)
                            .hasWebhook(hasWebhook)
                            .existsOpenPullRequest(existsOpenPr)
                            .build();
                }, githubApiExecutor))
                .toList();

        return futures.stream()
                .map(CompletableFuture::join)
                .sorted((r1, r2) -> {
                    // Open PR이 있는 경우 최우선
                    if (r1.isExistsOpenPullRequest() != r2.isExistsOpenPullRequest()) {
                        return r1.isExistsOpenPullRequest() ? -1 : 1;
                    }
                    // 최근 수정일 순 (내림차순)
                    String t1 = r1.getRepository().getUpdatedAt();
                    String t2 = r2.getRepository().getUpdatedAt();

                    if (t1 == null && t2 == null)
                        return 0;
                    if (t1 == null)
                        return 1;
                    if (t2 == null)
                        return -1;

                    return t2.compareTo(t1);
                })
                .collect(Collectors.toList());
    }

    /**
     * 로그인 아이디로 accessToken 조회
     */
    @Transactional(readOnly = true)
    public String findAccessTokenByLoginId(String loginId) {
        return githubAccountRepository.findByLoginId(loginId)
                .map(account -> tokenEncryptionService.decryptToken(account.getAccessToken()))
                .orElseThrow(() -> new GithubAccountNotFoundEx("No accessToken for loginId: " + loginId));
    }

    /**
     * 로그인 아이디로 GithubAccount 조회 - 존재하지 않으면 예외 발생
     */
    @Transactional(readOnly = true)
    public GithubAccount findByLoginIdOrThrow(String loginId) {
        return githubAccountRepository.findByLoginId(loginId)
                .orElseThrow(() -> new GithubAccountNotFoundEx("GithubAccount not found for loginId: " + loginId));
    }

    /**
     * 리뷰 설정 조회
     */
    @Transactional(readOnly = true)
    public ReviewSettingsDto getReviewSettings(String loginId) {
        GithubAccount account = findByLoginIdOrThrow(loginId);
        AiReviewSettings settings = account.getAiSettings();
        return new ReviewSettingsDto(
                settings.getReviewTone(),
                settings.getReviewFocus(),
                settings.getDetailLevel(),
                settings.getAutoReviewEnabled(),
                settings.getAutoPostToGithub(),
                settings.getOpenaiModel());
    }

    /**
     * 무시 패턴 조회
     */
    @Transactional(readOnly = true)
    public List<String> getIgnorePatterns(String loginId) {
        GithubAccount account = findByLoginIdOrThrow(loginId);
        return account.getAiSettings().getIgnorePatternsAsList();
    }

    /**
     * openai api key 조회
     */
    @Transactional(readOnly = true)
    public String getOpenAiKey(String loginId) {
        GithubAccount account = findByLoginIdOrThrow(loginId);
        String encryptedKey = account.getAiSettings().getOpenAiKey();
        if (encryptedKey == null || encryptedKey.isBlank()) {
            return null;
        }
        return tokenEncryptionService.decryptToken(encryptedKey);
    }

    /**
     * 마스킹된 OpenAI Key 반환 (API 응답용)
     */
    @Transactional(readOnly = true)
    public String getMaskedOpenAiKey(String loginId) {
        String decryptedKey = getOpenAiKey(loginId);
        if (decryptedKey == null || decryptedKey.length() < 10) {
            return null;
        }
        return decryptedKey.substring(0, 10) + "...****";
    }

    /**
     * 리뷰 설정 업데이트
     */
    @Transactional
    public Long updateReviewSettings(String loginId, ReviewSettingsDto dto) {
        GithubAccount account = findByLoginIdOrThrow(loginId);
        account.getAiSettings().updateReviewSettings(dto.getTone(), dto.getFocus(), dto.getDetailLevel(),
                dto.getAutoReviewEnabled(), dto.getAutoPostToGithub(),
                dto.getOpenaiModel());
        return account.getId();
    }

    /**
     * 무시 패턴 업데이트
     */
    @Transactional
    public Long updateIgnorePatterns(String loginId, List<String> patterns) {
        GithubAccount account = findByLoginIdOrThrow(loginId);
        String patternsString = patterns == null ? "" : String.join(",", patterns);
        account.getAiSettings().updateIgnorePatterns(patternsString);
        return account.getId();
    }

    /**
     * openai api key 업데이트
     */
    @Transactional
    public Long updateOpenAiKey(String loginId, String openAiKey) {
        GithubAccount account = findByLoginIdOrThrow(loginId);
        if (openAiKey == null || openAiKey.isBlank()) {
            account.getAiSettings().updateOpenAiKey(null);
        } else {
            String encryptedKey = tokenEncryptionService.encryptToken(openAiKey);
            account.getAiSettings().updateOpenAiKey(encryptedKey);
        }
        return account.getId();
    }

    /**
     * OpenAI API 키 유효성 검증
     */
    public boolean validateOpenAiKey(String openAiKey) {
        return aiService.validateApiKey(openAiKey);
    }

    /**
     * PR의 변경된 파일 목록 조회
     */
    public List<ChangedFileDto> getChangedFiles(String accessToken, String owner, String repo, int prNumber) {
        try {
            List<ChangedFileDto> files = new ArrayList<>();
            int page = 1;
            while (true) {
                int currentPageNumber = page;
                ChangedFileDto[] pageFiles = webClientBuilder.build()
                        .get()
                        .uri(uriBuilder -> uriBuilder
                                .scheme("https")
                                .host("api.github.com")
                                .path("/repos/{owner}/{repo}/pulls/{prNumber}/files")
                                .queryParam("per_page", GITHUB_PAGE_SIZE)
                                .queryParam("page", currentPageNumber)
                                .build(owner, repo, prNumber))
                        .header("Authorization", "Bearer " + accessToken)
                        .header("Accept", "application/vnd.github.v3+json")
                        .retrieve()
                        .bodyToMono(ChangedFileDto[].class)
                        .block();

                List<ChangedFileDto> currentPage = pageFiles == null ? List.of() : Arrays.asList(pageFiles);
                files.addAll(currentPage);
                if (currentPage.size() < GITHUB_PAGE_SIZE) {
                    break;
                }
                page++;
            }
            log.info("Fetched changed files for {}/{} PR #{}: count={}", owner, repo, prNumber, files.size());
            return files;
        } catch (GithubRateLimitEx e) {
            throw e;
        } catch (Exception e) {
            throw new GitHubApiEx("Failed to get changed files", e);
        }
    }

    /**
     * PR의 상세 정보(base, head branch SHA 포함)를 조회
     */
    public PullRequestInfoDto getPullRequestInfo(String accessToken, String owner, String repo, int prNumber) {
        try {
            return webClientBuilder.build()
                    .get()
                    .uri("https://api.github.com/repos/{owner}/{repo}/pulls/{prNumber}", owner, repo, prNumber)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/vnd.github.v3+json")
                    .retrieve()
                    .bodyToMono(PullRequestInfoDto.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to fetch PR info for {}/{} PR #{}", owner, repo, prNumber, e);
            throw new GitHubApiEx("Failed to get PR info", e);
        }
    }

    /**
     * 특정 ref 기준 파일 내용을 조회한다.
     */
    public String getFileContent(String accessToken, String owner, String repo, String path, String ref) {
        try {
            GithubContentResponseDto response = webClientBuilder.build()
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host("api.github.com")
                            .path("/repos/{owner}/{repo}/contents/")
                            .path(path)
                            .queryParam("ref", ref)
                            .build(owner, repo))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/vnd.github.v3+json")
                    .retrieve()
                    .bodyToMono(GithubContentResponseDto.class)
                    .block();

            if (response == null || response.getContent() == null) {
                return null;
            }

            if ("base64".equalsIgnoreCase(response.getEncoding())) {
                String normalizedContent = response.getContent().replaceAll("\\s", "");
                return new String(Base64.getDecoder().decode(normalizedContent), StandardCharsets.UTF_8);
            }

            return response.getContent();
        } catch (Exception e) {
            log.error("Failed to fetch file content for {}/{} {} at {}: {}", owner, repo, path, ref, e.getMessage());
            throw new GitHubApiEx("Failed to get file content", e);
        }
    }

    /**
     * Github PR에 댓글 게시
     */
    public void postPRComment(String accessToken, String owner, String repo, int prNumber, String body) {
        Map<String, String> requestBody = new HashMap<>();
        requestBody.put("body", body);

        try {
            webClientBuilder.build()
                    .post()
                    .uri("https://api.github.com/repos/{owner}/{repo}/issues/{prNumber}/comments", owner, repo,
                            prNumber)
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(requestBody)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("Posted review comment to PR #{} in {}/{}", prNumber, owner, repo);
        } catch (Exception e) {
            log.error("Failed to post comment to PR #{} in {}/{}: {}", prNumber, owner, repo, e.getMessage());
            throw new GitHubApiEx("Failed to post PR comment", e);
        }
    }

    /**
     * 특정 브랜치(또는 커밋 SHA)의 파일 트리 구조(목록)를 가져옵니다.
     * recursive=true 전달 시 하위 디렉토리 트리를 한 번에 다 가져옵니다. (단일 API 호출)
     */
    public GitTreeResponseDto getRepositoryTree(String accessToken, String owner, String repo, String treeSha,
            boolean recursive) {
        try {
            String uri = "https://api.github.com/repos/{owner}/{repo}/git/trees/{treeSha}";
            if (recursive) {
                uri += "?recursive=1";
            }

            return webClientBuilder.build()
                    .get()
                    .uri(uri, owner, repo, treeSha)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/vnd.github.v3+json")
                    .retrieve()
                    .bodyToMono(GitTreeResponseDto.class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to fetch repository tree for {}/{} at {}: {}", owner, repo, treeSha, e.getMessage());
            throw new GitHubApiEx("Failed to fetch repository tree", e);
        }
    }

    /**
     * Github PR에 인라인 리뷰 게시 (Review API 사용)
     */
    public void postPRReview(String accessToken, String owner, String repo, int prNumber,
            GithubReviewRequestDto reviewRequest) {
        try {
            webClientBuilder.build()
                    .post()
                    .uri("https://api.github.com/repos/{owner}/{repo}/pulls/{prNumber}/reviews", owner, repo, prNumber)
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/vnd.github.v3+json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(reviewRequest)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("Posted inline review to PR #{} in {}/{}", prNumber, owner, repo);
        } catch (WebClientResponseException e) {
            log.error("Failed to post inline review to PR #{} in {}/{}: {} - Body: {}", prNumber, owner, repo,
                    e.getMessage(), e.getResponseBodyAsString());
            throw new GitHubApiEx("Failed to post PR review", e);
        } catch (Exception e) {
            log.error("Failed to post inline review to PR #{} in {}/{}: {}", prNumber, owner, repo, e.getMessage());
            throw new GitHubApiEx("Failed to post PR review", e);
        }
    }

    /**
     * Webhook 생성 요청 DTO 생성
     */
    private WebhookCreateRequestDto createWebhookRequest(String webhookSecret) {
        Map<String, String> config = new HashMap<>();
        config.put("url", webhookUrl);
        config.put("content_type", "json");
        config.put("insecure_ssl", "0");
        config.put("secret", webhookSecret);

        List<String> events = List.of("push", "pull_request");

        return WebhookCreateRequestDto.builder()
                .name("web")
                .config(config)
                .events(events)
                .active(true)
                .build();
    }

    /**
     * 웹훅 등록 (기존 웹훅이 있다면 삭제 후 재등록)
     */
    public void registerWebhook(String accessToken, String owner, String repository) {
        GithubAccount account = findByLoginIdOrThrow(owner);

        // 기존 웹훅 삭제 (중복 방지 및 시크릿 갱신)
        deleteExistingWebhook(accessToken, owner, repository);

        WebhookCreateRequestDto request = createWebhookRequest(account.getWebhookSecret());

        try {
            webClientBuilder.build()
                    .post()
                    .uri("https://api.github.com/repos/{owner}/{repo}/hooks", owner, repository)
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(request)
                    .retrieve()
                    .toBodilessEntity()
                    .block();

            log.info("Webhook registered for {}/{}", owner, repository);
        } catch (Exception e) {
            throw new WebhookRegistrationEx("Error occurred during webhook registration", e);
        }
    }

    /**
     * 기존에 등록된 웹훅이 있다면 삭제
     */
    private void deleteExistingWebhook(String accessToken, String owner, String repo) {
        try {
            List<WebhookResponseDto> webhooks = webClientBuilder.build()
                    .get()
                    .uri("https://api.github.com/repos/{owner}/{repo}/hooks", owner, repo)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .bodyToFlux(WebhookResponseDto.class)
                    .collectList()
                    .block();

            if (webhooks != null) {
                for (WebhookResponseDto hook : webhooks) {
                    Object configUrl = hook.getConfig().get("url");
                    if (configUrl != null && configUrl.toString().equals(webhookUrl)) {
                        webClientBuilder.build()
                                .delete()
                                .uri("https://api.github.com/repos/{owner}/{repo}/hooks/{hookId}", owner, repo,
                                        hook.getId())
                                .header("Authorization", "Bearer " + accessToken)
                                .retrieve()
                                .toBodilessEntity()
                                .block();
                    }
                }
            }
        } catch (Exception e) {
            // 삭제 실패하더라도 등록 시도 (GitHub에서 중복 에러 뱉으면 그때 실패 처리)
        }
    }

    /**
     * 저장소 캐시 강제 초기화
     */
    @CacheEvict(value = "repositories", key = "#accessToken")
    public void evictRepositoryCache(String accessToken) {
        log.info("Evicting repository cache for accessToken: {}", accessToken.substring(0, 5) + "...");
    }
}
