package com.seojs.aisenpai_backend.pullrequest.controller;

import com.seojs.aisenpai_backend.github.dto.ChangedFileDto;
import com.seojs.aisenpai_backend.github.service.GithubRepositoryAccessService;
import com.seojs.aisenpai_backend.pullrequest.dto.PullRequestResponseDto;
import com.seojs.aisenpai_backend.pullrequest.service.PullRequestService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/pull-request/{owner}/{repo}")
public class PullRequestApiController {
        private final PullRequestService pullRequestService;
        private final OAuth2AuthorizedClientService authorizedClientService;
        private final GithubRepositoryAccessService repositoryAccessService;

        @GetMapping
        public List<PullRequestResponseDto> getPullRequestList(
                        @AuthenticationPrincipal OAuth2User principal,
                        @PathVariable String owner,
                        @PathVariable String repo) {
                OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient("github",
                                principal.getName());
                String accessToken = authorizedClient.getAccessToken().getTokenValue();

                return pullRequestService.getPullRequestList(owner, repo, accessToken);
        }

        @GetMapping("/{prNumber}/changes")
        public List<ChangedFileDto> getPullRequestWithChanges(
                        @AuthenticationPrincipal OAuth2User principal,
                        @PathVariable String owner,
                        @PathVariable String repo,
                        @PathVariable Integer prNumber) {
                OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient("github",
                                principal.getName());
                String accessToken = authorizedClient.getAccessToken().getTokenValue();

                return pullRequestService.getPullRequestWithChanges(owner, repo, prNumber, accessToken);
        }

        @PostMapping("/{prNumber}/review")
        public void reviewPullRequest(
                        @AuthenticationPrincipal OAuth2User principal,
                        @PathVariable String owner,
                        @PathVariable String repo,
                        @PathVariable Integer prNumber,
                        @RequestParam(required = false) String model) {
                OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient("github",
                                principal.getName());
                String accessToken = authorizedClient.getAccessToken().getTokenValue();
                repositoryAccessService.requireReviewRequestPermission(accessToken, owner, repo);

                pullRequestService.review(owner, repo, prNumber, accessToken, model);
        }

        @GetMapping("/{prNumber}/review")
        public String getPullRequestReview(
                        @AuthenticationPrincipal OAuth2User principal,
                        @PathVariable String owner,
                        @PathVariable String repo,
                        @PathVariable Integer prNumber) {
                OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient("github",
                                principal.getName());
                String accessToken = authorizedClient.getAccessToken().getTokenValue();

                return pullRequestService.getAiReview(owner, repo, prNumber, accessToken);
        }
}
