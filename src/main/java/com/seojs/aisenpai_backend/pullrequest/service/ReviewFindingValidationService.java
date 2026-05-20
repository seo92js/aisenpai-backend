package com.seojs.aisenpai_backend.pullrequest.service;

import com.seojs.aisenpai_backend.github.dto.AiReviewResponseDto;
import com.seojs.aisenpai_backend.github.dto.ChangedFileDto;
import com.seojs.aisenpai_backend.github.dto.ReviewCommentDto;
import com.seojs.aisenpai_backend.github.service.ReviewAnchorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewFindingValidationService {
    private final ReviewAnchorService reviewAnchorService;

    public ValidationResult validate(AiReviewResponseDto aiResponse, List<ChangedFileDto> changedFiles) {
        List<ReviewCommentDto> comments = aiResponse.getComments() != null ? aiResponse.getComments() : List.of();
        List<ChangedFileDto> files = changedFiles != null ? changedFiles : List.of();
        List<ReviewCommentDto> anchoredComments = comments.stream()
                .map(comment -> anchorComment(comment, files))
                .filter(comment -> comment != null)
                .toList();
        int discardedCount = comments.size() - anchoredComments.size();
        String generalReview = buildGeneralReview(files.size(), anchoredComments.size());

        log.info("Review finding validation result: anchored={}, discarded={}",
                anchoredComments.size(), discardedCount);
        return new ValidationResult(
                AiReviewResponseDto.builder()
                        .generalReview(generalReview)
                        .comments(anchoredComments)
                        .build(),
                anchoredComments,
                discardedCount);
    }

    private ReviewCommentDto anchorComment(ReviewCommentDto comment, List<ChangedFileDto> changedFiles) {
        if (!isValidReviewComment(comment)) {
            return null;
        }

        String filePatch = changedFiles.stream()
                .filter(file -> file.getFilename().equals(comment.getPath()))
                .findFirst()
                .map(ChangedFileDto::getPatch)
                .orElse(null);
        Integer line = reviewAnchorService.findLineNumber(filePatch, comment.getCodeSnippet());
        if (line == null) {
            return null;
        }

        return ReviewCommentDto.builder()
                .path(comment.getPath())
                .codeSnippet(comment.getCodeSnippet())
                .line(line)
                .side("RIGHT")
                .body(comment.getBody())
                .build();
    }

    private String buildGeneralReview(int changedFileCount, int anchoredCommentCount) {
        if (anchoredCommentCount > 0) {
            return "변경 파일 " + changedFileCount + "개를 검토했고, diff 기준 리뷰 코멘트 "
                    + anchoredCommentCount + "건을 확인했습니다.";
        }
        return "변경 파일 " + changedFileCount + "개를 검토했으며, 이번 diff에서 명백한 문제는 발견되지 않았습니다.";
    }

    private boolean isValidReviewComment(ReviewCommentDto comment) {
        return comment != null
                && hasText(comment.getPath())
                && hasText(comment.getCodeSnippet())
                && hasText(comment.getBody());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record ValidationResult(
            AiReviewResponseDto aiResponse,
            List<ReviewCommentDto> anchoredComments,
            int discardedCount) {
    }
}
