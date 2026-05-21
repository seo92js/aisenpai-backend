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
        assertTrue(prompt.contains("이번 PR의 변경으로 새로 생긴 실제 위험"));
        assertTrue(prompt.contains("버그, 보안, 데이터 손실"));
        assertTrue(prompt.contains("단순 취향, 포맷팅, 사소한 네이밍"));
        assertTrue(prompt.contains("확실하지 않은 문제는 단정하지 말고"));
        assertTrue(prompt.contains("일반적인 주의사항, 권장사항, 가능성 언급, 스타일 의견은 작성하지 마세요"));
        assertTrue(prompt.contains("\"검토 필요\", \"확인 필요\", \"가능성 있음\", \"복잡해질 수 있음\""));
        assertTrue(prompt.contains("실제 실패 조건, 사용자나 시스템에 미치는 영향, 구체적 수정 방향"));
        assertTrue(prompt.contains("새 필드, 분기, 메서드, 클래스가 추가되었다는 사실만으로 문제라고 지적하지 마세요"));
        assertTrue(prompt.contains("명백하게 확인되는 문제만 리뷰하세요"));
        assertTrue(prompt.contains("이미 코드에 존재하는 처리"));
        assertTrue(prompt.contains("테스트, lock, 검증, 예외 처리가 diff에 포함되어 있으면"));
        assertTrue(prompt.contains("diff 근거 원칙과 응답 형식 규칙은 리뷰 톤, 포커스, 상세 수준보다 우선"));
        assertTrue(prompt.contains("\"path\""));
        assertTrue(prompt.contains("\"body\""));
        assertTrue(prompt.contains("comments에는 changedFiles.patch의 추가된 라인"));
        assertTrue(prompt.contains("reviewContext.changedFiles 또는 changedFiles"));
        assertTrue(prompt.contains("codeSnippet에는 여러 줄을 넣지 마세요"));
        assertTrue(prompt.contains("body가 실제 실패 조건, 영향, 수정 방향을 모두 설명하지 못하면"));
        assertTrue(prompt.contains("comments와 generalReview 모두에 지적 사항으로 작성하지 마세요"));
        assertTrue(prompt.contains("실제 게시용 generalReview는 서버가 검증 결과를 기준으로 생성"));
        assertTrue(prompt.contains("\"generalReview\": \"\""));
        assertTrue(prompt.contains("comments는 빈 배열"));
        assertTrue(prompt.contains("테스트 파일 부재나 테스트 누락을 단정하지 마세요"));
    }
}
