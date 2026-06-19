package com.seojs.aisenpai_backend.github.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryAiSettingsTest {

    @Test
    void buildSystemPrompt_룰적용_확인() {
        // given
        RepositoryAiSettings settings = RepositoryAiSettings.builder()
                .repositoryId(1L)
                .owner("owner")
                .repositoryName("repo")
                .webhookSecret("secret")
                .build();

        Rule rule1 = Rule.builder()
                .repositorySettings(settings)
                .content("DTO에는 @Builder 필수")
                .isEnabled(true)
                .targetFilePattern("*.java")
                .build();

        Rule rule2 = Rule.builder()
                .repositorySettings(settings)
                .content("console.log 금지")
                .isEnabled(false)
                .targetFilePattern("*.ts")
                .build();

        settings.getRules().add(rule1);
        settings.getRules().add(rule2);

        // when
        String prompt = settings.buildSystemPrompt();

        // then
        assertTrue(prompt.contains("### 코드 리뷰 규칙"));
        assertTrue(prompt.contains("- [Target: *.java] DTO에는 @Builder 필수"));
        assertFalse(prompt.contains("console.log 금지"));
        assertTrue(prompt.contains("당신은 시니어 코드 리뷰어입니다."));
        assertTrue(prompt.contains("보안 취약점, 데이터 유실/오염, 런타임 예외, 동시성 이슈"));
        assertTrue(prompt.contains("객관성과 팩트 근거"));
        assertTrue(prompt.contains("소음 제거"));
        assertTrue(prompt.contains("기존 처리 존중"));
        assertTrue(prompt.contains("코멘트 완결성"));
        assertTrue(prompt.contains("[조건]"));
        assertTrue(prompt.contains("[영향]"));
        assertTrue(prompt.contains("[대안]"));
        assertTrue(prompt.contains("응답 형식 및 연동 규칙"));
        assertTrue(prompt.contains("comments 배열 내 'path'"));
        assertTrue(prompt.contains("'codeSnippet'은 지적 대상이 되는 추가된 소스 코드 라인"));
        assertTrue(prompt.contains("generalReview 필드는 비워두십시오"));
        assertTrue(prompt.contains("지적할 결함이 존재하지 않으면 comments를 빈 배열"));
        assertTrue(prompt.contains("테스트 파일 부재나 테스트 누락을 단정하여 지적하지 마십시오"));
    }
}
