package com.seojs.aisenpai_backend.pullrequest.service;

import com.seojs.aisenpai_backend.github.dto.ChangedFileDto;
import com.seojs.aisenpai_backend.github.dto.GitTreeResponseDto;
import com.seojs.aisenpai_backend.github.dto.PullRequestInfoDto;
import com.seojs.aisenpai_backend.github.service.GithubService;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewContextDto;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewContextDto.ContentFetchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReviewContextServiceTest {

    @Mock
    private GithubService githubService;

    private ReviewContextService reviewContextService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        reviewContextService = new ReviewContextService(githubService, new RelatedFileCandidateService());
    }

    @Test
    void buildReviewContext_FetchesEligibleHeadContent() {
        // given
        PullRequestInfoDto prInfo = prInfo("base-sha", "head-sha");
        ChangedFileDto file = changedFile("src/App.tsx", "modified", "@@ -1 +1 @@\n+export const a = 1;");
        when(githubService.getFileContent("token", "owner", "repo", "src/App.tsx", "head-sha"))
                .thenReturn("export const a = 1;");

        // when
        ReviewContextDto context = reviewContextService.buildReviewContext("token", "owner", "repo", 1,
                prInfo, List.of(file), null, List.of());

        // then
        assertEquals("owner", context.getPullRequest().getOwner());
        assertEquals("head-sha", context.getPullRequest().getHeadSha());
        assertEquals(ContentFetchStatus.FETCHED, context.getChangedFiles().get(0).getContentFetchStatus());
        assertEquals("export const a = 1;", context.getChangedFiles().get(0).getHeadContent());
        assertEquals(0, context.getRelatedFiles().size());
    }

    @Test
    void buildReviewContext_SkipsIgnoredDeletedBinaryAndPatchlessFiles() {
        // given
        PullRequestInfoDto prInfo = prInfo("base-sha", "head-sha");
        List<ChangedFileDto> files = List.of(
                changedFile("package-lock.json", "modified", "@@ -1 +1 @@\n+{}"),
                changedFile("src/Old.java", "removed", "@@ -1 +0 @@\n-class Old {}"),
                changedFile("assets/logo.png", "modified", "@@ -1 +1 @@\n+binary"),
                changedFile("src/LargeGenerated.ts", "modified", null));

        // when
        ReviewContextDto context = reviewContextService.buildReviewContext("token", "owner", "repo", 1,
                prInfo, files, null, List.of("package-lock.json"));

        // then
        assertTrue(context.getChangedFiles().stream()
                .allMatch(file -> file.getContentFetchStatus() == ContentFetchStatus.SKIPPED));
        verify(githubService, never()).getFileContent(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void buildReviewContext_ContentFetchFailure_DoesNotFailContextBuild() {
        // given
        PullRequestInfoDto prInfo = prInfo("base-sha", "head-sha");
        ChangedFileDto file = changedFile("src/App.tsx", "modified", "@@ -1 +1 @@\n+export const a = 1;");
        when(githubService.getFileContent("token", "owner", "repo", "src/App.tsx", "head-sha"))
                .thenThrow(new RuntimeException("github failed"));

        // when
        ReviewContextDto context = reviewContextService.buildReviewContext("token", "owner", "repo", 1,
                prInfo, List.of(file), null, List.of());

        // then
        assertEquals(ContentFetchStatus.FAILED, context.getChangedFiles().get(0).getContentFetchStatus());
        assertEquals("content fetch failed", context.getChangedFiles().get(0).getContentSkipReason());
    }

    @Test
    void buildReviewContext_LargeContent_IsTruncated() {
        // given
        PullRequestInfoDto prInfo = prInfo("base-sha", "head-sha");
        ChangedFileDto file = changedFile("src/App.tsx", "modified", "@@ -1 +1 @@\n+export const a = 1;");
        when(githubService.getFileContent("token", "owner", "repo", "src/App.tsx", "head-sha"))
                .thenReturn("a".repeat(ReviewContextService.MAX_FILE_CONTENT_CHARS + 1));

        // when
        ReviewContextDto context = reviewContextService.buildReviewContext("token", "owner", "repo", 1,
                prInfo, List.of(file), null, List.of());

        // then
        assertTrue(context.getChangedFiles().get(0).isTruncated());
        assertEquals(ReviewContextService.MAX_FILE_CONTENT_CHARS,
                context.getChangedFiles().get(0).getHeadContent().length());
    }

    @Test
    void buildReviewContext_LargePatch_IsTruncated() {
        // given
        PullRequestInfoDto prInfo = prInfo("base-sha", "head-sha");
        ChangedFileDto file = changedFile("src/App.tsx", "modified",
                "@@ -1 +1 @@\n+" + "a".repeat(ReviewContextService.MAX_PATCH_CHARS + 1));
        when(githubService.getFileContent("token", "owner", "repo", "src/App.tsx", "head-sha"))
                .thenReturn("export const a = 1;");

        // when
        ReviewContextDto context = reviewContextService.buildReviewContext("token", "owner", "repo", 1,
                prInfo, List.of(file), null, List.of());

        // then
        assertTrue(context.getChangedFiles().get(0).isPatchTruncated());
        assertEquals(ReviewContextService.MAX_PATCH_CHARS, context.getChangedFiles().get(0).getPatch().length());
    }

    @Test
    void buildReviewContext_TreeSummary_IsLimited() {
        // given
        GitTreeResponseDto treeDto = new GitTreeResponseDto();
        GitTreeResponseDto.GitTreeItemDto item = new GitTreeResponseDto.GitTreeItemDto();
        item.setType("blob");
        item.setPath("src/" + "a".repeat(ReviewContextService.MAX_TREE_CHARS) + ".java");
        treeDto.setTree(List.of(item));

        // when
        ReviewContextDto context = reviewContextService.buildReviewContext("token", "owner", "repo", 1,
                prInfo("base-sha", "head-sha"), List.of(), treeDto, List.of());

        // then
        assertTrue(context.getRepositoryTree().isTruncated());
        assertEquals("", context.getRepositoryTree().getSummary());
    }

    @Test
    void buildReviewContext_IncludesLimitedRelatedFileContent() {
        // given
        PullRequestInfoDto prInfo = prInfo("base-sha", "head-sha");
        ChangedFileDto file = changedFile("src/App.tsx", "modified",
                "@@ -1 +1 @@\n+import { AppProps } from './App.types';");
        GitTreeResponseDto treeDto = tree("src/App.tsx", "src/App.types.ts", "src/App.css");

        when(githubService.getFileContent("token", "owner", "repo", "src/App.tsx", "head-sha"))
                .thenReturn("import { AppProps } from './App.types';");
        when(githubService.getFileContent("token", "owner", "repo", "src/App.types.ts", "head-sha"))
                .thenReturn("export interface AppProps { name: string; }");
        when(githubService.getFileContent("token", "owner", "repo", "src/App.css", "head-sha"))
                .thenReturn(".app { color: red; }");

        // when
        ReviewContextDto context = reviewContextService.buildReviewContext("token", "owner", "repo", 1,
                prInfo, List.of(file), treeDto, List.of());

        // then
        assertEquals(2, context.getRelatedFiles().size());
        assertEquals("src/App.types.ts", context.getRelatedFiles().get(0).getPath());
        assertEquals(ContentFetchStatus.FETCHED, context.getRelatedFiles().get(0).getContentFetchStatus());
        assertEquals(RelatedFileCandidateService.MAX_RELATED_FILES, context.getBudget().getMaxRelatedFiles());
        assertEquals(ReviewContextService.MAX_RELATED_FILE_CONTENT_CHARS,
                context.getBudget().getMaxRelatedFileContentChars());
    }

    @Test
    void buildReviewContext_RelatedFileFetchFailure_DoesNotFailContextBuild() {
        // given
        PullRequestInfoDto prInfo = prInfo("base-sha", "head-sha");
        ChangedFileDto file = changedFile("src/App.tsx", "modified", "@@ -1 +1 @@\n+export function App() {}");
        GitTreeResponseDto treeDto = tree("src/App.tsx", "src/App.css");

        when(githubService.getFileContent("token", "owner", "repo", "src/App.tsx", "head-sha"))
                .thenReturn("export function App() {}");
        when(githubService.getFileContent("token", "owner", "repo", "src/App.css", "head-sha"))
                .thenThrow(new RuntimeException("github failed"));

        // when
        ReviewContextDto context = reviewContextService.buildReviewContext("token", "owner", "repo", 1,
                prInfo, List.of(file), treeDto, List.of());

        // then
        assertEquals(1, context.getRelatedFiles().size());
        assertEquals(ContentFetchStatus.FAILED, context.getRelatedFiles().get(0).getContentFetchStatus());
        assertEquals("content fetch failed", context.getRelatedFiles().get(0).getContentSkipReason());
    }

    @Test
    void buildReviewContext_RelatedFileContent_IsTruncated() {
        // given
        PullRequestInfoDto prInfo = prInfo("base-sha", "head-sha");
        ChangedFileDto file = changedFile("src/App.tsx", "modified", "@@ -1 +1 @@\n+export function App() {}");
        GitTreeResponseDto treeDto = tree("src/App.tsx", "src/App.css");

        when(githubService.getFileContent("token", "owner", "repo", "src/App.tsx", "head-sha"))
                .thenReturn("export function App() {}");
        when(githubService.getFileContent("token", "owner", "repo", "src/App.css", "head-sha"))
                .thenReturn("a".repeat(ReviewContextService.MAX_RELATED_FILE_CONTENT_CHARS + 1));

        // when
        ReviewContextDto context = reviewContextService.buildReviewContext("token", "owner", "repo", 1,
                prInfo, List.of(file), treeDto, List.of());

        // then
        assertTrue(context.getRelatedFiles().get(0).isTruncated());
        assertEquals(ReviewContextService.MAX_RELATED_FILE_CONTENT_CHARS,
                context.getRelatedFiles().get(0).getContent().length());
    }

    private PullRequestInfoDto prInfo(String baseSha, String headSha) {
        PullRequestInfoDto prInfo = new PullRequestInfoDto();
        PullRequestInfoDto.PullRequestRefDto base = new PullRequestInfoDto.PullRequestRefDto();
        base.setRef("main");
        base.setSha(baseSha);
        PullRequestInfoDto.PullRequestRefDto head = new PullRequestInfoDto.PullRequestRefDto();
        head.setRef("feature");
        head.setSha(headSha);
        prInfo.setBase(base);
        prInfo.setHead(head);
        return prInfo;
    }

    private ChangedFileDto changedFile(String filename, String status, String patch) {
        return new ChangedFileDto(filename, status, 1, 0, 1, 10, "sha", "blob", "raw", "contents", patch);
    }

    private GitTreeResponseDto tree(String... paths) {
        GitTreeResponseDto treeDto = new GitTreeResponseDto();
        treeDto.setTree(List.of(paths).stream().map(this::treeItem).toList());
        return treeDto;
    }

    private GitTreeResponseDto.GitTreeItemDto treeItem(String path) {
        GitTreeResponseDto.GitTreeItemDto item = new GitTreeResponseDto.GitTreeItemDto();
        item.setType("blob");
        item.setPath(path);
        return item;
    }
}
