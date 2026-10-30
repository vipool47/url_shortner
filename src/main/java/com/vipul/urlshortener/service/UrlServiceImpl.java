package com.vipul.urlshortener.service;

import com.vipul.urlshortener.config.UrlSafetyProperties;
import com.vipul.urlshortener.entity.UrlMapping;
import com.vipul.urlshortener.exception.InvalidExpiryException;
import com.vipul.urlshortener.exception.LinkExpiredException;
import com.vipul.urlshortener.exception.ThreatLookupUnavailableException;
import com.vipul.urlshortener.repository.UrlMappingRepository;
import com.vipul.urlshortener.util.Base62Encoder;
import com.vipul.urlshortener.util.ExpiryUtil;
import com.vipul.urlshortener.util.UrlSafetyValidator;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class UrlServiceImpl {

    private static final Logger logger = LoggerFactory.getLogger(UrlServiceImpl.class);
    private final UrlMappingRepository repository;
    private final UrlSafetyValidator urlSafetyValidator;
    private final UrlThreatChecker urlThreatChecker;
    private final UrlSafetyProperties urlSafetyProperties;
    private final TransactionTemplate txTemplate;

    public UrlServiceImpl(
            UrlMappingRepository repository,
            UrlSafetyValidator urlSafetyValidator,
            UrlThreatChecker urlThreatChecker,
            UrlSafetyProperties urlSafetyProperties,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.urlSafetyValidator = urlSafetyValidator;
        this.urlThreatChecker = urlThreatChecker;
        this.urlSafetyProperties = urlSafetyProperties;
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    // Shorten URL with optional custom code and expiry
    public String shortenUrl(String longUrl, String customCode, Integer expiryTimeMinutes, Integer expiryTimeHours, Integer expiryTimeDays, String expiresAtTimestamp) {
        String url = longUrl == null ? null : longUrl.trim();
        urlSafetyValidator.validate(url);

        // Calculate expiry time if provided
        LocalDateTime expiresAt;
        try {
            expiresAt = ExpiryUtil.calculateExpiryTime(expiryTimeMinutes, expiryTimeHours, expiryTimeDays, expiresAtTimestamp);
        } catch (IllegalArgumentException e) {
            throw new InvalidExpiryException(e.getMessage());
        }

        String alias = validateAlias(customCode);
        checkThreat(url);

        try {
            return txTemplate.execute(status -> persist(url, alias, expiresAt));
        } catch (DataIntegrityViolationException e) {
        if (alias != null) {
            throw new IllegalArgumentException("Custom alias already exists");
        }
        throw e;   // not an alias problem, server error
    }
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

    private void checkThreat(String url) {
        try {
            urlThreatChecker.check(url);
        } catch (ThreatLookupUnavailableException e) {
            if (urlSafetyProperties.isFailClosed()) {
                throw e;
            }
            logger.warn("Threat lookup failed; continuing URL creation because fail-open is configured");
        }
    }

    private String persist(String url, String alias, LocalDateTime expiresAt) {
        if (alias != null) {
            UrlMapping mapping = new UrlMapping(url);
            mapping.setShortCode(alias);
            mapping.setExpiresAt(expiresAt);
            repository.save(mapping);
            return alias;
        }

        Optional<UrlMapping> existing = repository.findAllByLongUrl(url).stream()
                .filter(mapping -> !mapping.isExpired())
                .findFirst();
        if (existing.isPresent()) {
            return existing.get().getShortCode();
        }

        UrlMapping mapping = new UrlMapping(url);
        mapping.setExpiresAt(expiresAt);
        mapping = repository.save(mapping);
        mapping.setShortCode(Base62Encoder.encode(mapping.getId()));
        repository.save(mapping);
        return mapping.getShortCode();
    }

    // Resolve short URL to its original URL
    public String getLongUrl(String shortCode) {
        Optional<UrlMapping> mapping = repository.findByShortCode(shortCode);
        if (mapping.isEmpty()) {
            throw new RuntimeException("Short URL not found: " + shortCode);
        }

        UrlMapping urlMapping = mapping.get();

        // Check if link has expired
        if (urlMapping.isExpired()) {
            throw new LinkExpiredException("This link has expired", shortCode, ExpiryUtil.formatDateTime(urlMapping.getExpiresAt()));
        }

        return urlMapping.getLongUrl();
    }

    public Optional<UrlMapping> findByShortCode(String shortCode) {
        return repository.findByShortCode(shortCode);
    }

    // Permanently delete links whose expiration time has passed
    @Transactional
    public int deleteExpiredLinks() {
        return repository.deleteExpiredLinks(LocalDateTime.now());
    }
}