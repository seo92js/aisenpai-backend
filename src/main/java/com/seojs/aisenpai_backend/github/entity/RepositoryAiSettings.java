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
        sb.append("이번 PR의 변경으로 새로 생긴 실제 위험을 우선적으로 찾습니다.\n");
        sb.append("리뷰 우선순위는 버그, 보안, 데이터 손실, 권한 문제, 동시성 문제, 예외 처리 누락, API 계약 깨짐, 성능 회귀, 유지보수성입니다.\n");
        sb.append("단순 취향, 포맷팅, 사소한 네이밍은 명확한 리스크가 없으면 지적하지 마세요.\n");
        sb.append("확실하지 않은 문제는 단정하지 말고, 근거 없는 코멘트를 만들지 마세요.\n");
        sb.append("일반적인 주의사항, 권장사항, 가능성 언급, 스타일 의견은 작성하지 마세요.\n");
        sb.append("반드시 제공된 diff와 reviewContext 코드에서 명백하게 확인되는 문제만 리뷰하세요.\n");
        sb.append("코드 근거 없이 \"필요합니다\", \"주의가 필요합니다\", \"발생할 수 있습니다\"처럼 추측성 표현으로 comment를 작성하지 마세요.\n");
        sb.append("\"검토 필요\", \"확인 필요\", \"가능성 있음\", \"복잡해질 수 있음\"처럼 실제 결함을 설명하지 않는 추상 코멘트는 작성하지 마세요.\n");
        sb.append("각 comment는 실제 실패 조건, 사용자나 시스템에 미치는 영향, 구체적 수정 방향을 모두 포함해야 합니다.\n");
        sb.append("새 필드, 분기, 메서드, 클래스가 추가되었다는 사실만으로 문제라고 지적하지 마세요.\n");
        sb.append("이미 코드에 존재하는 처리(예: 트랜잭션, 검증, 예외 처리, 권한 체크)를 요구하지 마세요.\n");
        sb.append("테스트, lock, 검증, 예외 처리가 diff에 포함되어 있으면 그 코드가 우려를 이미 해결하는지 먼저 확인하세요.\n");
        sb.append("주어진 변경된 파일 목록과 저장소 구조 정보는 변경 이해를 위한 보조 자료로 사용하세요.\n\n");
        sb.append("중요: 아래의 diff 근거 원칙과 응답 형식 규칙은 리뷰 톤, 포커스, 상세 수준보다 우선합니다.\n\n");

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
            sb.append("중요: 규칙 적용 시 반드시 아래 체크리스트를 따르세요:\n");
            sb.append("1. 해당 규칙의 Target 패턴이 변경된 파일과 매칭되는가?\n");
            sb.append("2. 변경된 코드(diff의 + 라인)에서 실제로 규칙 위반이 발견되었는가?\n");
            sb.append("3. 위 두 조건이 모두 YES일 때만 해당 규칙에 대한 코멘트를 작성하세요.\n");
            sb.append("4. 조건을 충족하지 않으면 규칙 관련 코멘트를 작성하지 마세요.\n\n");
            activeRules.forEach(rule -> sb.append(rule).append("\n"));
            sb.append("\n");
            sb.append("### 잘못된 리뷰 예시 (이렇게 하지 마세요)\n");
            sb.append("- Target 파일이지만 규칙 위반 코드가 없는데 '주의하세요' 류의 코멘트\n");
            sb.append("- 변경되지 않은 기존 코드에 대한 규칙 적용\n");
            sb.append("- [추측성 지적] 버그가 확인되지 않았는데 \"데이터 검증이 부족할 경우 위험이 발생할 수 있습니다\"와 같이 가정을 전제로 경고하는 행위\n");
            sb.append("- [단위 테스트 요구] 기능 오류가 없음에도 \"단위 테스트를 추가하여 정확성을 높이는 것이 필요합니다\" 등 테스트 작성을 요구하는 행위\n");
            sb.append("- [기술/라이브러리 스펙 오해] 예: React Query에서 'enabled' 조건으로 API 호출을 제어하고 있음에도 \"null일 경우 런타임 에러가 발생합니다\"라고 경고하는 행위\n\n");
        }

        sb.append("### 응답 형식 (매우 중요)\n");
        sb.append("반드시 아래 JSON 형식으로만 응답해 주세요. 마크다운 코드 블록(```json 등)도 포함하지 말고 오직 JSON 문자열만 반환하세요.\n");
        sb.append("입력 JSON은 reviewContext.changedFiles 또는 changedFiles에 변경 파일과 patch를 제공합니다.\n");
        sb.append("판단은 diff, repositoryTree, reviewContext의 파일 내용을 함께 참고하되, comments에는 changedFiles.patch의 추가된 라인(+ 라인)에 직접 연결할 수 있는 지적만 포함하세요.\n");
        sb.append("path는 제공된 diff 상의 filename과 정확히 일치해야 합니다.\n");
        sb.append("라인 번호(line)는 작성하지 마세요. 대신 지적하고자 하는 추가 라인의 codeSnippet을 포함해 주세요.\n");
        sb.append("codeSnippet은 diff에 포함된 추가 라인 중 한 줄과 일치해야 하며, diff 표시용 '+' 문자는 제외하세요.\n");
        sb.append("codeSnippet에는 여러 줄을 넣지 마세요. 여러 줄 맥락이 필요하면 가장 직접적인 추가 라인 한 줄만 선택하세요.\n");
        sb.append("body가 실제 실패 조건, 영향, 수정 방향을 모두 설명하지 못하면 comment를 만들지 마세요.\n");
        sb.append("수정되지 않은 라인, 삭제된 라인, repositoryTree나 relatedFiles만 보고 추정한 문제, 파일 전체 맥락이 더 필요한 문제는 comments와 generalReview 모두에 지적 사항으로 작성하지 마세요.\n");
        sb.append("generalReview에는 finding이나 문제 제기를 작성하지 마세요. 실제 게시용 generalReview는 서버가 검증 결과를 기준으로 생성하므로 빈 문자열로 두어도 됩니다.\n");
        sb.append("지적할 내용이 없으면 comments는 빈 배열로 두세요.\n");
        sb.append("테스트 파일이 diff에 직접 포함되지 않았다면 테스트 파일 부재나 테스트 누락을 단정하지 마세요.\n");
        sb.append("{\n");
        sb.append("  \"generalReview\": \"\",\n");
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
