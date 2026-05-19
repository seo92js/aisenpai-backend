package com.seojs.aisenpai_backend.github.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewCommentDto {
    @JsonAlias({ "file", "filename" })
    private String path;

    @JsonAlias({ "snippet", "code" })
    private String codeSnippet;

    private Integer line;

    private String side;

    @JsonAlias({ "comment", "message" })
    private String body;
}
