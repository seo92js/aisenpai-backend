package com.seojs.aisenpai_backend.github.service;

import com.seojs.aisenpai_backend.github.dto.GitRepositoryResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GithubRepositoryAccessService {

    private final OAuth2AuthorizedClientService authorizedClientService;
    private final GithubService githubService;
    private final RepositoryAiSettingsService repositoryAiSettingsService;

    public Long resolveRepositoryId(OAuth2User principal, String owner, String repository) {
        GitRepositoryResponseDto repositoryInfo = getRepository(principal, owner, repository);
        repositoryAiSettingsService.getOrCreatePlaceholder(repositoryInfo.getId(), owner, repository);
        return repositoryInfo.getId();
    }

    public Long requireAdminRepositoryId(OAuth2User principal, String owner, String repository, String message) {
        GitRepositoryResponseDto repositoryInfo = getRepository(principal, owner, repository);
        if (!repositoryInfo.hasAdminPermission()) {
            throw new SecurityException(message);
        }
        repositoryAiSettingsService.getOrCreatePlaceholder(repositoryInfo.getId(), owner, repository);
        return repositoryInfo.getId();
    }

    public void requireReviewRequestPermission(String accessToken, String owner, String repository) {
        GitRepositoryResponseDto repositoryInfo = getRepository(accessToken, owner, repository);
        if (!repositoryInfo.hasReviewRequestPermission()) {
            throw new SecurityException("Repository write permission is required to request review.");
        }
    }

    private GitRepositoryResponseDto getRepository(OAuth2User principal, String owner, String repository) {
        return getRepository(getAccessToken(principal), owner, repository);
    }

    private GitRepositoryResponseDto getRepository(String accessToken, String owner, String repository) {
        GitRepositoryResponseDto repositoryInfo = githubService.getRepository(accessToken, owner, repository);
        if (repositoryInfo == null || repositoryInfo.getId() == null) {
            throw new SecurityException("Repository access is required.");
        }
        return repositoryInfo;
    }

    private String getAccessToken(OAuth2User principal) {
        OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient("github",
                principal.getName());
        if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
            throw new SecurityException("GitHub login is required.");
        }
        return authorizedClient.getAccessToken().getTokenValue();
    }
}
