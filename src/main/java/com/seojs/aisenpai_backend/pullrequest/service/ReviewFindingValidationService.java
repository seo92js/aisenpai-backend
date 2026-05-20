package com.seojs.aisenpai_backend.pullrequest.service;

import com.seojs.aisenpai_backend.github.dto.AiReviewResponseDto;
import com.seojs.aisenpai_backend.github.dto.ChangedFileDto;
import com.seojs.aisenpai_backend.github.dto.ReviewCommentDto;
import com.seojs.aisenpai_backend.github.entity.Rule;
import com.seojs.aisenpai_backend.github.service.ReviewAnchorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewFindingValidationService {
    private static final String NO_VERIFIED_FINDINGS_MESSAGE =
            "검토 결과, 이번 변경에서 추가로 지적할 diff 라인 기준 코멘트는 없습니다.";
    private static final List<String> FINDING_PHRASES = List.of(
            "규칙을 위반", "위반하고", "위반했습니다", "위반됩니다",
            "문제가 있습니다", "오류가 있습니다", "버그가 있습니다",
            "위험합니다", "부적절합니다", "금지 규칙");

    private final ReviewAnchorService reviewAnchorService;

    public ValidationResult validate(AiReviewResponseDto aiResponse, List<ChangedFileDto> changedFiles,
            List<Rule> rules) {
        List<ReviewCommentDto> comments = aiResponse.getComments() != null ? aiResponse.getComments() : List.of();
        List<ChangedFileDto> files = changedFiles != null ? changedFiles : List.of();
        List<ReviewCommentDto> anchoredComments = comments.stream()
                .map(comment -> anchorComment(comment, files))
                .filter(comment -> comment != null)
                .toList();
        int discardedCount = comments.size() - anchoredComments.size();
        String generalReview = sanitizeGeneralReview(aiResponse.getGeneralReview(), anchoredComments, rules);

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

    private String sanitizeGeneralReview(String generalReview, List<ReviewCommentDto> anchoredComments,
            List<Rule> rules) {
        if (!hasText(generalReview)) {
            return generalReview;
        }
        if (looksLikeUnverifiedFinding(generalReview, rules)) {
            if (!anchoredComments.isEmpty()) {
                return "이번 변경에서 diff 라인 기준 리뷰 코멘트 " + anchoredComments.size() + "건을 확인했습니다.";
            }
            return NO_VERIFIED_FINDINGS_MESSAGE;
        }
        return generalReview;
    }

    private boolean looksLikeUnverifiedFinding(String generalReview, List<Rule> rules) {
        String normalizedReview = generalReview.toLowerCase(Locale.ROOT);
        if (FINDING_PHRASES.stream().anyMatch(normalizedReview::contains)) {
            return true;
        }

        Set<String> ruleTokens = rules == null ? Set.of() : rules.stream()
                .filter(Rule::isEnabled)
                .flatMap(rule -> tokenize(rule.getContent()).stream())
                .collect(Collectors.toSet());
        return ruleTokens.stream().anyMatch(normalizedReview::contains)
                && (normalizedReview.contains("위반") || normalizedReview.contains("금지"));
    }

    private Set<String> tokenize(String value) {
        if (!hasText(value)) {
            return Set.of();
        }
        return Arrays.stream(value.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}_]+"))
                .filter(token -> token.length() >= 3)
                .collect(Collectors.toSet());
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
