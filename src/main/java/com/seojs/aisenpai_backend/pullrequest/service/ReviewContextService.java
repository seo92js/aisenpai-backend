package com.seojs.aisenpai_backend.pullrequest.service;

import com.seojs.aisenpai_backend.github.dto.ChangedFileDto;
import com.seojs.aisenpai_backend.github.dto.GitTreeResponseDto;
import com.seojs.aisenpai_backend.github.dto.PullRequestInfoDto;
import com.seojs.aisenpai_backend.github.service.GithubService;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewContextDto;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewContextDto.ChangedFileContextDto;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewContextDto.ContentFetchStatus;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewContextDto.RepositoryTreeContextDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.FileSystems;
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
    public static final int MAX_TOTAL_CONTENT_CHARS = 80_000;
    public static final int MAX_TREE_CHARS = 10_000;
    public static final int MAX_CONTEXT_CHARS = 100_000;

    private static final List<String> GENERATED_OR_VENDOR_PATH_PARTS = List.of(
            "/node_modules/", "/vendor/", "/dist/", "/build/", "/target/", "/coverage/", "/.next/", "/out/");
    private static final List<String> BINARY_EXTENSIONS = List.of(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".pdf", ".zip", ".gz", ".tar", ".jar",
            ".class", ".wasm", ".woff", ".woff2", ".ttf", ".eot", ".mp4", ".mov", ".avi", ".mp3");

    private final GithubService githubService;

    public ReviewContextDto buildReviewContext(String accessToken, String owner, String repo, Integer prNumber,
            PullRequestInfoDto prInfo, List<ChangedFileDto> changedFiles, GitTreeResponseDto treeDto,
            List<String> ignorePatterns) {
        int[] usedContentChars = { 0 };
        int[] usedContextChars = { 0 };
        List<ChangedFileContextDto> fileContexts = new ArrayList<>();
        List<PathMatcher> ignoreMatchers = buildIgnoreMatchers(ignorePatterns);
        RepositoryTreeContextDto repositoryTree = buildRepositoryTreeContext(treeDto, usedContextChars);

        for (ChangedFileDto file : changedFiles) {
            fileContexts.add(buildChangedFileContext(accessToken, owner, repo, prInfo, file, ignoreMatchers,
                    usedContentChars, usedContextChars, fileContexts.size()));
        }

        return ReviewContextDto.builder()
                .pullRequest(buildPullRequestMeta(owner, repo, prNumber, prInfo))
                .changedFiles(fileContexts)
                .repositoryTree(repositoryTree)
                .relatedFiles(Collections.emptyList())
                .budget(ReviewContextDto.BudgetDto.builder()
                        .maxContextFiles(MAX_CONTEXT_FILES)
                        .maxPatchChars(MAX_PATCH_CHARS)
                        .maxFileContentChars(MAX_FILE_CONTENT_CHARS)
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
        if (isBinaryPath(filename)) {
            return "binary file";
        }
        if (isGeneratedOrVendorPath(normalizedPath)) {
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

    private List<PathMatcher> buildIgnoreMatchers(List<String> ignorePatterns) {
        if (ignorePatterns == null || ignorePatterns.isEmpty()) {
            return Collections.emptyList();
        }
        return ignorePatterns.stream()
                .filter(pattern -> pattern != null && !pattern.isBlank())
                .map(this::convertUserPatternToGlob)
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .toList();
    }

    private boolean matchesIgnorePattern(String filename, List<PathMatcher> ignoreMatchers) {
        return ignoreMatchers.stream().anyMatch(matcher -> matcher.matches(Paths.get(filename)));
    }

    private boolean isGeneratedOrVendorPath(String normalizedPath) {
        return GENERATED_OR_VENDOR_PATH_PARTS.stream().anyMatch(normalizedPath::contains);
    }

    private boolean isBinaryPath(String filename) {
        String lower = filename.toLowerCase();
        return BINARY_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    private String convertUserPatternToGlob(String pattern) {
        pattern = pattern.trim();
        boolean isDirectory = pattern.endsWith("/");
        if (isDirectory) {
            pattern = pattern.substring(0, pattern.length() - 1);
        }

        boolean isRooted = pattern.startsWith("/");
        if (isRooted) {
            pattern = pattern.substring(1);
        }

        boolean hasSlash = pattern.contains("/");
        StringBuilder glob = new StringBuilder();
        if (!isRooted && !hasSlash) {
            glob.append("{**/,}");
        }
        glob.append(pattern);
        if (isDirectory) {
            glob.append("/**");
        } else {
            glob.append("{,/**}");
        }
        return glob.toString();
    }

    private record ContentLimitResult(String content, boolean truncated, String reason) {
    }

    private int contextLength(String value) {
        return value != null ? value.length() : 0;
    }
}
