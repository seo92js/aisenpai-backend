package com.seojs.aisenpai_backend.pullrequest.service;

import com.seojs.aisenpai_backend.github.dto.AiReviewResponseDto;
import com.seojs.aisenpai_backend.github.dto.ChangedFileDto;
import com.seojs.aisenpai_backend.github.dto.ReviewCommentDto;
import com.seojs.aisenpai_backend.github.service.ReviewAnchorService;
import com.seojs.aisenpai_backend.pullrequest.service.ReviewFindingValidationService.DiscardReason;
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
        var result = service.validate(response, List.of(changedFile("src/App.java", patch)));

        // then
        assertEquals(1, result.anchoredComments().size());
        assertEquals(2, result.anchoredComments().get(0).getLine());
        assertEquals(1, result.discardedCount());
        assertEquals(1, result.discardedReasonCounts().get(DiscardReason.SNIPPET_NOT_ADDED_LINE));
        assertEquals("변경 파일 1개를 검토했고, diff 기준 리뷰 코멘트 1건을 확인했습니다.",
                result.aiResponse().getGeneralReview());
    }

    @Test
    void validate_ReplacesAiGeneralReviewWhenNoDiffCommentsExist() {
        // given
        AiReviewResponseDto response = AiReviewResponseDto.builder()
                .generalReview("RepositoryAiSettings 엔티티 클래스에서 Setter 금지 규칙을 위반하고 있습니다.")
                .comments(List.of())
                .build();
        String patch = """
                @@ -130,6 +130,7 @@
                 class RepositoryAiSettings {
                +    public String buildSystemPrompt() { return ""; }
                 }
                """;

        // when
        var result = service.validate(response,
                List.of(changedFile("src/main/java/RepositoryAiSettings.java", patch)));

        // then
        assertEquals("변경 파일 1개를 검토했으며, 이번 diff에서 명백한 문제는 발견되지 않았습니다.",
                result.aiResponse().getGeneralReview());
        assertFalse(result.aiResponse().getGeneralReview().contains("Setter"));
    }

    @Test
    void validate_GeneratesNoFindingGeneralReviewWhenNoCommentsRemain() {
        // given
        AiReviewResponseDto response = AiReviewResponseDto.builder()
                .generalReview("전체적으로 변경 범위가 작고 구조가 명확합니다.")
                .comments(List.of())
                .build();

        // when
        var result = service.validate(response, List.of());

        // then
        assertEquals("변경 파일 0개를 검토했으며, 이번 diff에서 명백한 문제는 발견되지 않았습니다.",
                result.aiResponse().getGeneralReview());
    }

    @Test
    void validate_DoesNotPreserveAiGeneralReviewText() {
        // given
        AiReviewResponseDto response = AiReviewResponseDto.builder()
                .generalReview("전체적으로 구조가 개선되었고 필요한 변경만 포함되어 있습니다.")
                .comments(List.of())
                .build();

        // when
        var result = service.validate(response, List.of());

        // then
        assertEquals("변경 파일 0개를 검토했으며, 이번 diff에서 명백한 문제는 발견되지 않았습니다.",
                result.aiResponse().getGeneralReview());
    }

    @Test
    void validate_DoesNotPreserveAiGeneralReviewEvenWhenItLooksNeutral() {
        // given
        AiReviewResponseDto response = AiReviewResponseDto.builder()
                .generalReview("Setter 없이 생성자 중심으로 값이 설정되어 있습니다.")
                .comments(List.of())
                .build();

        // when
        var result = service.validate(response, List.of());

        // then
        assertEquals("변경 파일 0개를 검토했으며, 이번 diff에서 명백한 문제는 발견되지 않았습니다.",
                result.aiResponse().getGeneralReview());
    }

    @Test
    void validate_TreatsNullChangedFilesAsEmpty() {
        // given
        AiReviewResponseDto response = AiReviewResponseDto.builder()
                .generalReview("전체 요약입니다.")
                .comments(List.of(comment("src/App.java", "private final String name;", "라인 코멘트입니다.")))
                .build();

        // when
        var result = service.validate(response, null);

        // then
        assertEquals(0, result.anchoredComments().size());
        assertEquals(1, result.discardedCount());
        assertEquals(1, result.discardedReasonCounts().get(DiscardReason.PATH_NOT_CHANGED));
        assertEquals("변경 파일 0개를 검토했으며, 이번 diff에서 명백한 문제는 발견되지 않았습니다.",
                result.aiResponse().getGeneralReview());
    }

    @Test
    void validate_ClassifiesMissingPatchDiscardReason() {
        // given
        AiReviewResponseDto response = AiReviewResponseDto.builder()
                .comments(List.of(comment("src/App.java", "private final String name;", "검증 누락입니다.")))
                .build();

        // when
        var result = service.validate(response, List.of(changedFile("src/App.java", null)));

        // then
        assertEquals(0, result.anchoredComments().size());
        assertEquals(1, result.discardedCount());
        assertEquals(1, result.discardedReasonCounts().get(DiscardReason.MISSING_PATCH));
    }

    @Test
    void validate_ClassifiesMultilineSnippetMismatch() {
        // given
        AiReviewResponseDto response = AiReviewResponseDto.builder()
                .comments(List.of(comment("src/App.java",
                        "private final String name;\nprivate final String token;",
                        "여러 줄 스니펫은 GitHub inline anchor로 쓰지 않습니다.")))
                .build();
        String patch = """
                @@ -1,3 +1,5 @@
                 class App {
                +    private final String name;
                +    private final String token;
                 }
                """;

        // when
        var result = service.validate(response, List.of(changedFile("src/App.java", patch)));

        // then
        assertEquals(0, result.anchoredComments().size());
        assertEquals(1, result.discardedCount());
        assertEquals(1, result.discardedReasonCounts().get(DiscardReason.MULTILINE_SNIPPET_MISMATCH));
    }

    @Test
    void validate_ClassifiesInvalidShapeDiscardReason() {
        // given
        AiReviewResponseDto response = AiReviewResponseDto.builder()
                .comments(List.of(comment("src/App.java", "private final String name;", "")))
                .build();

        // when
        var result = service.validate(response, List.of());

        // then
        assertEquals(0, result.anchoredComments().size());
        assertEquals(1, result.discardedCount());
        assertEquals(1, result.discardedReasonCounts().get(DiscardReason.INVALID_SHAPE));
    }

    @Test
    void validate_ReplacesFindingLikeGeneralReviewEvenWhenCommentsAreAnchored() {
        // given
        AiReviewResponseDto response = AiReviewResponseDto.builder()
                .generalReview("RepositoryAiSettings 엔티티 클래스에서 Setter 금지 규칙을 위반하고 있습니다.")
                .comments(List.of(comment("src/App.java", "private final String name;", "생성자 검증이 필요합니다.")))
                .build();
        String patch = """
                @@ -1,3 +1,4 @@
                 class App {
                +    private final String name;
                 }
                """;

        // when
        var result = service.validate(response, List.of(changedFile("src/App.java", patch)));

        // then
        assertEquals("변경 파일 1개를 검토했고, diff 기준 리뷰 코멘트 1건을 확인했습니다.",
                result.aiResponse().getGeneralReview());
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
