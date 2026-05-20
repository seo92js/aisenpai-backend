package com.seojs.aisenpai_backend.github.repository;

import com.seojs.aisenpai_backend.github.entity.Rule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RuleRepository extends JpaRepository<Rule, Long> {
    List<Rule> findByRepositorySettingsId(Long repositorySettingsId);
}
