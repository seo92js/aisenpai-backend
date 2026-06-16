package com.seojs.aisenpai_backend.pullrequest.repository;

import com.seojs.aisenpai_backend.pullrequest.entity.CodeFileDependency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CodeFileDependencyRepository extends JpaRepository<CodeFileDependency, Long> {
    List<CodeFileDependency> findByCodeGraphIndexId(Long indexId);
    
    List<CodeFileDependency> findByCodeGraphIndexIdAndSourceFilePath(Long indexId, String sourceFilePath);

    void deleteByCodeGraphIndexId(Long indexId);
}
