package com.seojs.aisenpai_backend.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class ExceptionRestControllerAdvice {

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(RuntimeException.class)
    public ErrorDto runtime(RuntimeException e) {
        String errorCode = "RUNTIME_EX";
        String message = e.getMessage();
        log.error("{} : {}", errorCode, message);
        return new ErrorDto(errorCode, message);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(GithubAccountNotFoundEx.class)
    public ErrorDto githubAccountNotFound(GithubAccountNotFoundEx e) {
        String errorCode = "GITHUB_ACCOUNT_NOT_FOUND";
        String message = e.getMessage();
        log.error("{} : {}", errorCode, message);
        return new ErrorDto(errorCode, message);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(PullRequestNotFoundEx.class)
    public ErrorDto pullRequestNotFound(PullRequestNotFoundEx e) {
        String errorCode = "PULL_REQUEST_NOT_FOUND";
        String message = e.getMessage();
        log.error("{} : {}", errorCode, message);
        return new ErrorDto(errorCode, message);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(RuleNotFoundEx.class)
    public ErrorDto ruleNotFound(RuleNotFoundEx e) {
        String errorCode = "RULE_NOT_FOUND";
        String message = e.getMessage();
        log.error("{} : {}", errorCode, message);
        return new ErrorDto(errorCode, message);
    }

    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @ExceptionHandler(SecurityException.class)
    public ErrorDto securityException(SecurityException e) {
        String errorCode = "SECURITY_VIOLATION";
        String message = e.getMessage();
        log.error("{} : {}", errorCode, message);
        return new ErrorDto(errorCode, message);
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(GitHubApiEx.class)
    public ErrorDto gitHubApiException(GitHubApiEx e) {
        String errorCode = "GITHUB_API_ERROR";
        String message = e.getMessage();
        log.error("{} : {}", errorCode, message);
        return new ErrorDto(errorCode, message);
    }

    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    @ExceptionHandler(GithubRateLimitEx.class)
    public ErrorDto githubRateLimitException(GithubRateLimitEx e) {
        String errorCode = "GITHUB_RATE_LIMIT";
        String message = e.getMessage();
        log.error("{} : {}", errorCode, message);
        return new ErrorDto(errorCode, message);
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(WebhookProcessingEx.class)
    public ErrorDto webhookProcessingException(WebhookProcessingEx e) {
        String errorCode = "WEBHOOK_PROCESSING_ERROR";
        String message = e.getMessage();
        log.error("{} : {}", errorCode, message);
        return new ErrorDto(errorCode, message);
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(TokenEncryptionEx.class)
    public ErrorDto tokenEncryptionException(TokenEncryptionEx e) {
        String errorCode = "TOKEN_ENCRYPTION_ERROR";
        String message = e.getMessage();
        log.error("{} : {}", errorCode, message);
        return new ErrorDto(errorCode, message);
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(WebhookRegistrationEx.class)
    public ErrorDto webhookRegistrationException(WebhookRegistrationEx e) {
        String errorCode = "WEBHOOK_REGISTRATION_ERROR";
        String message = e.getMessage();
        log.error("{} : {}", errorCode, message);
        return new ErrorDto(errorCode, message);
    }

    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @ExceptionHandler(InvalidGithubTokenException.class)
    public ErrorDto invalidGithubToken(InvalidGithubTokenException e) {
        String errorCode = "INVALID_GITHUB_TOKEN";
        String message = e.getMessage();
        log.error("{} : {}", errorCode, message);
        return new ErrorDto(errorCode, message);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(OpenAiKeyNotSetEx.class)
    public ErrorDto openAiKeyNotSet(OpenAiKeyNotSetEx e) {
        String errorCode = "OPEN_AI_KEY_NOT_SET";
        String message = e.getMessage();
        log.error("{} : {}", errorCode, message);
        return new ErrorDto(errorCode, message);
    }
}
