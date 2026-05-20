package com.seojs.aisenpai_backend.github.entity;

import lombok.Getter;

@Getter
public enum DetailLevel {
    CONCISE("핵심적인 내용만 간결하게 3~5줄 이내로 리뷰해주세요. 불필요한 설명은 생략해주세요."),
    STANDARD("적당한 수준의 상세도로 리뷰해주세요. 중요한 포인트를 설명하되 너무 길지 않게 작성해주세요."),
    DETAILED("매우 상세하게 리뷰해주세요. 검증 가능한 지적에는 코드 예시, 개선 방안, 관련 베스트 프랙티스까지 포함하되 근거 없는 지적은 추가하지 마세요.");

    private final String prompt;

    DetailLevel(String prompt) {
        this.prompt = prompt;
    }
}
