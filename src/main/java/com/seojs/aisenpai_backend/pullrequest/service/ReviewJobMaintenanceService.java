package com.seojs.aisenpai_backend.pullrequest.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewJobMaintenanceService {

    private final PullRequestService pullRequestService;

    @Value("${aisenpai.review-job.in-progress-timeout-minutes:30}")
    private long inProgressTimeoutMinutes;

    @Scheduled(
            fixedDelayString = "${aisenpai.review-job.stuck-check-delay-ms:600000}",
            initialDelayString = "${aisenpai.review-job.stuck-check-initial-delay-ms:60000}")
    public void failStuckInProgressReviews() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(inProgressTimeoutMinutes);
        int failedCount = pullRequestService.failStuckInProgressReviews(cutoff);
        if (failedCount > 0) {
            log.warn("Marked {} stuck review job(s) as failed", failedCount);
        }
    }
}
