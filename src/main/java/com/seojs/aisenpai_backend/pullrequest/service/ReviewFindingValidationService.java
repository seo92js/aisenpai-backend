package com.seojs.aisenpai_backend.pullrequest.service;

import com.seojs.aisenpai_backend.github.dto.AiReviewResponseDto;
import com.seojs.aisenpai_backend.github.dto.ChangedFileDto;
import com.seojs.aisenpai_backend.github.dto.ReviewCommentDto;
import com.seojs.aisenpai_backend.github.service.ReviewAnchorService;
import com.seojs.aisenpai_backend.github.service.ReviewAnchorService.AnchorFailureReason;
import com.seojs.aisenpai_backend.github.service.ReviewAnchorService.AnchorResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewFindingValidationService {
    private final ReviewAnchorService reviewAnchorService;

    public ValidationResult validate(AiReviewResponseDto aiResponse, List<ChangedFileDto> changedFiles) {
        List<ReviewCommentDto> comments = aiResponse.getComments() != null ? aiResponse.getComments() : List.of();
        List<ChangedFileDto> files = changedFiles != null ? changedFiles : List.of();
        EnumMap<DiscardReason, Integer> discardedReasonCounts = new EnumMap<>(DiscardReason.class);
        List<ReviewCommentDto> anchoredComments = new ArrayList<>();
        for (ReviewCommentDto comment : comments) {
            ReviewCommentDto anchoredComment = anchorComment(comment, files, discardedReasonCounts);
            if (anchoredComment != null) {
                anchoredComments.add(anchoredComment);
            }
        }
        int discardedCount = comments.size() - anchoredComments.size();
        String generalReview = buildGeneralReview(files.size(), anchoredComments.size());

        log.info("Review finding validation result: anchored={}, discarded={}, reasons={}",
                anchoredComments.size(), discardedCount, discardedReasonCounts);
        return new ValidationResult(
                AiReviewResponseDto.builder()
                        .generalReview(generalReview)
                        .comments(anchoredComments)
                        .contextFiles(aiResponse.getContextFiles())
                        .build(),
                anchoredComments,
                discardedCount,
                Map.copyOf(discardedReasonCounts));
    }

    private ReviewCommentDto anchorComment(ReviewCommentDto comment, List<ChangedFileDto> changedFiles,
            EnumMap<DiscardReason, Integer> discardedReasonCounts) {
        if (!isValidReviewComment(comment)) {
            countDiscard(discardedReasonCounts, DiscardReason.INVALID_SHAPE);
            return null;
        }

        ChangedFileDto changedFile = changedFiles.stream()
                .filter(file -> file.getFilename().equals(comment.getPath()))
                .findFirst()
                .orElse(null);
        if (changedFile == null) {
            countDiscard(discardedReasonCounts, DiscardReason.PATH_NOT_CHANGED);
            log.info("Discarded review finding. path={}, reason={}, snippet='{}'",
                    comment.getPath(), DiscardReason.PATH_NOT_CHANGED, firstSnippetLine(comment.getCodeSnippet()));
            return null;
        }

        AnchorResult anchorResult = reviewAnchorService.findAnchor(changedFile.getPatch(), comment.getCodeSnippet());
        if (!anchorResult.anchored()) {
            DiscardReason reason = mapAnchorFailure(anchorResult.failureReason());
            countDiscard(discardedReasonCounts, reason);
            log.info("Discarded review finding. path={}, reason={}, snippet='{}'",
                    comment.getPath(), reason, firstSnippetLine(comment.getCodeSnippet()));
            return null;
        }

        return ReviewCommentDto.builder()
                .path(comment.getPath())
                .codeSnippet(comment.getCodeSnippet())
                .line(anchorResult.line())
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

    private void countDiscard(EnumMap<DiscardReason, Integer> counts, DiscardReason reason) {
        counts.merge(reason, 1, Integer::sum);
    }

    private DiscardReason mapAnchorFailure(AnchorFailureReason reason) {
        if (reason == AnchorFailureReason.MISSING_PATCH) {
            return DiscardReason.MISSING_PATCH;
        }
        if (reason == AnchorFailureReason.MULTILINE_SNIPPET_MISMATCH) {
            return DiscardReason.MULTILINE_SNIPPET_MISMATCH;
        }
        if (reason == AnchorFailureReason.EMPTY_SNIPPET) {
            return DiscardReason.INVALID_SHAPE;
        }
        return DiscardReason.SNIPPET_NOT_ADDED_LINE;
    }

    private String firstSnippetLine(String codeSnippet) {
        if (codeSnippet == null) {
            return "";
        }
        return codeSnippet.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("");
    }

    public enum DiscardReason {
        INVALID_SHAPE,
        PATH_NOT_CHANGED,
        MISSING_PATCH,
        SNIPPET_NOT_ADDED_LINE,
        MULTILINE_SNIPPET_MISMATCH
    }

    public record ValidationResult(
            AiReviewResponseDto aiResponse,
            List<ReviewCommentDto> anchoredComments,
            int discardedCount,
            Map<DiscardReason, Integer> discardedReasonCounts) {
    }
}
