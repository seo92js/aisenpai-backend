package com.seojs.aisenpai_backend.github.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiReviewSettingsTest {

    @Test
    void buildSystemPrompt_룰적용_확인() {
        // given
        AiReviewSettings settings = AiReviewSettings.builder()
                .build();

        Rule rule1 = Rule.builder()
                .settings(settings)
                .content("DTO에는 @Builder 필수")
                .isEnabled(true)
                .targetFilePattern("*.java")
                .build();

        Rule rule2 = Rule.builder()
                .settings(settings)
                .content("console.log 금지")
                .isEnabled(false) // 비활성화
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
        assertTrue(prompt.contains("\"path\""));
        assertTrue(prompt.contains("\"body\""));
        assertTrue(prompt.contains("comments에는 diff의 추가된 라인"));
    }

}
