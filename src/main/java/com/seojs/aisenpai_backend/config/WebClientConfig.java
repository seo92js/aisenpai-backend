package com.seojs.aisenpai_backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import com.seojs.aisenpai_backend.exception.GithubRateLimitEx;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)) // 10MB
                .filter((request, next) -> next.exchange(request)
                        .flatMap(response -> {
                            String remaining = response.headers().asHttpHeaders().getFirst("X-RateLimit-Remaining");
                            if (remaining != null) {
                                try {
                                    int remainingCount = Integer.parseInt(remaining);
                                    if (remainingCount <= 0) {
                                        return Mono.error(new GithubRateLimitEx(
                                                "GitHub API rate limit exceeded."));
                                    }
                                } catch (NumberFormatException e) {
                                    // ignore if header is invalid
                                }
                            }
                            return Mono.just(response);
                        }));
    }
}
