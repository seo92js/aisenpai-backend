package com.seojs.aisenpai_backend.pullrequest.service;

import com.seojs.aisenpai_backend.github.dto.GitTreeResponseDto;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewContextDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelatedFileCandidateServiceTest {
    private final RelatedFileCandidateService service = new RelatedFileCandidateService();

    @Test
    void findCandidates_UsesReferenceBasenameAndDirectorySignals() {
        // given
        var changedFile = changedFile("src/components/App.tsx",
                "import { AppProps } from \"./App.types\";\nexport function App() {}");
        GitTreeResponseDto tree = tree(
                "src/components/App.tsx",
                "src/components/App.types.ts",
                "src/components/App.css",
                "src/components/Button.tsx");

        // when
        var candidates = service.findCandidates(List.of(changedFile), tree, List.of());

        // then
        assertEquals(2, candidates.size());
        assertEquals("src/components/App.types.ts", candidates.get(0).path());
        assertEquals("src/components/App.css", candidates.get(1).path());
    }

    @Test
    void findCandidates_ExcludesTestsIgnoredGeneratedAndChangedFiles() {
        // given
        var changedFile = changedFile("src/App.ts", "import helper from \"./helper\";");
        GitTreeResponseDto tree = tree(
                "src/App.ts",
                "src/helper.ts",
                "src/helper.test.ts",
                "src/__tests__/helper.ts",
                "dist/helper.ts",
                "src/ignored.ts");

        // when
        var candidates = service.findCandidates(List.of(changedFile), tree, List.of("src/ignored.ts"));

        // then
        assertEquals(List.of("src/helper.ts"), candidates.stream().map(RelatedFileCandidateService.RelatedFileCandidate::path)
                .toList());
    }

    @Test
    void findCandidates_LimitsRelatedFilesPerChangedFile() {
        // given
        var changedFile = changedFile("src/App.ts", "export const app = true;");
        GitTreeResponseDto tree = tree(
                "src/App.ts",
                "src/App.css",
                "src/App.types.ts",
                "src/Button.ts",
                "src/Card.ts");

        // when
        var candidates = service.findCandidates(List.of(changedFile), tree, List.of());

        // then
        assertEquals(RelatedFileCandidateService.MAX_RELATED_FILES_PER_CHANGED_FILE, candidates.size());
    }

    @Test
    void findCandidates_TreatsConfigFilesAsLowPriorityCandidatesWithoutLanguageAssumption() {
        // given
        var changedFile = changedFile("package.json", "{\"scripts\":{\"build\":\"vite build\"}}");
        GitTreeResponseDto tree = tree(
                "package.json",
                "Dockerfile",
                ".github/workflows/ci.yml",
                "pom.xml",
                "src/App.ts");

        // when
        var candidates = service.findCandidates(List.of(changedFile), tree, List.of());

        // then
        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().allMatch(candidate -> candidate.reason().contains("configuration change")));
        assertFalse(candidates.stream().anyMatch(candidate -> candidate.path().equals("src/App.ts")));
    }

    @Test
    void findCandidates_SkipsChangedFilesWithoutFetchedContent() {
        // given
        var changedFile = ReviewContextDto.ChangedFileContextDto.builder()
                .filename("src/App.ts")
                .status("removed")
                .patch("@@ -1 +0 @@\n-export const app = true;")
                .contentFetchStatus(ReviewContextDto.ContentFetchStatus.SKIPPED)
                .contentSkipReason("removed file")
                .build();
        GitTreeResponseDto tree = tree("src/App.css", "src/App.types.ts");

        // when
        var candidates = service.findCandidates(List.of(changedFile), tree, List.of());

        // then
        assertTrue(candidates.isEmpty());
    }

    @Test
    void findCandidates_DoesNotTreatBareExternalImportsAsLocalReferences() {
        // given
        var changedFile = changedFile("src/App.ts", "import React from \"react\";");
        GitTreeResponseDto tree = tree("src/App.ts", "src/react.ts", "src/App.css");

        // when
        var candidates = service.findCandidates(List.of(changedFile), tree, List.of());

        // then
        assertFalse(candidates.stream().anyMatch(candidate -> candidate.path().equals("src/react.ts")));
        assertEquals(List.of("src/App.css"), candidates.stream()
                .map(RelatedFileCandidateService.RelatedFileCandidate::path)
                .toList());
    }

    private ReviewContextDto.ChangedFileContextDto changedFile(String filename, String headContent) {
        return ReviewContextDto.ChangedFileContextDto.builder()
                .filename(filename)
                .status("modified")
                .patch("@@ -1 +1 @@\n+content")
                .headContent(headContent)
                .contentFetchStatus(ReviewContextDto.ContentFetchStatus.FETCHED)
                .build();
    }

    private GitTreeResponseDto tree(String... paths) {
        GitTreeResponseDto tree = new GitTreeResponseDto();
        tree.setTree(List.of(paths).stream().map(this::treeItem).toList());
        return tree;
    }

    private GitTreeResponseDto.GitTreeItemDto treeItem(String path) {
        GitTreeResponseDto.GitTreeItemDto item = new GitTreeResponseDto.GitTreeItemDto();
        item.setType("blob");
        item.setPath(path);
        return item;
    }
}
