package com.seojs.aisenpai_backend.github.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Getter
@NoArgsConstructor
@Entity
public class AiReviewSettings {
    @Id
    private Long id;

    @OneToOne
    @MapsId
    @JoinColumn(name = "id")
    private GithubAccount githubAccount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewTone reviewTone = ReviewTone.NEUTRAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewFocus reviewFocus = ReviewFocus.BOTH;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DetailLevel detailLevel = DetailLevel.STANDARD;

    @OneToMany(mappedBy = "settings", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Rule> rules = new ArrayList<>();

    @Column(length = 1000)
    private String ignorePatterns;

    @Column
    private String openAiKey;

    @Column(nullable = false)
    private Boolean autoReviewEnabled = false;

    @Column(nullable = false)
    private Boolean autoPostToGithub = false;

    @Column(nullable = false)
    private String openaiModel = "gpt-4o-mini";

    @Builder
    public AiReviewSettings(GithubAccount githubAccount) {
        this.githubAccount = githubAccount;
        this.reviewTone = ReviewTone.NEUTRAL;
        this.reviewFocus = ReviewFocus.BOTH;
        this.detailLevel = DetailLevel.STANDARD;
        this.autoReviewEnabled = false;
        this.autoPostToGithub = false;
        this.openaiModel = "gpt-4o-mini";
        this.ignorePatterns = "package-lock.json, yarn.lock, *.lock, .env*, *.pem, *.key, .yml, .yaml";
    }

    public void updateReviewSettings(ReviewTone tone, ReviewFocus focus, DetailLevel detailLevel,
            Boolean autoReviewEnabled, Boolean autoPostToGithub, String openaiModel) {
        this.reviewTone = tone;
        this.reviewFocus = focus;
        this.detailLevel = detailLevel;
        this.autoReviewEnabled = autoReviewEnabled != null ? autoReviewEnabled : false;
        this.autoPostToGithub = autoPostToGithub != null ? autoPostToGithub : false;
        this.openaiModel = openaiModel != null ? openaiModel : "gpt-4o-mini";
    }

    public void updateIgnorePatterns(String ignorePatterns) {
        this.ignorePatterns = ignorePatterns;
    }

    public void updateOpenAiKey(String openAiKey) {
        this.openAiKey = openAiKey;
    }

    public List<String> getIgnorePatternsAsList() {
        if (this.ignorePatterns == null || this.ignorePatterns.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.asList(this.ignorePatterns.split("\\s*,\\s*"));
    }

    /**
     * 설정된 옵션들을 조합하여 최종 시스템 프롬프트를 생성
     */
    public String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 시니어 코드 리뷰어입니다.\n\n");
        sb.append("주어진 변경된 파일 목록과 저장소 구조 정보를 바탕으로 코드 리뷰를 작성해 주세요.\n\n");

        sb.append("### 리뷰 톤\n");
        sb.append(this.reviewTone.getPrompt()).append("\n\n");

        sb.append("### 리뷰 포커스\n");
        sb.append(this.reviewFocus.getPrompt()).append("\n\n");

        sb.append("### 상세 수준\n");
        sb.append(this.detailLevel.getPrompt()).append("\n\n");

        if (this.rules != null && !this.rules.isEmpty()) {
            List<String> activeRules = this.rules.stream()
                    .filter(Rule::isEnabled)
                    .map(rule -> {
                        String prefix = (rule.getTargetFilePattern() != null && !rule.getTargetFilePattern().isBlank())
                                ? "[Target: " + rule.getTargetFilePattern() + "] "
                                : "";
                        return "- " + prefix + rule.getContent();
                    })
                    .toList();

            if (!activeRules.isEmpty()) {
                sb.append("### 코드 리뷰 규칙\n");
                sb.append("중요: 규칙 적용 시 반드시 아래 체크리스트를 따르세요:\n");
                sb.append("1. 해당 규칙의 Target 패턴이 변경된 파일과 매칭되는가?\n");
                sb.append("2. 변경된 코드(diff의 + 라인)에서 실제로 규칙 위반이 발견되었는가?\n");
                sb.append("3. 위 두 조건이 모두 YES일 때만 해당 규칙에 대한 코멘트를 작성하세요.\n");
                sb.append("4. 조건을 충족하지 않으면 규칙 관련 코멘트를 작성하지 마세요.\n\n");
                activeRules.forEach(rule -> sb.append(rule).append("\n"));
                sb.append("\n");
                sb.append("### 잘못된 리뷰 예시 (이렇게 하지 마세요)\n");
                sb.append("- Target 파일이지만 규칙 위반 코드가 없는데 '주의하세요' 류의 코멘트\n");
                sb.append("- 변경되지 않은 기존 코드에 대한 규칙 적용\n\n");
            }
        }

        sb.append("### 응답 형식 (매우 중요)\n");
        sb.append("반드시 아래 JSON 형식으로만 응답해 주세요. 마크다운 코드 블록(```json 등)도 포함하지 말고 오직 JSON 문자열만 반환하세요.\n");
        sb.append("판단은 diff와 repositoryTree를 함께 참고하되, comments에는 diff의 추가된 라인(+ 라인)에 직접 연결할 수 있는 지적만 포함하세요.\n");
        sb.append("path는 제공된 diff 상의 filename과 정확히 일치해야 합니다.\n");
        sb.append("라인 번호(line)는 작성하지 마세요. 대신 지적하고자 하는 추가 라인의 codeSnippet을 포함해 주세요.\n");
        sb.append("codeSnippet은 diff에 포함된 추가 라인 중 한 줄과 일치해야 하며, diff 표시용 '+' 문자는 제외하세요.\n");
        sb.append("수정되지 않은 라인, 삭제된 라인, repositoryTree만 보고 발견한 문제, 파일 전체 맥락이 더 필요한 문제는 comments가 아니라 generalReview에 포함하세요.\n");
        sb.append("{\n");
        sb.append("  \"generalReview\": \"전반적인 리뷰 요약 (한국어)\",\n");
        sb.append("  \"comments\": [\n");
        sb.append("    {\n");
        sb.append("      \"path\": \"src/main/java/Example.java\",\n");
        sb.append("      \"codeSnippet\": \"지적할 코드의 한 줄 (필수)\",\n");
        sb.append("      \"body\": \"코멘트 내용 (한국어)\"\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n");

        return sb.toString();
    }
}
