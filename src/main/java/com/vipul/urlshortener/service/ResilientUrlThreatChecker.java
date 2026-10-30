package com.vipul.urlshortener.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.vipul.urlshortener.exception.ThreatLookupUnavailableException;
import com.vipul.urlshortener.exception.UnsafeDestinationException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Primary
@Service
public class ResilientUrlThreatChecker implements UrlThreatChecker {

    private static final Logger logger = LoggerFactory.getLogger(ResilientUrlThreatChecker.class);

    private final UrlThreatChecker delegate;
    private final Cache<String, Boolean> safeVerdicts = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();
    private final CircuitBreaker circuitBreaker = CircuitBreaker.of(
            "safeBrowsing",
            CircuitBreakerConfig.custom()
                    .slidingWindowSize(20)
                    .minimumNumberOfCalls(10)
                    .failureRateThreshold(50)
                    .waitDurationInOpenState(Duration.ofSeconds(30))
                    .ignoreExceptions(UnsafeDestinationException.class)
                    .build()
    );

    public ResilientUrlThreatChecker(
            @Qualifier("safeBrowsingService") UrlThreatChecker delegate
    ) {
        this.delegate = delegate;
    }

    @Override
    public void check(String url) {
        if (safeVerdicts.getIfPresent(url) != null) {
            return;
        }

        try {
            circuitBreaker.executeRunnable(() -> delegate.check(url));
            safeVerdicts.put(url, Boolean.TRUE);
        } catch (CallNotPermittedException e) {
            logger.warn("Safe Browsing circuit is open; lookup was not sent to the provider");
            throw new ThreatLookupUnavailableException(e);
        }
    }
}
