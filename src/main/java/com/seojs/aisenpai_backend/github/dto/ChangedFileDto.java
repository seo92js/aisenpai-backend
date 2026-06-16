package com.seojs.aisenpai_backend.github.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ChangedFileDto {
    private String filename;        // 파일명
    private String status;          // 파일 상태 (added, modified, removed, renamed)
    private int additions;          // 추가된 라인 수
    private int deletions;          // 삭제된 라인 수
    private int changes;            // 총 변경된 라인 수
    private int lines;              // 파일의 전체 라인 수
    private String sha;             // 파일의 SHA 해시
    private String blobUrl;         // 파일의 전체 URL
    private String rawUrl;          // 파일의 raw URL
    private String contentsUrl;     // 파일의 contents URL
    private String patch;           // 파일의 patch 내용 (diff)
    @com.fasterxml.jackson.annotation.JsonProperty("previous_filename")
    private String previousFilename; // 이전 파일명 (renamed 상태인 경우)

    public ChangedFileDto(String filename, String status, int additions, int deletions, int changes, int lines,
            String sha, String blobUrl, String rawUrl, String contentsUrl, String patch) {
        this(filename, status, additions, deletions, changes, lines, sha, blobUrl, rawUrl, contentsUrl, patch, null);
    }

    @lombok.Builder
    @lombok.experimental.Tolerate
    public ChangedFileDto() {}
}
