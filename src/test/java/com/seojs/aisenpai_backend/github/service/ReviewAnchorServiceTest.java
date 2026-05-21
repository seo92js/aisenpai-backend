package com.seojs.aisenpai_backend.github.service;

import com.seojs.aisenpai_backend.github.service.ReviewAnchorService.AnchorFailureReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReviewAnchorServiceTest {

    private final ReviewAnchorService reviewAnchorService = new ReviewAnchorService();

    @Test
    void findLineNumber_AddedLineExactMatch_ReturnsNewFileLineNumber() {
        // given
        String patch = """
                @@ -1,3 +1,4 @@
                 class Example {
                +    private final String name;
                     void run() {}
                 }
                """;

        // when
        Integer lineNumber = reviewAnchorService.findLineNumber(patch, "private final String name;");

        // then
        assertEquals(2, lineNumber);
    }

    @Test
    void findLineNumber_ContextLineMatch_ReturnsNull() {
        // given
        String patch = """
                @@ -1,3 +1,4 @@
                 class Example {
                +    private final String name;
                     void run() {}
                 }
                """;

        // when
        Integer lineNumber = reviewAnchorService.findLineNumber(patch, "void run() {}");

        // then
        assertNull(lineNumber);
    }

    @Test
    void findLineNumber_PartialMatch_ReturnsNull() {
        // given
        String patch = """
                @@ -10,2 +10,3 @@
                +return token.isBlank();
                +return token.isBlankOrExpired();
                """;

        // when
        Integer lineNumber = reviewAnchorService.findLineNumber(patch, "token.isBlank");

        // then
        assertNull(lineNumber);
    }

    @Test
    void findAnchor_MultilineSnippet_ReturnsFailureReason() {
        // given
        String patch = """
                @@ -1,3 +1,5 @@
                 class Example {
                +    private final String name;
                +    private final String token;
                 }
                """;

        // when
        var result = reviewAnchorService.findAnchor(patch, "private final String name;\nprivate final String token;");

        // then
        assertNull(result.line());
        assertEquals(AnchorFailureReason.MULTILINE_SNIPPET_MISMATCH, result.failureReason());
    }

    @Test
    void findAnchor_MissingPatch_ReturnsFailureReason() {
        // when
        var result = reviewAnchorService.findAnchor(null, "private final String name;");

        // then
        assertNull(result.line());
        assertEquals(AnchorFailureReason.MISSING_PATCH, result.failureReason());
    }
}
