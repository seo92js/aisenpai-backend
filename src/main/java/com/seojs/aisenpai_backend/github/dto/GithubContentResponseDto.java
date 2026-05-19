package com.seojs.aisenpai_backend.github.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class GithubContentResponseDto {
    private String name;
    private String path;
    private String sha;
    private Long size;
    private String encoding;
    private String content;
}
