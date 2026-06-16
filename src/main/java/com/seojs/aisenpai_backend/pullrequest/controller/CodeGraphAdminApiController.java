package com.seojs.aisenpai_backend.pullrequest.controller;

import com.seojs.aisenpai_backend.github.dto.GitRepositoryResponseDto;
import com.seojs.aisenpai_backend.github.entity.RepositoryAiSettings;
import com.seojs.aisenpai_backend.github.service.GithubService;
import com.seojs.aisenpai_backend.github.service.RepositoryAiSettingsService;
import com.seojs.aisenpai_backend.pullrequest.service.CodeGraphIndexService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/repositories/{id}")
public class CodeGraphAdminApiController {

    private final CodeGraphIndexService codeGraphIndexService;
    private final RepositoryAiSettingsService repositoryAiSettingsService;
    private final GithubService githubService;
    private final OAuth2AuthorizedClientService authorizedClientService;

    @PostMapping("/reindex")
    public void reindex(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable("id") Long repositoryId) {

        boolean isServiceAdmin = principal.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ADMIN".equals(a.getAuthority()));

        RepositoryAiSettings settings = repositoryAiSettingsService.getRequired(repositoryId);
        String owner = settings.getOwner();
        String repo = settings.getRepositoryName();

        OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient("github", principal.getName());
        if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
            throw new SecurityException("GitHub authentication is required.");
        }
        String accessToken = authorizedClient.getAccessToken().getTokenValue();

        if (!isServiceAdmin) {
            GitRepositoryResponseDto gitRepo = githubService.getRepository(accessToken, owner, repo);
            if (gitRepo == null || !gitRepo.hasAdminPermission()) {
                throw new SecurityException("Repository admin permission is required to trigger reindexing.");
            }
        }

        String defaultBranch = githubService.getDefaultBranch(accessToken, owner, repo);
        String latestSha = githubService.getLatestCommitSha(accessToken, owner, repo, defaultBranch);

        if (latestSha == null || latestSha.isBlank()) {
            throw new IllegalArgumentException("Failed to resolve latest commit SHA for branch: " + defaultBranch);
        }

        log.info("Manual reindex triggered by user {} for repositoryId={}, branch={}, sha={}",
                principal.getName(), repositoryId, defaultBranch, latestSha);

        codeGraphIndexService.submitIndexingTask(repositoryId, "refs/heads/" + defaultBranch, latestSha, true);
    }
}
