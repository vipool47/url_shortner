package com.vipul.urlshortener.service;

import com.vipul.urlshortener.config.UrlSafetyProperties;
import com.vipul.urlshortener.entity.SafeStatus;
import com.vipul.urlshortener.entity.UrlMapping;
import com.vipul.urlshortener.exception.ThreatLookupUnavailableException;
import com.vipul.urlshortener.exception.UnsafeDestinationException;
import com.vipul.urlshortener.repository.UrlMappingRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PendingUrlVerificationServiceTest {

    private UrlMappingRepository repository;
    private UrlThreatChecker checker;
    private UrlSafetyProperties properties;
    private PendingVerificationState state;
    private InlineThreatCheck inlineThreatCheck;
    private PendingUrlVerificationService service;

    @BeforeEach
    void setUp() {
        repository = mock(UrlMappingRepository.class);
        checker = mock(UrlThreatChecker.class);
        properties = new UrlSafetyProperties();
        state = new PendingVerificationState();
        inlineThreatCheck = new InlineThreatCheck(checker, properties);
        service = new PendingUrlVerificationService(repository, inlineThreatCheck, properties, state);
    }

    @AfterEach
    void shutDownInlineChecker() {
        inlineThreatCheck.shutdown();
    }

    @Test
    void resolvesPendingRowsToSafeAndBlocked() {
        UrlMapping safe = pending("https://safe.example", 1L);
        UrlMapping blocked = pending("https://bad.example", 2L);
        when(repository.existsBySafeStatus(SafeStatus.PENDING)).thenReturn(true);
        when(repository.findOldestPending(
                eq(SafeStatus.PENDING), any(LocalDateTime.class), any(LocalDateTime.class), any()
        )).thenReturn(List.of(safe, blocked));
        doThrow(new UnsafeDestinationException()).when(checker).check("https://bad.example");

        service.verifyPendingUrls();

        verify(repository).updateSafeStatusIfExpected(
                eq(1L), eq(SafeStatus.PENDING), eq(SafeStatus.SAFE), any(LocalDateTime.class)
        );
        verify(repository).updateSafeStatusIfExpected(
                eq(2L), eq(SafeStatus.PENDING), eq(SafeStatus.BLOCKED), any(LocalDateTime.class)
        );
    }

    @Test
    void unavailableLookupStopsTheCycleBeforeCheckingNextRow() {
        UrlMapping first = pending("https://unavailable.example", 1L);
        UrlMapping second = pending("https://later.example", 2L);
        when(repository.existsBySafeStatus(SafeStatus.PENDING)).thenReturn(true);
        when(repository.findOldestPending(
                eq(SafeStatus.PENDING), any(LocalDateTime.class), any(LocalDateTime.class), any()
        )).thenReturn(List.of(first, second));
        doThrow(new ThreatLookupUnavailableException()).when(checker).check("https://unavailable.example");

        service.verifyPendingUrls();

        verify(checker, never()).check("https://later.example");
        verify(repository, never()).updateSafeStatusIfExpected(any(), any(), any(), any());
    }

    @Test
    void skipsDatabaseWhenFlagIsFalse() {
        state.markNoPending();

        service.verifyPendingUrls();

        verifyNoInteractions(repository);
    }

    @Test
    void excludesRowsOlderThanMaximumPendingAge() {
        properties.getVerification().setMaxPendingAge(java.time.Duration.ofHours(2));
        when(repository.existsBySafeStatus(SafeStatus.PENDING)).thenReturn(true);
        when(repository.findOldestPending(
                eq(SafeStatus.PENDING), any(LocalDateTime.class), any(LocalDateTime.class), any()
        )).thenReturn(List.of());

        service.verifyPendingUrls();

        verify(repository).findOldestPending(
                eq(SafeStatus.PENDING),
                any(LocalDateTime.class),
                argThat(cutoff -> cutoff.isBefore(LocalDateTime.now().minusHours(1))),
                any()
        );
        verify(repository, never()).updateSafeStatusIfExpected(any(), any(), any(), any());
        assertFalse(state.mayHavePending());
    }

    private UrlMapping pending(String url, Long id) {
        UrlMapping mapping = new UrlMapping(url);
        mapping.setId(id);
        mapping.setSafeStatus(SafeStatus.PENDING);
        return mapping;
    }
}
