package com.seojs.aisenpai_backend.github.service;

import com.seojs.aisenpai_backend.github.entity.GithubAccount;
import com.seojs.aisenpai_backend.github.repository.GithubAccountRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomOAuth2UserService extends DefaultOAuth2UserService {
    private final GithubAccountRepository githubAccountRepository;
    private final TokenEncryptionService tokenEncryptionService;
    private final LoginHistoryService loginHistoryService;

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) {
        OAuth2User oAuth2User = super.loadUser(userRequest);
        String accessToken = userRequest.getAccessToken().getTokenValue();
        String loginId = oAuth2User.getAttribute("login");
        if (loginId != null && accessToken != null) {
            String encryptedToken = tokenEncryptionService.encryptToken(accessToken);
            githubAccountRepository.findByLoginId(loginId)
                    .ifPresentOrElse(
                            account -> {
                                account.updateAccessToken(encryptedToken);
                                log.info("User logged in: {}", loginId);
                            },
                            () -> {
                                String webhookSecret = generateWebhookSecret();
                                GithubAccount newAccount = GithubAccount.builder()
                                        .loginId(loginId)
                                        .accessToken(encryptedToken)
                                        .webhookSecret(webhookSecret)
                                        .build();
                                githubAccountRepository.save(newAccount);
                                log.info("New user registered: {}", loginId);
                            });

            recordLoginHistory(loginId);
        }
        return oAuth2User;
    }

    private void recordLoginHistory(String loginId) {
        String ipAddress = null;
        String userAgent = null;

        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();
            ipAddress = getClientIp(request);
            userAgent = request.getHeader("User-Agent");
        }

        loginHistoryService.recordLogin(loginId, ipAddress, userAgent);
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 웹훅 시크릿 생성
     */
    private String generateWebhookSecret() {
        SecureRandom secureRandom = new SecureRandom();
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
}
