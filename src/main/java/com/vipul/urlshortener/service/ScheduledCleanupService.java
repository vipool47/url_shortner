package com.vipul.urlshortener.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ScheduledCleanupService {

    private final UrlServiceImpl urlService;
    private final Logger logger = LoggerFactory.getLogger(ScheduledCleanupService.class);

    public ScheduledCleanupService(UrlServiceImpl urlService) {
        this.urlService = urlService;
    }

    // Runs based on cleanup.expired.cron property (default: hourly)
    @Scheduled(cron = "${cleanup.expired.cron:0 0 * * * *}")
    public void cleanupExpired() {
        int count = urlService.deleteExpiredLinks();
        int stuckCount = urlService.deleteStuckPendingLinks();
        logger.info("Deleted {} expired links and {} stuck pending links", count, stuckCount);
    }
}
