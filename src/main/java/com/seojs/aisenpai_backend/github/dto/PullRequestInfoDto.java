package com.seojs.aisenpai_backend.github.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PullRequestInfoDto {
    private Long id;
    private Integer number;
    private String state;

    @JsonProperty("head")
    private PullRequestRefDto head;

    @JsonProperty("base")
    private PullRequestRefDto base;

    @Getter
    @Setter
    public static class PullRequestRefDto {
        private String ref;
        private String sha;
    }
}
