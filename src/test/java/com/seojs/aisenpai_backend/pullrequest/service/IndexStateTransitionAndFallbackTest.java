package com.seojs.aisenpai_backend.pullrequest.service;

import com.seojs.aisenpai_backend.github.dto.GitTreeResponseDto;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewContextDto.ChangedFileContextDto;
import com.seojs.aisenpai_backend.pullrequest.dto.ReviewContextDto.ContentFetchStatus;
import com.seojs.aisenpai_backend.pullrequest.entity.CodeFileDependency;
import com.seojs.aisenpai_backend.pullrequest.entity.CodeGraphIndex;
import com.seojs.aisenpai_backend.pullrequest.repository.CodeFileDependencyRepository;
import com.seojs.aisenpai_backend.pullrequest.repository.CodeGraphIndexRepository;
import com.seojs.aisenpai_backend.pullrequest.service.RelatedFileCandidateService.RelatedFileCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IndexStateTransitionAndFallbackTest {

    @Test
    void testStateTransitions() {
        CodeGraphIndex index = CodeGraphIndex.builder()
                .repositoryId(1L)
                .refName("refs/heads/main")
                .commitSha("commit123")
                .status(CodeGraphIndex.Status.INDEXING)
                .defaultBranch(true)
                .build();

        assertEquals(CodeGraphIndex.Status.INDEXING, index.getStatus());
        assertNotNull(index.getDefaultBranch());
        assertTrue(index.getDefaultBranch());

        index.markReady(10, 25, "JVM_REGEX", "1.0");
        assertEquals(CodeGraphIndex.Status.READY, index.getStatus());
        assertEquals(10, index.getFileCount());
        assertEquals(25, index.getDependencyCount());

        index.markStale();
        assertEquals(CodeGraphIndex.Status.STALE, index.getStatus());

        CodeGraphIndex failedIndex = CodeGraphIndex.builder()
                .repositoryId(1L)
                .refName("refs/heads/main")
                .commitSha("commit456")
                .status(CodeGraphIndex.Status.INDEXING)
                .build();
        failedIndex.markFailed("Out of memory error while parsing");
        assertEquals(CodeGraphIndex.Status.FAILED, failedIndex.getStatus());
        assertEquals("Out of memory error while parsing", failedIndex.getFailureReason());
    }

    @Test
    void testFallbackWhenIndexIsMissingOrNotReady() {
        CodeGraphIndexRepository indexRepo = mock(CodeGraphIndexRepository.class);
        CodeFileDependencyRepository depRepo = mock(CodeFileDependencyRepository.class);

        when(indexRepo.findFirstByRepositoryIdAndDefaultBranchAndStatusOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());

        CodeGraphQueryService queryService = new CodeGraphQueryService(indexRepo, depRepo);
        RelatedFileCandidateService candidateService = new RelatedFileCandidateService(queryService);

        List<ChangedFileContextDto> changedFiles = List.of(
                ChangedFileContextDto.builder()
                        .filename("src/App.ts")
                        .status("modified")
                        .headContent("import helper from \"./helper\";")
                        .contentFetchStatus(ContentFetchStatus.FETCHED)
                        .build()
        );

        GitTreeResponseDto tree = new GitTreeResponseDto();
        GitTreeResponseDto.GitTreeItemDto item1 = new GitTreeResponseDto.GitTreeItemDto();
        item1.setPath("src/App.ts");
        item1.setType("blob");
        GitTreeResponseDto.GitTreeItemDto item2 = new GitTreeResponseDto.GitTreeItemDto();
        item2.setPath("src/helper.ts");
        item2.setType("blob");
        tree.setTree(List.of(item1, item2));

        List<RelatedFileCandidate> candidates = candidateService.findCandidates(1L, changedFiles, tree, List.of());

        assertFalse(candidates.isEmpty());
        assertEquals("src/helper.ts", candidates.get(0).path());
        assertTrue(candidates.get(0).reason().contains("reference hint"));
    }

    @Test
    void testFallbackWhenQueryServiceThrowsException() {
        CodeGraphIndexRepository indexRepo = mock(CodeGraphIndexRepository.class);
        CodeFileDependencyRepository depRepo = mock(CodeFileDependencyRepository.class);

        when(indexRepo.findFirstByRepositoryIdAndDefaultBranchAndStatusOrderByCreatedAtDesc(any(), any(), any()))
                .thenThrow(new RuntimeException("Database connection timed out"));

        CodeGraphQueryService queryService = new CodeGraphQueryService(indexRepo, depRepo);
        RelatedFileCandidateService candidateService = new RelatedFileCandidateService(queryService);

        List<ChangedFileContextDto> changedFiles = List.of(
                ChangedFileContextDto.builder()
                        .filename("src/App.ts")
                        .status("modified")
                        .headContent("import helper from \"./helper\";")
                        .contentFetchStatus(ContentFetchStatus.FETCHED)
                        .build()
        );

        GitTreeResponseDto tree = new GitTreeResponseDto();
        GitTreeResponseDto.GitTreeItemDto item1 = new GitTreeResponseDto.GitTreeItemDto();
        item1.setPath("src/App.ts");
        item1.setType("blob");
        GitTreeResponseDto.GitTreeItemDto item2 = new GitTreeResponseDto.GitTreeItemDto();
        item2.setPath("src/helper.ts");
        item2.setType("blob");
        tree.setTree(List.of(item1, item2));

        List<RelatedFileCandidate> candidates = assertDoesNotThrow(() -> 
                candidateService.findCandidates(1L, changedFiles, tree, List.of())
        );

        assertFalse(candidates.isEmpty());
        assertEquals("src/helper.ts", candidates.get(0).path());
    }
}
