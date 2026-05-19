package com.seojs.aisenpai_backend.github.service;

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
}
