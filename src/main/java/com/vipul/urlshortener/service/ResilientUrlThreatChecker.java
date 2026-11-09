package com.vipul.urlshortener.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.vipul.urlshortener.config.UrlSafetyProperties;
import com.vipul.urlshortener.exception.ThreatLookupUnavailableException;
import com.vipul.urlshortener.exception.UnsafeDestinationException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.net.URI;
import java.util.Locale;

@Primary
@Service
public class ResilientUrlThreatChecker implements UrlThreatChecker {

    private static final Logger logger = LoggerFactory.getLogger(ResilientUrlThreatChecker.class);

    private final UrlThreatChecker delegate;
    private final Cache<String, Boolean> safeVerdicts;
    private final CircuitBreaker circuitBreaker = CircuitBreaker.of(
            "safeBrowsing",
            CircuitBreakerConfig.custom()
                    .slidingWindowSize(20)
                    .minimumNumberOfCalls(10)
                    .failureRateThreshold(50)
                    .slowCallDurationThreshold(Duration.ofSeconds(1))
                    .slowCallRateThreshold(50)
                    .waitDurationInOpenState(Duration.ofSeconds(30))
                    .ignoreExceptions(UnsafeDestinationException.class)
                    .build()
    );

    @Autowired
    public ResilientUrlThreatChecker(
            @Qualifier("safeBrowsingService") UrlThreatChecker delegate,
            UrlSafetyProperties properties
    ) {
        this.delegate = delegate;
        this.safeVerdicts = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(properties.getSafeVerdictTtl())
                .build();
    }

    public ResilientUrlThreatChecker(
            @Qualifier("safeBrowsingService") UrlThreatChecker delegate
    ) {
        this(delegate, new UrlSafetyProperties());
    }

    @Override
    public void check(String url) {
        if (safeVerdicts.getIfPresent(cacheKey(url)) != null) {
            return;
        }

        try {
            circuitBreaker.executeRunnable(() -> delegate.check(url));
            safeVerdicts.put(cacheKey(url), Boolean.TRUE);
        } catch (CallNotPermittedException e) {
            logger.warn("Safe Browsing circuit is open; lookup was not sent to the provider");
            throw new ThreatLookupUnavailableException(e);
        }
    }

    private String cacheKey(String url) {
        URI uri = URI.create(url);
        int fragmentStart = url.indexOf('#');
        String withoutFragment = fragmentStart >= 0 ? url.substring(0, fragmentStart) : url;
        int schemeEnd = url.indexOf(':');
        String normalized = uri.getScheme().toLowerCase(Locale.ROOT) + withoutFragment.substring(schemeEnd);

        String authority = uri.getRawAuthority();
        if (authority == null) {
            return normalized;
        }
        int authorityStart = schemeEnd + 3;
        int hostStart = authority.lastIndexOf('@') + 1;
        int hostEnd;
        if (authority.charAt(hostStart) == '[') {
            hostEnd = authority.indexOf(']', hostStart) + 1;
        } else {
            int portStart = authority.indexOf(':', hostStart);
            hostEnd = portStart >= 0 ? portStart : authority.length();
        }
        String host = authority.substring(hostStart, hostEnd).toLowerCase(Locale.ROOT);
        int absoluteHostStart = authorityStart + hostStart;
        int absoluteHostEnd = authorityStart + hostEnd;
        return normalized.substring(0, absoluteHostStart) + host + normalized.substring(absoluteHostEnd);
    }
}
