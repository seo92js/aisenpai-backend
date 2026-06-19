package com.seojs.aisenpai_backend.pullrequest.service;

import tools.jackson.core.JacksonException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.util.Locale;

final class ReviewFailureClassifier {
    private ReviewFailureClassifier() {
    }

    static String messageFor(Throwable throwable) {
        if (throwable instanceof JacksonException) {
            return "AI review failed: AI 요청 데이터를 생성하지 못했습니다.";
        }
        if (isInvalidApiConfiguration(throwable)) {
            return "AI review failed: OpenAI API 설정이 올바르지 않습니다.";
        }
        if (isInvalidApiKey(throwable)) {
            return "AI review failed: OpenAI API 키가 유효하지 않습니다.";
        }
        if (isRateLimited(throwable)) {
            return "AI review failed: OpenAI API rate limit에 도달했습니다. 잠시 후 다시 시도해 주세요.";
        }
        if (isTimeout(throwable)) {
            return "AI review failed: OpenAI 응답 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.";
        }
        return "AI review failed: 알 수 없는 오류가 발생했습니다.";
    }

    static String codeFor(Throwable throwable) {
        if (throwable instanceof JacksonException) {
            return "JSON_PAYLOAD_ERROR";
        }
        if (isInvalidApiConfiguration(throwable)) {
            return "INVALID_API_CONFIGURATION";
        }
        if (isInvalidApiKey(throwable)) {
            return "INVALID_API_KEY";
        }
        if (isRateLimited(throwable)) {
            return "RATE_LIMIT";
        }
        if (isTimeout(throwable)) {
            return "TIMEOUT";
        }
        return "UNKNOWN";
    }

    private static boolean isInvalidApiConfiguration(Throwable throwable) {
        return throwable instanceof IllegalArgumentException;
    }

    private static boolean isInvalidApiKey(Throwable throwable) {
        if (throwable instanceof NonTransientAiException) {
            String message = normalizedMessage(throwable);
            return message.contains("401") || message.contains("403") || message.contains("invalid api key")
                    || message.contains("incorrect api key") || message.contains("unauthorized");
        }
        RestClientResponseException responseException = responseExceptionOf(throwable);
        if (responseException == null) {
            return false;
        }
        HttpStatusCode statusCode = responseException.getStatusCode();
        return statusCode.value() == 401 || statusCode.value() == 403;
    }

    private static boolean isRateLimited(Throwable throwable) {
        RestClientResponseException responseException = responseExceptionOf(throwable);
        if (responseException != null && responseException.getStatusCode().value() == 429) {
            return true;
        }
        String message = normalizedMessage(throwable);
        return message.contains("rate limit") || message.contains("too many requests") || message.contains("429");
    }

    private static boolean isTimeout(Throwable throwable) {
        if (throwable instanceof ResourceAccessException || throwable instanceof SocketTimeoutException) {
            return true;
        }
        Throwable cause = throwable.getCause();
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        String message = normalizedMessage(throwable);
        return message.contains("timed out") || message.contains("timeout");
    }

    private static RestClientResponseException responseExceptionOf(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof RestClientResponseException responseException) {
                return responseException;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String normalizedMessage(Throwable throwable) {
        return throwable.getMessage() == null ? "" : throwable.getMessage().toLowerCase(Locale.ROOT);
    }
}
