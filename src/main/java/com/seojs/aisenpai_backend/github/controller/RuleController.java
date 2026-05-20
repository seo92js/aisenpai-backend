package com.seojs.aisenpai_backend.github.controller;

import com.seojs.aisenpai_backend.github.dto.RuleResponseDto;
import com.seojs.aisenpai_backend.github.dto.RuleSaveDto;
import com.seojs.aisenpai_backend.github.service.GithubRepositoryAccessService;
import com.seojs.aisenpai_backend.github.service.RuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rules")
@RequiredArgsConstructor
public class RuleController {

    private final RuleService ruleService;
    private final GithubRepositoryAccessService repositoryAccessService;

    @GetMapping
    public List<RuleResponseDto> getRules(@AuthenticationPrincipal OAuth2User principal,
            @RequestParam String owner, @RequestParam String repository) {
        Long repositoryId = repositoryAccessService.resolveRepositoryId(principal, owner, repository);
        return ruleService.getRules(repositoryId);
    }

    @PostMapping
    public RuleResponseDto createRule(
            @AuthenticationPrincipal OAuth2User principal,
            @RequestParam String owner,
            @RequestParam String repository,
            @RequestBody RuleSaveDto request) {
        Long repositoryId = requireRuleAdminRepositoryId(principal, owner, repository);
        return ruleService.createRule(repositoryId, request);
    }

    @PutMapping("/{ruleId}")
    public RuleResponseDto updateRule(
            @AuthenticationPrincipal OAuth2User principal,
            @PathVariable Long ruleId,
            @RequestParam String owner,
            @RequestParam String repository,
            @RequestBody RuleSaveDto request) {
        Long repositoryId = requireRuleAdminRepositoryId(principal, owner, repository);
        return ruleService.updateRule(repositoryId, ruleId, request);
    }

    @DeleteMapping("/{ruleId}")
    public void deleteRule(@AuthenticationPrincipal OAuth2User principal, @PathVariable Long ruleId,
            @RequestParam String owner, @RequestParam String repository) {
        Long repositoryId = requireRuleAdminRepositoryId(principal, owner, repository);
        ruleService.deleteRule(repositoryId, ruleId);
    }

    @PatchMapping("/{ruleId}/toggle")
    public RuleResponseDto toggleRule(@AuthenticationPrincipal OAuth2User principal, @PathVariable Long ruleId,
            @RequestParam String owner, @RequestParam String repository) {
        Long repositoryId = requireRuleAdminRepositoryId(principal, owner, repository);
        return ruleService.toggleRule(repositoryId, ruleId);
    }

    private Long requireRuleAdminRepositoryId(OAuth2User principal, String owner, String repository) {
        return repositoryAccessService.requireAdminRepositoryId(principal, owner, repository,
                "Repository admin permission is required to update rules.");
    }
}
