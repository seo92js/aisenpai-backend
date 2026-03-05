package com.seojs.aisenpai_backend.github.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class GitTreeResponseDto {
    private String sha;
    private String url;
    private List<GitTreeItemDto> tree;
    private Boolean truncated;

    @Getter
    @Setter
    public static class GitTreeItemDto {
        private String path;
        private String mode;
        private String type;
        private String sha;
        private String url;
        private Long size;
    }
}
