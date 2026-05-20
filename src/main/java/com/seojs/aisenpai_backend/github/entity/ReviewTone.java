package com.seojs.aisenpai_backend.github.entity;

import lombok.Getter;

@Getter
public enum ReviewTone {
    FRIENDLY("친절하고 격려하는 톤으로 리뷰해주세요. 긍정적인 표현을 사용하고, 검증 가능한 개선점도 부드럽게 제안해주세요."),
    STRICT("엄격하고 직접적인 톤으로 리뷰해주세요. 검증 가능한 문제점만 명확하게 지적하고, 개선이 필요한 부분을 구체적으로 설명해주세요."),
    NEUTRAL("객관적이고 중립적인 톤으로 리뷰해주세요. 사실과 diff 근거에 기반하여 균형 잡힌 피드백을 제공해주세요.");

    private final String prompt;

    ReviewTone(String prompt) {
        this.prompt = prompt;
    }
}
