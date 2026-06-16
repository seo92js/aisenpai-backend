package com.seojs.aisenpai_backend.github.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class WebhookPayloadDto {
    private String action;
    @JsonProperty("pull_request")
    private PullRequestDto pullRequest;
    private RepositoryDto repository;

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class PullRequestDto {
        private int number;
        private String title;
        private String body;
        private String state;
        private UserDto user;
        private String htmlUrl;
        private String diffUrl;
        private RefDto head;
        private RefDto base;
        private Boolean merged;
        @JsonProperty("merge_commit_sha")
        private String mergeCommitSha;

        public PullRequestDto(int number, String title, String body, String state, UserDto user, String htmlUrl,
                String diffUrl) {
            this(number, title, body, state, user, htmlUrl, diffUrl, null, null, null, null);
        }

        public PullRequestDto(int number, String title, String body, String state, UserDto user, String htmlUrl,
                String diffUrl, RefDto head, RefDto base, Boolean merged) {
            this(number, title, body, state, user, htmlUrl, diffUrl, head, base, merged, null);
        }
    }

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RefDto {
        private String ref;
        private String sha;
    }

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class RepositoryDto {
        private Long id;
        private String name;
        private String fullName;
        private UserDto owner;
        @JsonProperty("default_branch")
        private String defaultBranch;

        public RepositoryDto(Long id, String name, String fullName, UserDto owner) {
            this(id, name, fullName, owner, null);
        }
    }

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static class UserDto {
        private String login;
        private int id;
        private String htmlUrl;
    }
}
