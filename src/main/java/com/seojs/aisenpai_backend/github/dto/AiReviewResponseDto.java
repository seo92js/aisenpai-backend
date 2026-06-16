package com.seojs.aisenpai_backend.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiReviewResponseDto {
    private String generalReview;
    private List<ReviewCommentDto> comments;
    private List<ContextFileDto> contextFiles;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContextFileDto {
        private String path;
        private String type; // "changed" or "related"
        private String status; // e.g., "diff + content", "diff only (binary)", "content read"
    }
}
