package com.seojs.aisenpai_backend.pullrequest.service;

import com.seojs.aisenpai_backend.pullrequest.entity.CodeFileDependency;
import com.seojs.aisenpai_backend.pullrequest.entity.CodeGraphIndex;
import com.seojs.aisenpai_backend.pullrequest.repository.CodeFileDependencyRepository;
import com.seojs.aisenpai_backend.pullrequest.repository.CodeGraphIndexRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class CodeGraphIndexServiceIntegrationTest {

    @Autowired
    private CodeGraphIndexService codeGraphIndexService;

    @Autowired
    private CodeGraphIndexRepository codeGraphIndexRepository;

    @Autowired
    private CodeFileDependencyRepository codeFileDependencyRepository;

    @Test
    void testMarkOtherIndexesStaleDeletesDependenciesAndIndex() {
        Long repositoryId = 999L;

        // Create old index
        CodeGraphIndex oldIndex = CodeGraphIndex.builder()
                .repositoryId(repositoryId)
                .refName("refs/heads/main")
                .commitSha("oldsha")
                .status(CodeGraphIndex.Status.READY)
                .build();
        oldIndex = codeGraphIndexRepository.save(oldIndex);

        // Create dependency for old index
        CodeFileDependency dep = CodeFileDependency.builder()
                .codeGraphIndex(oldIndex)
                .sourceFilePath("src/Old.java")
                .rawImport("java.util.List")
                .build();
        codeFileDependencyRepository.save(dep);

        // Create new index
        CodeGraphIndex newIndex = CodeGraphIndex.builder()
                .repositoryId(repositoryId)
                .refName("refs/heads/main")
                .commitSha("newsha")
                .status(CodeGraphIndex.Status.READY)
                .build();
        newIndex = codeGraphIndexRepository.save(newIndex);

        // Run markOtherIndexesStale
        ReflectionTestUtils.invokeMethod(codeGraphIndexService, "markOtherIndexesStale", repositoryId, newIndex.getId());

        // Verify that old index and its dependency are deleted
        List<CodeGraphIndex> remainingIndexes = codeGraphIndexRepository.findByRepositoryIdOrderByCreatedAtDesc(repositoryId);
        assertEquals(1, remainingIndexes.size());
        assertEquals("newsha", remainingIndexes.get(0).getCommitSha());

        final Long oldIndexId = oldIndex.getId();
        List<CodeFileDependency> remainingDeps = codeFileDependencyRepository.findAll();
        assertTrue(remainingDeps.stream().noneMatch(d -> d.getCodeGraphIndex().getId().equals(oldIndexId)));
    }
}
