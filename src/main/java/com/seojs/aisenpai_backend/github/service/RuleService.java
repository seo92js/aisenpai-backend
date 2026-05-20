package com.seojs.aisenpai_backend.github.service;

import com.seojs.aisenpai_backend.exception.RuleNotFoundEx;

import com.seojs.aisenpai_backend.github.dto.RuleResponseDto;
import com.seojs.aisenpai_backend.github.dto.RuleSaveDto;
import com.seojs.aisenpai_backend.github.entity.RepositoryAiSettings;
import com.seojs.aisenpai_backend.github.entity.Rule;
import com.seojs.aisenpai_backend.github.repository.RuleRepository;
import com.seojs.aisenpai_backend.github.service.RepositoryAiSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RuleService {

    private final RuleRepository ruleRepository;
    private final RepositoryAiSettingsService repositoryAiSettingsService;

    @Transactional(readOnly = true)
    public List<RuleResponseDto> getRules(Long repositoryId) {
        RepositoryAiSettings settings = repositoryAiSettingsService.getRequired(repositoryId);
        return ruleRepository.findByRepositorySettingsId(settings.getId()).stream()
                .map(RuleResponseDto::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public RuleResponseDto createRule(Long repositoryId, RuleSaveDto request) {
        RepositoryAiSettings settings = repositoryAiSettingsService.getRequired(repositoryId);

        Rule rule = Rule.builder()
                .repositorySettings(settings)
                .content(request.getContent())
                .isEnabled(true) // 기본값 활성화
                .targetFilePattern(request.getTargetFilePattern())
                .build();

        return RuleResponseDto.from(ruleRepository.save(rule));
    }

    @Transactional
    public RuleResponseDto updateRule(Long repositoryId, Long ruleId, RuleSaveDto request) {
        Rule rule = findRepositoryRule(repositoryId, ruleId);

        rule.update(request.getContent(), rule.isEnabled(), request.getTargetFilePattern());
        return RuleResponseDto.from(rule);
    }

    @Transactional
    public void deleteRule(Long repositoryId, Long ruleId) {
        findRepositoryRule(repositoryId, ruleId);
        ruleRepository.deleteById(ruleId);
    }

    @Transactional
    public RuleResponseDto toggleRule(Long repositoryId, Long ruleId) {
        Rule rule = findRepositoryRule(repositoryId, ruleId);

        rule.toggle();
        return RuleResponseDto.from(rule);
    }

    private Rule findRepositoryRule(Long repositoryId, Long ruleId) {
        Rule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new RuleNotFoundEx("Rule not found with id: " + ruleId));
        RepositoryAiSettings settings = rule.getRepositorySettings();
        if (settings == null || !repositoryId.equals(settings.getRepositoryId())) {
            throw new RuleNotFoundEx("Rule not found with id: " + ruleId);
        }
        return rule;
    }
}
