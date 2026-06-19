package com.seojs.aisenpai_backend.github.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@Getter
@NoArgsConstructor
@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_repository_ai_settings_repo", columnNames = "repository_id"))
public class RepositoryAiSettings {

    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final String DEFAULT_IGNORE_PATTERNS = "package-lock.json, yarn.lock, *.lock, .env*, *.pem, *.key, .yml, .yaml";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repository_id", nullable = false)
    private Long repositoryId;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String repositoryName;

    @Column(nullable = false)
    private String webhookSecret;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "webhook_registered_by_id")
    private GithubAccount webhookRegisteredBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "posting_account_id")
    private GithubAccount postingAccount;

    private String postingAccountLogin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewTone reviewTone = ReviewTone.NEUTRAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewFocus reviewFocus = ReviewFocus.BOTH;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DetailLevel detailLevel = DetailLevel.STANDARD;

    @OneToMany(mappedBy = "repositorySettings", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Rule> rules = new ArrayList<>();

    @Column(length = 1000)
    private String ignorePatterns = DEFAULT_IGNORE_PATTERNS;

    @Column
    private String openAiKey;

    @Column(nullable = false)
    private Boolean autoReviewEnabled = false;

    @Column(nullable = false)
    private Boolean autoPostToGithub = false;

    @Column(nullable = false)
    private String openaiModel = DEFAULT_MODEL;

    @Builder
    public RepositoryAiSettings(Long repositoryId, String owner, String repositoryName, String webhookSecret,
            GithubAccount webhookRegisteredBy, GithubAccount postingAccount) {
        this.repositoryId = repositoryId;
        this.owner = owner;
        this.repositoryName = repositoryName;
        this.webhookSecret = webhookSecret;
        this.webhookRegisteredBy = webhookRegisteredBy;
        this.postingAccount = postingAccount;
        this.postingAccountLogin = postingAccount != null ? postingAccount.getLoginId() : null;
    }

    public void updateRepository(String owner, String repositoryName) {
        this.owner = owner;
        this.repositoryName = repositoryName;
    }

    public void updateWebhookRegistration(GithubAccount account, String webhookSecret) {
        this.webhookRegisteredBy = account;
        this.postingAccount = account;
        this.postingAccountLogin = account != null ? account.getLoginId() : null;
        this.webhookSecret = webhookSecret;
    }

    public void updateReviewSettings(ReviewTone tone, ReviewFocus focus, DetailLevel detailLevel,
            Boolean autoReviewEnabled, Boolean autoPostToGithub, String openaiModel) {
        this.reviewTone = tone != null ? tone : ReviewTone.NEUTRAL;
        this.reviewFocus = focus != null ? focus : ReviewFocus.BOTH;
        this.detailLevel = detailLevel != null ? detailLevel : DetailLevel.STANDARD;
        this.autoReviewEnabled = autoReviewEnabled != null ? autoReviewEnabled : false;
        this.autoPostToGithub = autoPostToGithub != null ? autoPostToGithub : false;
        this.openaiModel = openaiModel != null ? openaiModel : DEFAULT_MODEL;
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

    public List<Rule> getActiveRules() {
        if (this.rules == null) {
            return List.of();
        }
        return this.rules.stream()
                .filter(Rule::isEnabled)
                .toList();
    }

    public String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 시니어 코드 리뷰어입니다.\n\n");
        sb.append("### 핵심 리뷰 원칙\n");
        sb.append("1. **임팩트 중심**: 보안 취약점, 데이터 유실/오염, 런타임 예외, 동시성 이슈, API 깨짐, 성능 저하 등 실제 위험을 초래하는 결함을 최우선으로 검출하십시오.\n");
        sb.append("2. **객관성과 팩트 근거**: 모든 지적은 오직 제공된 diff의 추가된 라인(+)에서 명백히 확인되는 실체적 문제만을 기반으로 해야 합니다. 추측, 가정, 가상 시나리오를 전제로 경고하지 마십시오.\n");
        sb.append("3. **소음 제거**: 단순 코딩 스타일, 개인적 취향(네이밍), 사소한 포맷팅 등 명확한 버그가 유발되지 않는 항목은 지적 대상에서 제외합니다.\n");
        sb.append("4. **기존 처리 존중**: 트랜잭션, 검증, 예외 처리, 권한 체크 등이 이미 변경점 외부나 기존 코드에 적절히 구현되어 있다면 추가적인 작성을 요구하지 마십시오. diff 내의 테스트, lock, 검증 코드가 우려를 이미 해결하고 있는지 먼저 평가하십시오.\n");
        sb.append("5. **코멘트 완결성**: 생성하는 모든 코멘트는 아래 3가지 요소를 완벽히 포함해야 합니다.\n");
        sb.append("   - [조건] 결함이 발생하는 실제 런타임/로직 상의 트리거 조건\n");
        sb.append("   - [영향] 결함이 서비스나 사용자에게 미치는 구체적인 임팩트\n");
        sb.append("   - [대안] 문제를 해결할 수 있는 명확한 수정 코드 방향\n\n");

        sb.append("### 리뷰 톤\n");
        sb.append(this.reviewTone.getPrompt()).append("\n\n");

        sb.append("### 리뷰 포커스\n");
        sb.append(this.reviewFocus.getPrompt()).append("\n\n");

        sb.append("### 상세 수준\n");
        sb.append(this.detailLevel.getPrompt()).append("\n\n");

        List<String> activeRules = getActiveRules().stream()
                .map(rule -> {
                    String prefix = (rule.getTargetFilePattern() != null && !rule.getTargetFilePattern().isBlank())
                            ? "[Target: " + rule.getTargetFilePattern() + "] "
                            : "";
                    return "- " + prefix + rule.getContent();
                })
                .toList();

        if (!activeRules.isEmpty()) {
            sb.append("### 코드 리뷰 규칙\n");
            sb.append("중요: 아래 두 조건이 모두 충족될 때만 해당 규칙 코멘트를 작성하십시오:\n");
            sb.append("1. 규칙의 Target 패턴이 변경된 파일명과 정확히 매칭됨\n");
            sb.append("2. 변경된 코드(diff의 + 라인) 내에서 실제로 해당 규칙 위반이 실체화됨\n\n");
            activeRules.forEach(rule -> sb.append(rule).append("\n"));
            sb.append("\n");
        }

        sb.append("### 응답 형식 및 연동 규칙\n");
        sb.append("반드시 반환하는 JSON 스키마 규격을 엄격하게 준수하십시오.\n");
        sb.append("- comments 배열 내 'path'는 diff에 명시된 파일 경로와 정확히 일치해야 합니다.\n");
        sb.append("- 'codeSnippet'은 지적 대상이 되는 추가된 소스 코드 라인을 원본 텍스트 그대로 가져오되, 맨 앞의 '+' 기호는 제외하십시오.\n");
        sb.append("- 'body'가 위의 '코멘트 완결성(조건-영향-대안)' 기준을 충족하지 못하면 해당 코멘트는 생성하지 마십시오.\n");
        sb.append("- generalReview 필드는 비워두십시오(서버가 별도 생성).\n");
        sb.append("- 테스트 파일이 diff에 직접 포함되지 않았다면 테스트 파일 부재나 테스트 누락을 단정하여 지적하지 마십시오.\n");
        sb.append("- 지적할 결함이 존재하지 않으면 comments를 빈 배열( [] )로 지정하십시오.\n");

        return sb.toString();
    }
}
