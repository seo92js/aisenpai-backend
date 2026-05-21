package com.seojs.aisenpai_backend.github.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class RepositoryCacheService {
    private static final String REPOSITORIES_CACHE = "repositories";

    private final CacheManager cacheManager;

    public void evictByAccessToken(String accessToken) {
        Cache cache = cacheManager.getCache(REPOSITORIES_CACHE);
        if (cache == null) {
            return;
        }
        cache.evict(accessToken);
        log.info("Evicted repository cache for accessToken: {}", maskToken(accessToken));
    }

    public void evictAll() {
        Cache cache = cacheManager.getCache(REPOSITORIES_CACHE);
        if (cache == null) {
            return;
        }
        cache.clear();
        log.info("Evicted all repository cache entries");
    }

    public void evictAllAfterCommit() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            evictAll();
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evictAll();
            }
        });
    }

    private String maskToken(String accessToken) {
        if (accessToken == null || accessToken.length() < 5) {
            return "****";
        }
        return accessToken.substring(0, 5) + "...";
    }
}
