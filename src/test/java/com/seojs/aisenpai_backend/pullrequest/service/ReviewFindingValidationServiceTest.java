package com.seojs.aisenpai_backend.pullrequest.service;

import com.seojs.aisenpai_backend.github.dto.AiReviewResponseDto;
import com.seojs.aisenpai_backend.github.dto.ChangedFileDto;
import com.seojs.aisenpai_backend.github.dto.ReviewCommentDto;
import com.seojs.aisenpai_backend.github.entity.Rule;
import com.seojs.aisenpai_backend.github.service.ReviewAnchorService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ReviewFindingValidationServiceTest {
    private final ReviewFindingValidationService service = new ReviewFindingValidationService(new ReviewAnchorService());

    @Test
    void validate_KeepsOnlyAnchoredDiffFindings() {
        // given
        AiReviewResponseDto response = AiReviewResponseDto.builder()
                .generalReview("전체적으로 변경 범위가 작고 구조가 명확합니다.")
                .comments(List.of(
                        comment("src/App.java", "private final String name;", "생성자 검증이 필요합니다."),
                        comment("src/App.java", "void run() {}", "기존 라인 의견입니다.")))
                .build();
        String patch = """
                @@ -1,3 +1,4 @@
                 class App {
                +    private final String name;
                     void run() {}
                 }
                """;

        // when
        var result = service.validate(response, List.of(changedFile("src/App.java", patch)), List.of());

        // then
        assertEquals(1, result.anchoredComments().size());
        assertEquals(2, result.anchoredComments().get(0).getLine());
        assertEquals(1, result.discardedCount());
        assertEquals("전체적으로 변경 범위가 작고 구조가 명확합니다.", result.aiResponse().getGeneralReview());
    }

    @Test
    void validate_RemovesRuleLikeGeneralReviewWhenNoDiffEvidenceExists() {
        // given
        Rule rule = Rule.builder()
                .content("Entity 클래스에는 Setter 금지")
                .isEnabled(true)
                .targetFilePattern("**/*.java")
                .build();
        AiReviewResponseDto response = AiReviewResponseDto.builder()
                .generalReview("AiReviewSettings 엔티티 클래스에서 Setter 금지 규칙을 위반하고 있습니다.")
                .comments(List.of())
                .build();
        String patch = """
                @@ -130,6 +130,7 @@
                 class AiReviewSettings {
                +    public String buildSystemPrompt() { return ""; }
                 }
                """;

        // when
        var result = service.validate(response,
                List.of(changedFile("src/main/java/AiReviewSettings.java", patch)), List.of(rule));

        // then
        assertEquals("검증 가능한 diff 라인 기준 코멘트가 없습니다.", result.aiResponse().getGeneralReview());
        assertFalse(result.aiResponse().getGeneralReview().contains("Setter"));
    }

    @Test
    void validate_KeepsNeutralGeneralSummaryWithoutFindings() {
        // given
        AiReviewResponseDto response = AiReviewResponseDto.builder()
                .generalReview("전체적으로 변경 범위가 작고 구조가 명확합니다.")
                .comments(List.of())
                .build();

        // when
        var result = service.validate(response, List.of(), List.of());

        // then
        assertEquals("전체적으로 변경 범위가 작고 구조가 명확합니다.", result.aiResponse().getGeneralReview());
    }

    @Test
    void validate_KeepsGeneralSummaryThatUsesBroadReviewWords() {
        // given
        AiReviewResponseDto response = AiReviewResponseDto.builder()
                .generalReview("전체적으로 구조가 개선되었고 필요한 변경만 포함되어 있습니다.")
                .comments(List.of())
                .build();

        // when
        var result = service.validate(response, List.of(), List.of());

        // then
        assertEquals("전체적으로 구조가 개선되었고 필요한 변경만 포함되어 있습니다.", result.aiResponse().getGeneralReview());
    }

    @Test
    void validate_KeepsRuleKeywordWhenItIsNotAViolationClaim() {
        // given
        Rule rule = Rule.builder()
                .content("Entity 클래스에는 Setter 금지")
                .isEnabled(true)
                .targetFilePattern("**/*.java")
                .build();
        AiReviewResponseDto response = AiReviewResponseDto.builder()
                .generalReview("Setter 없이 생성자 중심으로 값이 설정되어 있습니다.")
                .comments(List.of())
                .build();

        // when
        var result = service.validate(response, List.of(), List.of(rule));

        // then
        assertEquals("Setter 없이 생성자 중심으로 값이 설정되어 있습니다.", result.aiResponse().getGeneralReview());
    }

    @Test
    void validate_TreatsNullChangedFilesAsEmpty() {
        // given
        AiReviewResponseDto response = AiReviewResponseDto.builder()
                .generalReview("전체 요약입니다.")
                .comments(List.of(comment("src/App.java", "private final String name;", "라인 코멘트입니다.")))
                .build();

        // when
        var result = service.validate(response, null, List.of());

        // then
        assertEquals(0, result.anchoredComments().size());
        assertEquals(1, result.discardedCount());
    }

    @Test
    void validate_ReplacesFindingLikeGeneralReviewEvenWhenCommentsAreAnchored() {
        // given
        Rule rule = Rule.builder()
                .content("Entity 클래스에는 Setter 금지")
                .isEnabled(true)
                .targetFilePattern("**/*.java")
                .build();
        AiReviewResponseDto response = AiReviewResponseDto.builder()
                .generalReview("AiReviewSettings 엔티티 클래스에서 Setter 금지 규칙을 위반하고 있습니다.")
                .comments(List.of(comment("src/App.java", "private final String name;", "생성자 검증이 필요합니다.")))
                .build();
        String patch = """
                @@ -1,3 +1,4 @@
                 class App {
                +    private final String name;
                 }
                """;

        // when
        var result = service.validate(response, List.of(changedFile("src/App.java", patch)), List.of(rule));

        // then
        assertEquals("diff 라인에 매칭된 리뷰 코멘트 1건이 있습니다.", result.aiResponse().getGeneralReview());
        assertEquals(1, result.anchoredComments().size());
    }

    private ReviewCommentDto comment(String path, String snippet, String body) {
        return ReviewCommentDto.builder()
                .path(path)
                .codeSnippet(snippet)
                .body(body)
                .build();
    }

    private ChangedFileDto changedFile(String filename, String patch) {
        return new ChangedFileDto(filename, "modified", 1, 0, 1, 10, "sha", "blob", "raw", "contents", patch);
    }
}
