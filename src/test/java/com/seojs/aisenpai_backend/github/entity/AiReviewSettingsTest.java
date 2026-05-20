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
        assertTrue(prompt.contains("이번 PR의 변경으로 새로 생긴 실제 위험"));
        assertTrue(prompt.contains("버그, 보안, 데이터 손실"));
        assertTrue(prompt.contains("단순 취향, 포맷팅, 사소한 네이밍"));
        assertTrue(prompt.contains("확실하지 않은 문제는 단정하지 말고"));
        assertTrue(prompt.contains("diff 근거 원칙과 응답 형식 규칙은 리뷰 톤, 포커스, 상세 수준보다 우선"));
        assertTrue(prompt.contains("\"path\""));
        assertTrue(prompt.contains("\"body\""));
        assertTrue(prompt.contains("comments에는 changedFiles.patch의 추가된 라인"));
        assertTrue(prompt.contains("reviewContext.changedFiles 또는 changedFiles"));
        assertTrue(prompt.contains("comments와 generalReview 모두에 지적 사항으로 작성하지 마세요"));
        assertTrue(prompt.contains("comments는 빈 배열"));
        assertTrue(prompt.contains("테스트 파일 부재나 테스트 누락을 단정하지 마세요"));
    }

}
