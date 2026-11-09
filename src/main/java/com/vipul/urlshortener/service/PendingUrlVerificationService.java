package com.vipul.urlshortener.service;

import com.vipul.urlshortener.config.UrlSafetyProperties;
import com.vipul.urlshortener.entity.SafeStatus;
import com.vipul.urlshortener.entity.UrlMapping;
import com.vipul.urlshortener.exception.ThreatLookupUnavailableException;
import com.vipul.urlshortener.exception.UnsafeDestinationException;
import com.vipul.urlshortener.repository.UrlMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class PendingUrlVerificationService {

    private static final Logger logger = LoggerFactory.getLogger(PendingUrlVerificationService.class);

    private final UrlMappingRepository repository;
    private final InlineThreatCheck inlineThreatCheck;
    private final UrlSafetyProperties properties;
    private final PendingVerificationState state;

    public PendingUrlVerificationService(
            UrlMappingRepository repository,
            InlineThreatCheck inlineThreatCheck,
            UrlSafetyProperties properties,
            PendingVerificationState state
    ) {
        this.repository = repository;
        this.inlineThreatCheck = inlineThreatCheck;
        this.properties = properties;
        this.state = state;
    }

    @Scheduled(fixedDelayString = "${bitshort.url-safety.verification.delay-ms:10000}")
    public void verifyPendingUrls() {
        if (!state.mayHavePending()) {
            return;
        }

        if (!repository.existsBySafeStatus(SafeStatus.PENDING)) {
            state.markNoPending();
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoff = now.minus(properties.getVerification().getMaxPendingAge());
        List<UrlMapping> pending = repository.findOldestPending(
                SafeStatus.PENDING,
                now,
                cutoff,
                PageRequest.of(0, properties.getVerification().getBatchSize())
        );
        if (pending.isEmpty()) {
            state.markNoPending();
            return;
        }

        for (UrlMapping mapping : pending) {
            try {
                SafeStatus verdict;
                try {
                    inlineThreatCheck.check(mapping.getLongUrl());
                    verdict = SafeStatus.SAFE;
                } catch (UnsafeDestinationException e) {
                    verdict = SafeStatus.BLOCKED;
                }

                repository.updateSafeStatusIfExpected(
                        mapping.getId(), SafeStatus.PENDING, verdict, LocalDateTime.now()
                );
            } catch (ThreatLookupUnavailableException e) {
                logger.warn("Pending URL verification stopped because the safety lookup is unavailable");
                return;
            } catch (RuntimeException e) {
                logger.warn("Pending URL verification failed unexpectedly for mapping id {}",
                        mapping.getId(), e);
            }
        }
    }
}
