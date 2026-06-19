package com.seojs.aisenpai_backend.pullrequest.service;

import tools.jackson.core.JacksonException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReviewFailureClassifierTest {

    @Test
    void messageFor_ClassifiesJsonPayloadError() {
        // given
        JacksonException exception = new JacksonException("bad json") {};

        // when & then
        assertEquals("JSON_PAYLOAD_ERROR", ReviewFailureClassifier.codeFor(exception));
        assertEquals("AI review failed: AI 요청 데이터를 생성하지 못했습니다.",
                ReviewFailureClassifier.messageFor(exception));
    }

    @Test
    void messageFor_ClassifiesInvalidApiKey() {
        // given
        RestClientResponseException exception = responseException(HttpStatus.UNAUTHORIZED);

        // when & then
        assertEquals("INVALID_API_KEY", ReviewFailureClassifier.codeFor(exception));
        assertEquals("AI review failed: OpenAI API 키가 유효하지 않습니다.",
                ReviewFailureClassifier.messageFor(exception));
    }

    @Test
    void messageFor_ClassifiesRateLimit() {
        // given
        RestClientResponseException exception = responseException(HttpStatus.TOO_MANY_REQUESTS);

        // when & then
        assertEquals("RATE_LIMIT", ReviewFailureClassifier.codeFor(exception));
        assertEquals("AI review failed: OpenAI API rate limit에 도달했습니다. 잠시 후 다시 시도해 주세요.",
                ReviewFailureClassifier.messageFor(exception));
    }

    @Test
    void messageFor_ClassifiesTimeout() {
        // given
        ResourceAccessException exception = new ResourceAccessException("Read timed out");

        // when & then
        assertEquals("TIMEOUT", ReviewFailureClassifier.codeFor(exception));
        assertEquals("AI review failed: OpenAI 응답 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.",
                ReviewFailureClassifier.messageFor(exception));
    }

    private RestClientResponseException responseException(HttpStatus status) {
        return new RestClientResponseException(
                status.getReasonPhrase(),
                status.value(),
                status.getReasonPhrase(),
                HttpHeaders.EMPTY,
                new byte[0],
                StandardCharsets.UTF_8);
    }
}
