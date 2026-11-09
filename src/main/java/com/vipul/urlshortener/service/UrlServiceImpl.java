package com.vipul.urlshortener.service;

import com.vipul.urlshortener.config.UrlSafetyProperties;
import com.vipul.urlshortener.entity.SafeStatus;
import com.vipul.urlshortener.entity.UrlMapping;
import com.vipul.urlshortener.exception.InvalidExpiryException;
import com.vipul.urlshortener.exception.LinkBlockedException;
import com.vipul.urlshortener.exception.LinkExpiredException;
import com.vipul.urlshortener.exception.LinkPendingException;
import com.vipul.urlshortener.exception.ThreatLookupUnavailableException;
import com.vipul.urlshortener.exception.UnsafeDestinationException;
import com.vipul.urlshortener.repository.UrlMappingRepository;
import com.vipul.urlshortener.util.Base62Encoder;
import com.vipul.urlshortener.util.ExpiryUtil;
import com.vipul.urlshortener.util.UrlSafetyValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class UrlServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(UrlServiceImpl.class);
    private final UrlMappingRepository repository;
    private final UrlSafetyValidator urlSafetyValidator;
    private final InlineThreatCheck inlineThreatCheck;
    private final UrlSafetyProperties urlSafetyProperties;
    private final PendingVerificationState pendingVerificationState;
    private final TransactionTemplate txTemplate;

    public UrlServiceImpl(
            UrlMappingRepository repository,
            UrlSafetyValidator urlSafetyValidator,
            InlineThreatCheck inlineThreatCheck,
            UrlSafetyProperties urlSafetyProperties,
            PendingVerificationState pendingVerificationState,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.urlSafetyValidator = urlSafetyValidator;
        this.inlineThreatCheck = inlineThreatCheck;
        this.urlSafetyProperties = urlSafetyProperties;
        this.pendingVerificationState = pendingVerificationState;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    public String shortenUrl(
            String longUrl,
            String customCode,
            Integer expiryTimeMinutes,
            Integer expiryTimeHours,
            Integer expiryTimeDays,
            String expiresAtTimestamp
    ) {
        return shortenUrlWithStatus(
                longUrl, customCode, expiryTimeMinutes, expiryTimeHours, expiryTimeDays, expiresAtTimestamp
        ).getShortCode();
    }

    public UrlMapping shortenUrlWithStatus(
            String longUrl,
            String customCode,
            Integer expiryTimeMinutes,
            Integer expiryTimeHours,
            Integer expiryTimeDays,
            String expiresAtTimestamp
    ) {
        long validationStarted = System.nanoTime();
        String url = longUrl == null ? null : longUrl.trim();
        urlSafetyValidator.validate(url);

        LocalDateTime expiresAt;
        try {
            expiresAt = ExpiryUtil.calculateExpiryTime(
                    expiryTimeMinutes, expiryTimeHours, expiryTimeDays, expiresAtTimestamp
            );
        } catch (IllegalArgumentException e) {
            throw new InvalidExpiryException(e.getMessage());
        }
        long validationMs = elapsedMillis(validationStarted);

        long aliasStarted = System.nanoTime();
        String alias = validateAlias(customCode);
        long aliasMs = elapsedMillis(aliasStarted);

        long reuseStarted = System.nanoTime();
        List<UrlMapping> existingMappings = repository.findAllByLongUrl(url);
        UrlMapping recentSafe = findFreshSafeMapping(existingMappings);
        long reuseMs = elapsedMillis(reuseStarted);
        SafeStatus status;
        LocalDateTime checkedAt;
        long threatStarted = System.nanoTime();
        if (recentSafe != null) {
            status = SafeStatus.SAFE;
            checkedAt = recentSafe.getSafeCheckedAt();
        } else {
            try {
                inlineThreatCheck.check(url);
                status = SafeStatus.SAFE;
                checkedAt = LocalDateTime.now();
            } catch (UnsafeDestinationException e) {
                logger.info("URL creation timing: validation={}ms alias_check={}ms verdict_reuse_check={}ms threat_check={}ms persist=0ms status=REJECTED",
                        validationMs, aliasMs, reuseMs, elapsedMillis(threatStarted));
                throw e;
            } catch (ThreatLookupUnavailableException e) {
                if (urlSafetyProperties.isFailClosed()) {
                    status = SafeStatus.PENDING;
                    checkedAt = null;
                } else {
                    logger.warn("Threat lookup failed; continuing URL creation because fail-open is configured");
                    status = SafeStatus.SAFE;
                    checkedAt = LocalDateTime.now();
                }
            }
        }
        long threatMs = elapsedMillis(threatStarted);

        long persistStarted = System.nanoTime();
        SafeStatus statusToPersist = status;
        LocalDateTime checkedAtToPersist = checkedAt;
        UrlMapping mapping;
        try {
            mapping = txTemplate.execute(
                    transaction -> persist(url, alias, expiresAt, statusToPersist, checkedAtToPersist)
            );
        } catch (DataIntegrityViolationException e) {
            if (alias != null) {
                throw new IllegalArgumentException("Custom alias already exists");
            }
            throw e;
        }
        if (mapping.getSafeStatus() == SafeStatus.PENDING) {
            pendingVerificationState.markPending();
        }
        logger.info(
                "URL creation timing: validation={}ms alias_check={}ms verdict_reuse_check={}ms threat_check={}ms persist={}ms status={}",
                validationMs, aliasMs, reuseMs, threatMs, elapsedMillis(persistStarted), mapping.getSafeStatus()
        );
        return mapping;
    }

    private UrlMapping findFreshSafeMapping(List<UrlMapping> mappings) {
        LocalDateTime freshAfter = LocalDateTime.now().minus(urlSafetyProperties.getSafeVerdictFreshness());
        return mappings.stream()
                .filter(mapping -> mapping.getSafeStatus() == SafeStatus.SAFE)
                .filter(mapping -> mapping.getSafeCheckedAt() != null
                        && !mapping.getSafeCheckedAt().isBefore(freshAfter))
                .findFirst()
                .orElse(null);
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private String validateAlias(String customCode) {
        if (customCode == null || customCode.trim().isEmpty()) {
            return null;
        }

        String alias = customCode.trim().toLowerCase();
        if (!alias.matches("^[a-z0-9]+$")) {
            throw new IllegalArgumentException("Custom alias must contain only lowercase letters and numbers");
        }
        if (repository.findByShortCode(alias).isPresent()) {
            throw new IllegalArgumentException("Custom alias already exists");
        }
        return alias;
    }

    private UrlMapping persist(
            String url,
            String alias,
            LocalDateTime expiresAt,
            SafeStatus status,
            LocalDateTime checkedAt
    ) {
        if (alias == null && expiresAt == null) {
            Optional<UrlMapping> reusable = repository.findAllByLongUrl(url).stream()
                    .filter(mapping -> !mapping.isExpired())
                    .filter(mapping -> mapping.getExpiresAt() == null)
                    .filter(mapping -> mapping.getSafeStatus() != SafeStatus.BLOCKED)
                    .findFirst();
            if (reusable.isPresent()) {
                UrlMapping existing = reusable.get();
                if (status == SafeStatus.SAFE && existing.getSafeStatus() == SafeStatus.PENDING) {
                    int updated = repository.updateSafeStatusIfExpected(
                            existing.getId(), SafeStatus.PENDING, SafeStatus.SAFE, checkedAt
                    );
                    if (updated == 1) {
                        existing.setSafeStatus(SafeStatus.SAFE);
                        existing.setSafeCheckedAt(checkedAt);
                        return existing;
                    }
                } else {
                    return existing;
                }
            }
        }

        UrlMapping mapping = new UrlMapping(url);
        mapping.setExpiresAt(expiresAt);
        mapping.setSafeStatus(status);
        mapping.setSafeCheckedAt(checkedAt);
        if (alias != null) {
            mapping.setShortCode(alias);
            return repository.save(mapping);
        }

        mapping = repository.save(mapping);
        mapping.setShortCode(Base62Encoder.encode(mapping.getId()));
        return repository.save(mapping);
    }

    public String getLongUrl(String shortCode) {
        Optional<UrlMapping> mapping = repository.findByShortCode(shortCode);
        if (mapping.isEmpty()) {
            throw new RuntimeException("Short URL not found: " + shortCode);
        }

        UrlMapping urlMapping = mapping.get();
        if (urlMapping.isExpired()) {
            throw new LinkExpiredException(
                    "This link has expired", shortCode, ExpiryUtil.formatDateTime(urlMapping.getExpiresAt())
            );
        }

        if (urlMapping.getSafeStatus() == SafeStatus.BLOCKED) {
            throw new LinkBlockedException();
        }
        if (urlMapping.getSafeStatus() == SafeStatus.PENDING) {
            verifyPendingForRedirect(urlMapping);
        } else if (urlMapping.getSafeStatus() != SafeStatus.SAFE) {
            throw new LinkPendingException();
        }
        return urlMapping.getLongUrl();
    }

    private void verifyPendingForRedirect(UrlMapping mapping) {
        SafeStatus verdict;
        try {
            inlineThreatCheck.check(mapping.getLongUrl());
            verdict = SafeStatus.SAFE;
        } catch (UnsafeDestinationException e) {
            verdict = SafeStatus.BLOCKED;
        } catch (ThreatLookupUnavailableException e) {
            throw new LinkPendingException();
        }

        int updated = repository.updateSafeStatusIfExpected(
                mapping.getId(), SafeStatus.PENDING, verdict, LocalDateTime.now()
        );
        if (updated != 1) {
            throw new LinkPendingException();
        }
        if (verdict == SafeStatus.BLOCKED) {
            throw new LinkBlockedException();
        }
        mapping.setSafeStatus(SafeStatus.SAFE);
        mapping.setSafeCheckedAt(LocalDateTime.now());
    }

    public Optional<UrlMapping> findByShortCode(String shortCode) {
        return repository.findByShortCode(shortCode);
    }

    @Transactional
    public int deleteExpiredLinks() {
        return repository.deleteExpiredLinks(LocalDateTime.now());
    }

    @Transactional
    public int deleteStuckPendingLinks() {
        LocalDateTime cutoff = LocalDateTime.now().minus(urlSafetyProperties.getVerification().getMaxPendingAge());
        return repository.deleteStuckPendingLinks(SafeStatus.PENDING, cutoff);
    }
}
