package com.seojs.aisenpai_backend.github.service;

import com.seojs.aisenpai_backend.exception.OpenAiKeyNotSetEx;
import com.seojs.aisenpai_backend.github.dto.ReviewSettingsDto;
import com.seojs.aisenpai_backend.github.entity.GithubAccount;
import com.seojs.aisenpai_backend.github.entity.RepositoryAiSettings;
import com.seojs.aisenpai_backend.github.repository.RepositoryAiSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RepositoryAiSettingsService {

    private final RepositoryAiSettingsRepository repositoryAiSettingsRepository;
    private final TokenEncryptionService tokenEncryptionService;

    @Transactional
    public RepositoryAiSettings registerWebhookSettings(Long repositoryId, String owner, String repositoryName,
            GithubAccount account) {
        RepositoryAiSettings settings = repositoryAiSettingsRepository.findByRepositoryId(repositoryId)
                .orElseGet(() -> RepositoryAiSettings.builder()
                        .repositoryId(repositoryId)
                        .owner(owner)
                        .repositoryName(repositoryName)
                        .webhookSecret(account.getWebhookSecret())
                        .webhookRegisteredBy(account)
                        .postingAccount(account)
                        .build());

        settings.updateRepository(owner, repositoryName);
        settings.updateWebhookRegistration(account, account.getWebhookSecret());
        return repositoryAiSettingsRepository.save(settings);
    }

    @Transactional
    public RepositoryAiSettings getOrCreatePlaceholder(Long repositoryId, String owner, String repositoryName) {
        return repositoryAiSettingsRepository.findByRepositoryId(repositoryId)
                .map(settings -> {
                    settings.updateRepository(owner, repositoryName);
                    return settings;
                })
                .orElseGet(() -> repositoryAiSettingsRepository.save(RepositoryAiSettings.builder()
                        .repositoryId(repositoryId)
                        .owner(owner)
                        .repositoryName(repositoryName)
                        .webhookSecret("")
                        .build()));
    }

    @Transactional(readOnly = true)
    public RepositoryAiSettings getRequired(Long repositoryId) {
        return repositoryAiSettingsRepository.findByRepositoryId(repositoryId)
                .orElseThrow(() -> new OpenAiKeyNotSetEx("Repository AI settings are not configured."));
    }

    @Transactional(readOnly = true)
    public RepositoryAiSettings getConfiguredForReview(Long repositoryId) {
        RepositoryAiSettings settings = getRequired(repositoryId);
        if (settings.getOpenAiKey() == null || settings.getOpenAiKey().isBlank()) {
            throw new OpenAiKeyNotSetEx("OpenAI API key is not set for this repository.");
        }
        return settings;
    }

    @Transactional(readOnly = true)
    public ReviewSettingsDto getReviewSettings(Long repositoryId) {
        RepositoryAiSettings settings = getRequired(repositoryId);
        return new ReviewSettingsDto(
                settings.getReviewTone(),
                settings.getReviewFocus(),
                settings.getDetailLevel(),
                settings.getAutoReviewEnabled(),
                settings.getAutoPostToGithub(),
                settings.getOpenaiModel());
    }

    @Transactional
    public Long updateReviewSettings(Long repositoryId, ReviewSettingsDto dto) {
        RepositoryAiSettings settings = getRequired(repositoryId);
        settings.updateReviewSettings(dto.getTone(), dto.getFocus(), dto.getDetailLevel(),
                dto.getAutoReviewEnabled(), dto.getAutoPostToGithub(), dto.getOpenaiModel());
        return settings.getId();
    }

    @Transactional(readOnly = true)
    public List<String> getIgnorePatterns(Long repositoryId) {
        return getRequired(repositoryId).getIgnorePatternsAsList();
    }

    @Transactional
    public Long updateIgnorePatterns(Long repositoryId, List<String> patterns) {
        RepositoryAiSettings settings = getRequired(repositoryId);
        settings.updateIgnorePatterns(patterns == null ? "" : String.join(",", patterns));
        return settings.getId();
    }

    @Transactional(readOnly = true)
    public String getOpenAiKey(Long repositoryId) {
        String encryptedKey = getRequired(repositoryId).getOpenAiKey();
        if (encryptedKey == null || encryptedKey.isBlank()) {
            return null;
        }
        return tokenEncryptionService.decryptToken(encryptedKey);
    }

    @Transactional(readOnly = true)
    public String getMaskedOpenAiKey(Long repositoryId) {
        String decryptedKey = getOpenAiKey(repositoryId);
        if (decryptedKey == null || decryptedKey.length() < 10) {
            return null;
        }
        return decryptedKey.substring(0, 10) + "...****";
    }

    @Transactional
    public Long updateOpenAiKey(Long repositoryId, String openAiKey) {
        RepositoryAiSettings settings = getRequired(repositoryId);
        if (openAiKey == null || openAiKey.isBlank()) {
            settings.updateOpenAiKey(null);
        } else {
            settings.updateOpenAiKey(tokenEncryptionService.encryptToken(openAiKey));
        }
        return settings.getId();
    }
}
