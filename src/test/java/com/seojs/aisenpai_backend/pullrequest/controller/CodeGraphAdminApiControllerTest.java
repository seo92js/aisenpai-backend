package com.seojs.aisenpai_backend.pullrequest.controller;

import com.seojs.aisenpai_backend.github.dto.GitRepositoryResponseDto;
import com.seojs.aisenpai_backend.github.entity.RepositoryAiSettings;
import com.seojs.aisenpai_backend.github.service.GithubService;
import com.seojs.aisenpai_backend.github.service.RepositoryAiSettingsService;
import com.seojs.aisenpai_backend.pullrequest.service.CodeGraphIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CodeGraphAdminApiControllerTest {

    @Mock
    private CodeGraphIndexService codeGraphIndexService;

    @Mock
    private RepositoryAiSettingsService repositoryAiSettingsService;

    @Mock
    private GithubService githubService;

    @Mock
    private OAuth2AuthorizedClientService authorizedClientService;

    private CodeGraphAdminApiController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new CodeGraphAdminApiController(
                codeGraphIndexService,
                repositoryAiSettingsService,
                githubService,
                authorizedClientService
        );
    }

    private OAuth2User mockOAuth2User(String username, String role) {
        SimpleGrantedAuthority authority = new SimpleGrantedAuthority(role);
        return new DefaultOAuth2User(
                Collections.singletonList(authority),
                Map.of("login", username, "id", 12345),
                "login"
        );
    }

    private OAuth2AuthorizedClient mockOAuth2AuthorizedClient() {
        OAuth2AccessToken token = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "mock-access-token",
                java.time.Instant.now(),
                java.time.Instant.now().plusSeconds(3600)
        );
        ClientRegistration clientRegistration = ClientRegistration.withRegistrationId("github")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .clientId("client-id")
                .tokenUri("https://github.com/login/oauth/access_token")
                .authorizationUri("https://github.com/login/oauth/authorize")
                .redirectUri("http://localhost:8080/login/oauth2/code/github")
                .build();
        return new OAuth2AuthorizedClient(clientRegistration, "principal-name", token);
    }

    @Test
    void testReindex_ServiceAdmin_Allowed() {
        Long repoId = 1L;
        OAuth2User principal = mockOAuth2User("admin-user", "ROLE_ADMIN");
        RepositoryAiSettings settings = RepositoryAiSettings.builder()
                .repositoryId(repoId)
                .owner("owner")
                .repositoryName("repo")
                .build();

        when(repositoryAiSettingsService.getRequired(repoId)).thenReturn(settings);
        when(authorizedClientService.loadAuthorizedClient("github", principal.getName()))
                .thenReturn(mockOAuth2AuthorizedClient());
        when(githubService.getDefaultBranch("mock-access-token", "owner", "repo")).thenReturn("main");
        when(githubService.getLatestCommitSha("mock-access-token", "owner", "repo", "main")).thenReturn("latest-sha");

        controller.reindex(principal, repoId);

        verify(codeGraphIndexService).submitIndexingTask(repoId, "refs/heads/main", "latest-sha", true);
        verify(githubService, never()).getRepository(any(), any(), any());
    }

    @Test
    void testReindex_GithubRepoAdmin_Allowed() {
        Long repoId = 1L;
        OAuth2User principal = mockOAuth2User("repo-admin", "ROLE_USER");
        RepositoryAiSettings settings = RepositoryAiSettings.builder()
                .repositoryId(repoId)
                .owner("owner")
                .repositoryName("repo")
                .build();

        GitRepositoryResponseDto gitRepo = mock(GitRepositoryResponseDto.class);
        when(gitRepo.hasAdminPermission()).thenReturn(true);

        when(repositoryAiSettingsService.getRequired(repoId)).thenReturn(settings);
        when(authorizedClientService.loadAuthorizedClient("github", principal.getName()))
                .thenReturn(mockOAuth2AuthorizedClient());
        when(githubService.getRepository("mock-access-token", "owner", "repo")).thenReturn(gitRepo);
        when(githubService.getDefaultBranch("mock-access-token", "owner", "repo")).thenReturn("main");
        when(githubService.getLatestCommitSha("mock-access-token", "owner", "repo", "main")).thenReturn("latest-sha");

        controller.reindex(principal, repoId);

        verify(codeGraphIndexService).submitIndexingTask(repoId, "refs/heads/main", "latest-sha", true);
    }

    @Test
    void testReindex_RegularUser_Forbidden() {
        Long repoId = 1L;
        OAuth2User principal = mockOAuth2User("regular-user", "ROLE_USER");
        RepositoryAiSettings settings = RepositoryAiSettings.builder()
                .repositoryId(repoId)
                .owner("owner")
                .repositoryName("repo")
                .build();

        GitRepositoryResponseDto gitRepo = mock(GitRepositoryResponseDto.class);
        when(gitRepo.hasAdminPermission()).thenReturn(false);

        when(repositoryAiSettingsService.getRequired(repoId)).thenReturn(settings);
        when(authorizedClientService.loadAuthorizedClient("github", principal.getName()))
                .thenReturn(mockOAuth2AuthorizedClient());
        when(githubService.getRepository("mock-access-token", "owner", "repo")).thenReturn(gitRepo);

        assertThrows(SecurityException.class, () -> controller.reindex(principal, repoId));
        verify(codeGraphIndexService, never()).submitIndexingTask(any(), any(), any(), anyBoolean());
    }
}
