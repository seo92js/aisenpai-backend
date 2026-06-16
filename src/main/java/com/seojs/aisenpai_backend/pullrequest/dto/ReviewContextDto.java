package com.seojs.aisenpai_backend.pullrequest.dto;

import com.seojs.aisenpai_backend.github.dto.ChangedFileDto;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ReviewContextDto {
    private PullRequestMetaDto pullRequest;
    private List<ChangedFileContextDto> changedFiles;
    private RepositoryTreeContextDto repositoryTree;
    private List<RelatedFileContextDto> relatedFiles;
    private BudgetDto budget;

    @Getter
    @Builder
    public static class PullRequestMetaDto {
        private String owner;
        private String repo;
        private Integer prNumber;
        private String baseSha;
        private String headSha;
        private String baseRef;
        private String headRef;
    }

    @Getter
    @Builder
    public static class ChangedFileContextDto {
        private String filename;
        private String status;
        private int additions;
        private int deletions;
        private int changes;
        private String patch;
        private boolean patchTruncated;
        private String headContent;
        private String baseContent;
        private ContentFetchStatus contentFetchStatus;
        private String contentSkipReason;
        private boolean truncated;
        private String previousFilename;

        public static ChangedFileContextDto fromChangedFile(ChangedFileDto file, String patch, boolean patchTruncated,
                String headContent, ContentFetchStatus contentFetchStatus, String contentSkipReason,
                boolean truncated) {
            return ChangedFileContextDto.builder()
                    .filename(file.getFilename())
                    .status(file.getStatus())
                    .additions(file.getAdditions())
                    .deletions(file.getDeletions())
                    .changes(file.getChanges())
                    .patch(patch)
                    .patchTruncated(patchTruncated)
                    .headContent(headContent)
                    .baseContent(null)
                    .contentFetchStatus(contentFetchStatus)
                    .contentSkipReason(contentSkipReason)
                    .truncated(truncated)
                    .previousFilename(file.getPreviousFilename())
                    .build();
        }
    }

    public enum ContentFetchStatus {
        FETCHED,
        SKIPPED,
        FAILED
    }

    @Getter
    @Builder
    public static class RepositoryTreeContextDto {
        private String summary;
        private boolean truncated;
    }

    @Getter
    @Builder
    public static class RelatedFileContextDto {
        private String path;
        private String ref;
        private String content;
        private String reason;
        private ContentFetchStatus contentFetchStatus;
        private String contentSkipReason;
        private boolean truncated;
    }

    @Getter
    @Builder
    public static class BudgetDto {
        private int maxContextFiles;
        private int maxPatchChars;
        private int maxFileContentChars;
        private int maxRelatedFiles;
        private int maxRelatedFileContentChars;
        private int maxTotalContentChars;
        private int maxTreeChars;
        private int maxContextChars;
        private int usedContentChars;
        private int usedContextChars;
    }
}
