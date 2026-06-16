package com.seojs.aisenpai_backend.pullrequest.service;

import com.seojs.aisenpai_backend.github.dto.ChangedFileDto;
import com.seojs.aisenpai_backend.github.dto.GitTreeResponseDto;
import com.seojs.aisenpai_backend.github.dto.PullRequestInfoDto;
import com.seojs.aisenpai_backend.github.service.GithubService;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewContextDto;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewContextDto.ChangedFileContextDto;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewContextDto.ContentFetchStatus;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewContextDto.RelatedFileContextDto;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewContextDto.RepositoryTreeContextDto;
import com.seojs.aisenpai_backend.pullrequest.service.RelatedFileCandidateService.RelatedFileCandidate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewContextService {
    public static final int MAX_CONTEXT_FILES = 12;
    public static final int MAX_PATCH_CHARS = 12_000;
    public static final int MAX_FILE_CONTENT_CHARS = 12_000;
    public static final int MAX_RELATED_FILE_CONTENT_CHARS = 6_000;
    public static final int MAX_TOTAL_CONTENT_CHARS = 80_000;
    public static final int MAX_TREE_CHARS = 10_000;
    public static final int MAX_CONTEXT_CHARS = 100_000;

    private final GithubService githubService;
    private final RelatedFileCandidateService relatedFileCandidateService;

    public ReviewContextDto buildReviewContext(String accessToken, String owner, String repo, Integer prNumber,
            PullRequestInfoDto prInfo, List<ChangedFileDto> changedFiles, GitTreeResponseDto treeDto,
            List<String> ignorePatterns) {
        return buildReviewContext(null, accessToken, owner, repo, prNumber, prInfo, changedFiles, treeDto, ignorePatterns);
    }

    public ReviewContextDto buildReviewContext(Long repositoryId, String accessToken, String owner, String repo, Integer prNumber,
            PullRequestInfoDto prInfo, List<ChangedFileDto> changedFiles, GitTreeResponseDto treeDto,
            List<String> ignorePatterns) {
        int[] usedContentChars = { 0 };
        int[] usedContextChars = { 0 };
        List<ChangedFileContextDto> fileContexts = new ArrayList<>();
        List<PathMatcher> ignoreMatchers = ReviewPathUtils.buildIgnoreMatchers(ignorePatterns);
        RepositoryTreeContextDto repositoryTree = buildRepositoryTreeContext(treeDto, usedContextChars);

        for (ChangedFileDto file : changedFiles) {
            fileContexts.add(buildChangedFileContext(accessToken, owner, repo, prInfo, file, ignoreMatchers,
                    usedContentChars, usedContextChars, fileContexts.size()));
        }
        List<RelatedFileContextDto> relatedFiles = buildRelatedFileContexts(repositoryId, accessToken, owner, repo, prInfo,
                fileContexts, treeDto, ignorePatterns, usedContentChars, usedContextChars);

        long skippedChangedFiles = fileContexts.stream()
                .filter(file -> file.getContentFetchStatus() == ContentFetchStatus.SKIPPED)
                .count();
        long failedChangedFiles = fileContexts.stream()
                .filter(file -> file.getContentFetchStatus() == ContentFetchStatus.FAILED)
                .count();
        long missingPatchFiles = fileContexts.stream()
                .filter(file -> "missing patch".equals(file.getContentSkipReason()))
                .count();
        long truncatedChangedFiles = fileContexts.stream()
                .filter(file -> file.isTruncated() || file.isPatchTruncated())
                .count();
        log.info("Review context collected. changed={}, related={}, skippedChanged={}, failedChanged={}, "
                        + "missingPatch={}, truncatedChanged={}, usedContentChars={}, usedContextChars={}",
                fileContexts.size(), relatedFiles.size(), skippedChangedFiles, failedChangedFiles, missingPatchFiles,
                truncatedChangedFiles, usedContentChars[0], usedContextChars[0]);
        if (truncatedChangedFiles > 0) {
            log.info("Truncated changed review context files: {}", summarizeTruncatedChangedFiles(fileContexts));
        }

        return ReviewContextDto.builder()
                .pullRequest(buildPullRequestMeta(owner, repo, prNumber, prInfo))
                .changedFiles(fileContexts)
                .repositoryTree(repositoryTree)
                .relatedFiles(relatedFiles)
                .budget(ReviewContextDto.BudgetDto.builder()
                        .maxContextFiles(MAX_CONTEXT_FILES)
                        .maxPatchChars(MAX_PATCH_CHARS)
                        .maxFileContentChars(MAX_FILE_CONTENT_CHARS)
                        .maxRelatedFiles(RelatedFileCandidateService.MAX_RELATED_FILES)
                        .maxRelatedFileContentChars(MAX_RELATED_FILE_CONTENT_CHARS)
                        .maxTotalContentChars(MAX_TOTAL_CONTENT_CHARS)
                        .maxTreeChars(MAX_TREE_CHARS)
                        .maxContextChars(MAX_CONTEXT_CHARS)
                        .usedContentChars(usedContentChars[0])
                        .usedContextChars(usedContextChars[0])
                        .build())
                .build();
    }

    private ReviewContextDto.PullRequestMetaDto buildPullRequestMeta(String owner, String repo, Integer prNumber,
            PullRequestInfoDto prInfo) {
        PullRequestInfoDto.PullRequestRefDto head = prInfo != null ? prInfo.getHead() : null;
        PullRequestInfoDto.PullRequestRefDto base = prInfo != null ? prInfo.getBase() : null;

        return ReviewContextDto.PullRequestMetaDto.builder()
                .owner(owner)
                .repo(repo)
                .prNumber(prNumber)
                .headSha(head != null ? head.getSha() : null)
                .headRef(head != null ? head.getRef() : null)
                .baseSha(base != null ? base.getSha() : null)
                .baseRef(base != null ? base.getRef() : null)
                .build();
    }

    private ChangedFileContextDto buildChangedFileContext(String accessToken, String owner, String repo,
            PullRequestInfoDto prInfo, ChangedFileDto file, List<PathMatcher> ignoreMatchers, int[] usedContentChars,
            int[] usedContextChars, int fileIndex) {
        String skipReason = resolveSkipReason(file, ignoreMatchers, fileIndex);
        int metadataChars = contextLength(file.getFilename()) + contextLength(file.getStatus());
        ContentLimitResult patchLimitResult = applyPatchBudget(file.getPatch(), usedContextChars[0] + metadataChars);
        String patch = patchLimitResult.content();
        usedContextChars[0] += metadataChars + contextLength(patch);
        if (skipReason != null) {
            return ChangedFileContextDto.fromChangedFile(file, patch, patchLimitResult.truncated(), null,
                    ContentFetchStatus.SKIPPED, skipReason, false);
        }
        if (patch == null && patchLimitResult.reason() != null) {
            return ChangedFileContextDto.fromChangedFile(file, null, patchLimitResult.truncated(), null,
                    ContentFetchStatus.SKIPPED, patchLimitResult.reason(), false);
        }

        try {
            String headSha = prInfo != null && prInfo.getHead() != null && prInfo.getHead().getSha() != null
                    ? prInfo.getHead().getSha()
                    : "HEAD";
            String headContent = githubService.getFileContent(accessToken, owner, repo, file.getFilename(), headSha);
            if (headContent == null) {
                return ChangedFileContextDto.fromChangedFile(file, patch, patchLimitResult.truncated(), null,
                        ContentFetchStatus.SKIPPED,
                        "empty content", false);
            }

            ContentLimitResult limitResult = applyContentBudget(headContent, usedContentChars[0], usedContextChars[0]);
            if (limitResult.content() == null) {
                return ChangedFileContextDto.fromChangedFile(file, patch, patchLimitResult.truncated(), null,
                        ContentFetchStatus.SKIPPED,
                        limitResult.reason(), false);
            }

            usedContentChars[0] += limitResult.content().length();
            usedContextChars[0] += limitResult.content().length();
            return ChangedFileContextDto.fromChangedFile(file, patch, patchLimitResult.truncated(),
                    limitResult.content(), ContentFetchStatus.FETCHED,
                    limitResult.reason(), limitResult.truncated());
        } catch (Exception e) {
            log.warn("Failed to fetch review context content for {}: {}", file.getFilename(), e.getMessage());
            return ChangedFileContextDto.fromChangedFile(file, patch, patchLimitResult.truncated(), null,
                    ContentFetchStatus.FAILED,
                    "content fetch failed", false);
        }
    }

    private List<RelatedFileContextDto> buildRelatedFileContexts(Long repositoryId, String accessToken, String owner, String repo,
            PullRequestInfoDto prInfo, List<ChangedFileContextDto> changedFileContexts, GitTreeResponseDto treeDto,
            List<String> ignorePatterns, int[] usedContentChars, int[] usedContextChars) {
        List<RelatedFileCandidate> candidates = relatedFileCandidateService.findCandidates(repositoryId, changedFileContexts,
                treeDto, ignorePatterns);
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        String headSha = prInfo != null && prInfo.getHead() != null && prInfo.getHead().getSha() != null
                ? prInfo.getHead().getSha()
                : "HEAD";
        List<RelatedFileContextDto> relatedFiles = new ArrayList<>();
        int skippedCount = 0;
        int truncatedCount = 0;
        for (RelatedFileCandidate candidate : candidates) {
            try {
                String content = githubService.getFileContent(accessToken, owner, repo, candidate.path(), headSha);
                if (content == null) {
                    skippedCount++;
                    relatedFiles.add(buildRelatedFileContext(candidate, headSha, null, ContentFetchStatus.SKIPPED,
                            "empty content", false));
                    continue;
                }

                ContentLimitResult limitResult = applyRelatedContentBudget(content, usedContentChars[0],
                        usedContextChars[0]);
                if (limitResult.content() == null) {
                    skippedCount++;
                    relatedFiles.add(buildRelatedFileContext(candidate, headSha, null, ContentFetchStatus.SKIPPED,
                            limitResult.reason(), false));
                    continue;
                }

                if (limitResult.truncated()) {
                    truncatedCount++;
                }
                usedContentChars[0] += limitResult.content().length();
                usedContextChars[0] += candidate.path().length() + candidate.reason().length()
                        + limitResult.content().length();
                relatedFiles.add(buildRelatedFileContext(candidate, headSha, limitResult.content(),
                        ContentFetchStatus.FETCHED, limitResult.reason(), limitResult.truncated()));
            } catch (Exception e) {
                skippedCount++;
                log.warn("Failed to fetch related review context content for {}: {}", candidate.path(),
                        e.getMessage());
                relatedFiles.add(buildRelatedFileContext(candidate, headSha, null, ContentFetchStatus.FAILED,
                        "content fetch failed", false));
            }
        }

        log.info("Related context collected. candidates={}, related={}, skipped={}, truncated={}",
                candidates.size(), relatedFiles.size(), skippedCount, truncatedCount);
        return relatedFiles;
    }

    private RelatedFileContextDto buildRelatedFileContext(RelatedFileCandidate candidate, String ref, String content,
            ContentFetchStatus status, String skipReason, boolean truncated) {
        return RelatedFileContextDto.builder()
                .path(candidate.path())
                .ref(ref)
                .content(content)
                .reason(candidate.reason())
                .contentFetchStatus(status)
                .contentSkipReason(skipReason)
                .truncated(truncated)
                .build();
    }

    private String resolveSkipReason(ChangedFileDto file, List<PathMatcher> ignoreMatchers, int fileIndex) {
        String filename = file.getFilename();
        String normalizedPath = "/" + filename;

        if (fileIndex >= MAX_CONTEXT_FILES) {
            return "context file count limit exceeded";
        }
        if ("removed".equals(file.getStatus())) {
            return "removed file";
        }
        if ("renamed".equals(file.getStatus())) {
            return "renamed file";
        }
        if (file.getPatch() == null || file.getPatch().isBlank()) {
            return "missing patch";
        }
        if (ReviewPathUtils.isBinaryPath(filename)) {
            return "binary file";
        }
        if (ReviewPathUtils.isGeneratedOrVendorPath(normalizedPath)) {
            return "generated/vendor/build output";
        }
        if (matchesIgnorePattern(filename, ignoreMatchers)) {
            return "ignored by review settings";
        }

        return null;
    }

    private ContentLimitResult applyContentBudget(String content, int usedContentChars, int usedContextChars) {
        int remainingTotal = MAX_TOTAL_CONTENT_CHARS - usedContentChars;
        int remainingContext = MAX_CONTEXT_CHARS - usedContextChars;
        int remaining = Math.min(remainingTotal, remainingContext);
        if (remaining <= 0) {
            return new ContentLimitResult(null, false, "context budget exceeded");
        }

        int allowedChars = Math.min(MAX_FILE_CONTENT_CHARS, remaining);
        if (content.length() > allowedChars) {
            return new ContentLimitResult(content.substring(0, allowedChars), true, "content truncated by budget");
        }

        return new ContentLimitResult(content, false, null);
    }

    private ContentLimitResult applyRelatedContentBudget(String content, int usedContentChars, int usedContextChars) {
        int remainingTotal = MAX_TOTAL_CONTENT_CHARS - usedContentChars;
        int remainingContext = MAX_CONTEXT_CHARS - usedContextChars;
        int remaining = Math.min(remainingTotal, remainingContext);
        if (remaining <= 0) {
            return new ContentLimitResult(null, false, "context budget exceeded");
        }

        int allowedChars = Math.min(MAX_RELATED_FILE_CONTENT_CHARS, remaining);
        if (content.length() > allowedChars) {
            return new ContentLimitResult(content.substring(0, allowedChars), true,
                    "related content truncated by budget");
        }

        return new ContentLimitResult(content, false, null);
    }

    private ContentLimitResult applyPatchBudget(String patch, int usedContextChars) {
        if (patch == null) {
            return new ContentLimitResult(null, false, null);
        }

        int remainingContext = MAX_CONTEXT_CHARS - usedContextChars;
        if (remainingContext <= 0) {
            return new ContentLimitResult(null, true, "context budget exceeded");
        }

        int allowedChars = Math.min(MAX_PATCH_CHARS, remainingContext);
        if (patch.length() > allowedChars) {
            return new ContentLimitResult(patch.substring(0, allowedChars), true, "patch truncated by budget");
        }

        return new ContentLimitResult(patch, false, null);
    }

    private RepositoryTreeContextDto buildRepositoryTreeContext(GitTreeResponseDto treeDto, int[] usedContextChars) {
        if (treeDto == null || treeDto.getTree() == null) {
            return RepositoryTreeContextDto.builder()
                    .summary(null)
                    .truncated(false)
                    .build();
        }

        StringBuilder summary = new StringBuilder();
        boolean truncated = Boolean.TRUE.equals(treeDto.getTruncated());
        for (GitTreeResponseDto.GitTreeItemDto item : treeDto.getTree()) {
            String line = "- " + item.getType() + ": " + item.getPath() + "\n";
            if (summary.length() + line.length() > MAX_TREE_CHARS) {
                truncated = true;
                break;
            }
            summary.append(line);
        }
        usedContextChars[0] += summary.length();

        return RepositoryTreeContextDto.builder()
                .summary(summary.toString())
                .truncated(truncated)
                .build();
    }

    private boolean matchesIgnorePattern(String filename, List<PathMatcher> ignoreMatchers) {
        return ignoreMatchers.stream().anyMatch(matcher -> matcher.matches(Paths.get(filename)));
    }

    private List<String> summarizeTruncatedChangedFiles(List<ChangedFileContextDto> fileContexts) {
        return fileContexts.stream()
                .filter(file -> file.isTruncated() || file.isPatchTruncated())
                .map(file -> file.getFilename()
                        + "(patchTruncated=" + file.isPatchTruncated()
                        + ", contentTruncated=" + file.isTruncated()
                        + ", reason=" + file.getContentSkipReason() + ")")
                .toList();
    }

    private record ContentLimitResult(String content, boolean truncated, String reason) {
    }

    private int contextLength(String value) {
        return value != null ? value.length() : 0;
    }
}
