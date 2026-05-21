package com.seojs.aisenpai_backend.github.controller;

import com.seojs.aisenpai_backend.github.dto.GitRepositoryWithWebhookResponseDto;
import com.seojs.aisenpai_backend.github.dto.OpenAiKeyDto;
import com.seojs.aisenpai_backend.github.dto.ReviewSettingsDto;
import com.seojs.aisenpai_backend.github.service.GithubRepositoryAccessService;
import com.seojs.aisenpai_backend.github.service.GithubService;
import com.seojs.aisenpai_backend.github.service.RepositoryCacheService;
import com.seojs.aisenpai_backend.github.service.RepositoryAiSettingsService;
import com.seojs.aisenpai_backend.pullrequest.service.PullRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/github")
public class GithubApiController {
    private final GithubService githubService;
    private final RepositoryAiSettingsService repositoryAiSettingsService;
    private final GithubRepositoryAccessService repositoryAccessService;
    private final RepositoryCacheService repositoryCacheService;
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final PullRequestService pullRequestService;

    @GetMapping("/status")
    public boolean getLoginStatus(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            return false;
        }

        OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient("github",
                principal.getName());
        return authorizedClient != null && authorizedClient.getAccessToken() != null;
    }

    @GetMapping("/username")
    public String getUsername(@AuthenticationPrincipal OAuth2User principal) {
        return principal.getAttribute("login");
    }

    @GetMapping("/repositories")
    public List<GitRepositoryWithWebhookResponseDto> getRepositories(@AuthenticationPrincipal OAuth2User principal) {
        OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient("github",
                principal.getName());
        String accessToken = authorizedClient.getAccessToken().getTokenValue();
        return githubService.getRepositoriesWithWebhookStatus(accessToken);
    }

    /**
     * 저장소 목록 캐시 초기화 (새로고침)
     */
    @PostMapping("/repositories/refresh")
    public void refreshRepositories(@AuthenticationPrincipal OAuth2User principal) {
        OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient("github",
                principal.getName());
        String accessToken = authorizedClient.getAccessToken().getTokenValue();
        repositoryCacheService.evictByAccessToken(accessToken);
    }

    @PostMapping("/webhook/")
    public void handleWebhook(@RequestBody String payload,
            @RequestHeader("X-Github-Event") String event,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature) {
        if (event.equals("pull_request")) {
            pullRequestService.processAndSaveWebhook(payload, signature);
        }
    }

    @PostMapping("/register")
    public void registerWebhook(@AuthenticationPrincipal OAuth2User principal, @RequestParam String owner,
            @RequestParam String repository) {
        OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient("github",
                principal.getName());
        String accessToken = authorizedClient.getAccessToken().getTokenValue();
        String requesterLogin = principal.getAttribute("login");

        githubService.registerWebhook(accessToken, requesterLogin, owner, repository);
    }

    @GetMapping("/review-settings")
    public ReviewSettingsDto getReviewSettings(@AuthenticationPrincipal OAuth2User principal,
            @RequestParam String owner, @RequestParam String repository) {
        Long repositoryId = repositoryAccessService.resolveRepositoryId(principal, owner, repository);
        return repositoryAiSettingsService.getReviewSettings(repositoryId);
    }

    @PatchMapping("/review-settings")
    public Long updateReviewSettings(@AuthenticationPrincipal OAuth2User principal,
            @RequestParam String owner, @RequestParam String repository, @RequestBody ReviewSettingsDto dto) {
        Long repositoryId = repositoryAccessService.requireAdminRepositoryId(principal, owner, repository,
                "Repository admin permission is required to update AI settings.");
        return repositoryAiSettingsService.updateReviewSettings(repositoryId, dto);
    }

    @GetMapping("/ignore")
    public List<String> getIgnorePatterns(@AuthenticationPrincipal OAuth2User principal,
            @RequestParam String owner, @RequestParam String repository) {
        Long repositoryId = repositoryAccessService.resolveRepositoryId(principal, owner, repository);
        return repositoryAiSettingsService.getIgnorePatterns(repositoryId);
    }

    @PatchMapping("/ignore")
    public Long updateIgnorePatterns(@AuthenticationPrincipal OAuth2User principal,
            @RequestParam String owner, @RequestParam String repository, @RequestBody List<String> patterns) {
        Long repositoryId = repositoryAccessService.requireAdminRepositoryId(principal, owner, repository,
                "Repository admin permission is required to update AI settings.");
        return repositoryAiSettingsService.updateIgnorePatterns(repositoryId, patterns);
    }

    @PatchMapping("/openai")
    public Long updateOpenAiKey(@AuthenticationPrincipal OAuth2User principal, @RequestParam String owner,
            @RequestParam String repository, @RequestBody OpenAiKeyDto dto) {
        Long repositoryId = repositoryAccessService.requireAdminRepositoryId(principal, owner, repository,
                "Repository admin permission is required to update AI settings.");
        return repositoryAiSettingsService.updateOpenAiKey(repositoryId, dto.getKey());
    }

    @GetMapping("/openai")
    public String getOpenAiKey(@AuthenticationPrincipal OAuth2User principal,
            @RequestParam String owner, @RequestParam String repository) {
        Long repositoryId = repositoryAccessService.resolveRepositoryId(principal, owner, repository);
        return repositoryAiSettingsService.getMaskedOpenAiKey(repositoryId);
    }

    @PostMapping("/openai/validate")
    public boolean validateOpenAiKey(@RequestBody OpenAiKeyDto dto) {
        return githubService.validateOpenAiKey(dto.getKey());
    }
}
