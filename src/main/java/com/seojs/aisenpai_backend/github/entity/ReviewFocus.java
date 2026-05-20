package com.seojs.aisenpai_backend.github.entity;

import lombok.Getter;

@Getter
public enum ReviewFocus {
    PRAISE_ONLY("잘 작성된 코드, 좋은 패턴, 개선된 점 위주로 칭찬해주세요. 문제점 지적은 검증 가능한 diff 근거가 있을 때만 최소한으로 작성해주세요."),
    IMPROVEMENT_ONLY("검증 가능한 diff 근거가 있는 버그, 성능 이슈, 보안 취약점, 코드 품질 개선점 위주로 피드백해주세요. 근거 없는 문제를 만들지 말고 칭찬은 최소화해주세요."),
    BOTH("잘 작성된 부분은 칭찬하고, 검증 가능한 diff 근거가 있는 개선점은 구체적으로 제안해주세요. 균형 잡힌 리뷰를 제공해주세요.");

    private final String prompt;

    ReviewFocus(String prompt) {
        this.prompt = prompt;
    }
}
