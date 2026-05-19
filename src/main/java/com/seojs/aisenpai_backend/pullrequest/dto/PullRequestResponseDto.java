package com.seojs.aisenpai_backend.pullrequest.dto;

import java.time.LocalDateTime;

import com.seojs.aisenpai_backend.pullrequest.entity.PullRequest;
import com.seojs.aisenpai_backend.pullrequest.entity.PullRequest.PullRequestState;
import com.seojs.aisenpai_backend.pullrequest.entity.PullRequest.ReviewStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PullRequestResponseDto {
    private Integer prNumber;
    private String title;
    private String action;
    private ReviewStatus status;
    private PullRequestState prState;
    private String headSha;
    private String reviewStartedHeadSha;
    private String reviewCompletedHeadSha;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String aiReview;

    public static PullRequestResponseDto fromEntity(PullRequest pullRequest) {
        return new PullRequestResponseDto(
                pullRequest.getPrNumber(),
                pullRequest.getTitle(),
                pullRequest.getAction(),
                pullRequest.getStatus(),
                pullRequest.getPrState(),
                pullRequest.getHeadSha(),
                pullRequest.getReviewStartedHeadSha(),
                pullRequest.getReviewCompletedHeadSha(),
                pullRequest.getCreatedAt(),
                pullRequest.getUpdatedAt(),
                pullRequest.getAiReview());
    }
}
