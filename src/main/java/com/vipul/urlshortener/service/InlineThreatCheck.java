package com.vipul.urlshortener.service;

import com.vipul.urlshortener.config.UrlSafetyProperties;
import com.vipul.urlshortener.exception.ThreatLookupUnavailableException;
import com.vipul.urlshortener.exception.UnsafeDestinationException;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class InlineThreatCheck {

    private final UrlThreatChecker checker;
    private final UrlSafetyProperties properties;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            3,
            3,
            0,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16),
            runnable -> {
                Thread thread = new Thread(runnable, "url-safety-check");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

    public InlineThreatCheck(UrlThreatChecker checker, UrlSafetyProperties properties) {
        this.checker = checker;
        this.properties = properties;
    }

    public void check(String url) {
        Future<?> future;
        try {
            future = executor.submit(() -> checker.check(url));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            throw new ThreatLookupUnavailableException(e);
        }

        try {
            future.get(properties.getInlineBudget().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ThreatLookupUnavailableException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ThreatLookupUnavailableException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof UnsafeDestinationException unsafe) {
                throw unsafe;
            }
            if (cause instanceof ThreatLookupUnavailableException unavailable) {
                throw unavailable;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ThreatLookupUnavailableException(cause);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
