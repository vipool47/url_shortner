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
import com.vipul.urlshortener.util.UrlSafetyValidator;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UrlServiceImplTest {

    private UrlMappingRepository repository;
    private UrlServiceImpl service;
    private UrlThreatChecker threatChecker;
    private UrlSafetyProperties properties;
    private PlatformTransactionManager transactionManager;
    private InlineThreatCheck inlineThreatCheck;
    private PendingVerificationState pendingVerificationState;

    @BeforeEach
    void setUp() {
        repository = mock(UrlMappingRepository.class);
        properties = new UrlSafetyProperties();
        threatChecker = mock(UrlThreatChecker.class);
        inlineThreatCheck = new InlineThreatCheck(threatChecker, properties);
        pendingVerificationState = new PendingVerificationState();
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        service = new UrlServiceImpl(
                repository,
                new UrlSafetyValidator(properties),
                inlineThreatCheck,
                properties,
                pendingVerificationState,
                transactionManager
        );
        AtomicLong idGenerator = new AtomicLong(1);
        when(repository.save(any(UrlMapping.class))).thenAnswer(invocation -> {
            UrlMapping mapping = invocation.getArgument(0);
            if (mapping.getId() == null) {
                mapping.setId(idGenerator.getAndIncrement());
            }
            return mapping;
        });
    }

    @AfterEach
    void shutDownInlineChecker() {
        inlineThreatCheck.shutdown();
    }

    @Test
    void createUrlWithoutExpirationSucceeds() {
        when(repository.findAllByLongUrl("https://example.com")).thenReturn(List.of());

        String code = service.shortenUrl("https://example.com", null, null, null, null, null);

        assertNotNull(code);
        assertFalse(code.isEmpty());
        verify(repository, atLeast(2)).save(any(UrlMapping.class));
        verify(threatChecker).check("https://example.com");
    }

    @Test
    void createUrlWithoutExpirationStoresNoExpiry() {
        when(repository.findAllByLongUrl("https://example.com")).thenReturn(List.of());

        service.shortenUrl("https://example.com", null, null, null, null, null);

        ArgumentCaptor<UrlMapping> captor = ArgumentCaptor.forClass(UrlMapping.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        UrlMapping firstSave = captor.getAllValues().get(0);
        assertEquals("https://example.com", firstSave.getLongUrl());
        assertNull(firstSave.getExpiresAt());
        assertEquals(SafeStatus.SAFE, firstSave.getSafeStatus());
        assertNotNull(firstSave.getSafeCheckedAt());
    }

    @Test
    void createUrlWithFutureExpirationSucceeds() {
        service.shortenUrl("https://example.com", "future", 30, null, null, null);

        ArgumentCaptor<UrlMapping> captor = ArgumentCaptor.forClass(UrlMapping.class);
        verify(repository).save(captor.capture());
        UrlMapping saved = captor.getValue();
        assertEquals("future", saved.getShortCode());
        assertNotNull(saved.getExpiresAt());
        assertTrue(saved.getExpiresAt().isAfter(LocalDateTime.now()));
    }

    @Test
    void createUrlWithFutureTimestampExpirationSucceeds() {
        String future = LocalDateTime.now().plusDays(2).toString();

        service.shortenUrl("https://example.com", "timestamped", null, null, null, future);

        ArgumentCaptor<UrlMapping> captor = ArgumentCaptor.forClass(UrlMapping.class);
        verify(repository).save(captor.capture());
        UrlMapping saved = captor.getValue();
        assertNotNull(saved.getExpiresAt());
        assertTrue(saved.getExpiresAt().isAfter(LocalDateTime.now()));
    }

    @Test
    void createUrlWithPastExpirationIsRejected() {
        String past = LocalDateTime.now().minusHours(1).toString();

        assertThrows(InvalidExpiryException.class,
                () -> service.shortenUrl("https://example.com", "expired", null, null, null, past));
        verify(repository, never()).save(any(UrlMapping.class));
    }

    @Test
    void createUrlWithInvalidExpirationFormatIsRejected() {
        assertThrows(InvalidExpiryException.class,
                () -> service.shortenUrl("https://example.com", null, null, null, null, "not-a-date"));
        verify(repository, never()).save(any(UrlMapping.class));
    }

    @Test
    void createUrlWithEmptyUrlIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.shortenUrl(null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.shortenUrl("   ", null, null, null, null, null));
        verify(repository, never()).save(any(UrlMapping.class));
        verifyNoInteractions(threatChecker);
    }

    @Test
    void createUrlWithMalformedUrlIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.shortenUrl("http://exa mple.com", null, null, null, null, null));
        verify(repository, never()).save(any(UrlMapping.class));
        verifyNoInteractions(threatChecker);
    }

    @Test
    void createUrlWithNonHttpSchemeIsRejected() {
        for (String badUrl : List.of(
                "ftp://example.com",
                "mailto:test@example.com",
                "javascript:alert(1)",
                "example.com")) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.shortenUrl(badUrl, null, null, null, null, null),
                    "should reject: " + badUrl);
        }
        verify(repository, never()).save(any(UrlMapping.class));
    }

    @Test
    void createUrlWithNoUsableHostIsRejected() {
        for (String badUrl : List.of(
                "https://:8080/path",
                "http://?x=1",
                "http://user@/path")) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.shortenUrl(badUrl, null, null, null, null, null),
                    "should reject: " + badUrl);
        }
        verify(repository, never()).save(any(UrlMapping.class));
    }

    @Test
    void shortenUrlIsNotTransactional() throws Exception {
        Method method = UrlServiceImpl.class.getMethod(
                "shortenUrl", String.class, String.class, Integer.class, Integer.class, Integer.class, String.class);
        assertNull(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class));
    }

    @Test
    void successfulCreationPersistsMapping() {
        when(repository.findAllByLongUrl("https://spring.io")).thenReturn(List.of());

        String code = service.shortenUrl("https://spring.io", "spring", null, null, null, null);

        assertEquals("spring", code);
        ArgumentCaptor<UrlMapping> captor = ArgumentCaptor.forClass(UrlMapping.class);
        verify(repository).save(captor.capture());
        UrlMapping saved = captor.getValue();
        assertEquals("spring", saved.getShortCode());
        assertEquals("https://spring.io", saved.getLongUrl());
        assertNull(saved.getExpiresAt());
    }

    @Test
    void concurrentCustomAliasConflictGetsReadableError() {
        when(repository.findByShortCode("taken")).thenReturn(Optional.empty());
        doThrow(new DataIntegrityViolationException("unique constraint"))
                .when(repository).save(any(UrlMapping.class));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.shortenUrl("https://example.com", "taken", null, null, null, null));

        assertEquals("Custom alias already exists", exception.getMessage());
    }

    @Test
    void redirectReturnsLongUrlForActiveLink() {
        UrlMapping active = new UrlMapping("https://active.example");
        active.setShortCode("abc123");
        active.setExpiresAt(LocalDateTime.now().plusDays(1));
        active.setSafeStatus(SafeStatus.SAFE);
        when(repository.findByShortCode("abc123")).thenReturn(Optional.of(active));

        assertEquals("https://active.example", service.getLongUrl("abc123"));
        verifyNoInteractions(threatChecker);
    }

    @Test
    void redirectRejectsExpiredLinkEvenBeforeCleanupRuns() {
        UrlMapping expired = new UrlMapping("https://old.example");
        expired.setShortCode("old123");
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        expired.setSafeStatus(SafeStatus.SAFE);
        when(repository.findByShortCode("old123")).thenReturn(Optional.of(expired));

        assertThrows(LinkExpiredException.class, () -> service.getLongUrl("old123"));
    }

    @Test
    void redirectRejectsUnknownShortCode() {
        when(repository.findByShortCode("missing")).thenReturn(Optional.empty());

        assertThrows(RuntimeException.class, () -> service.getLongUrl("missing"));
    }

    @Test
    void cleanupDeletesOnlyExpiredLinks() {
        when(repository.deleteExpiredLinks(any(LocalDateTime.class))).thenReturn(2);

        int deleted = service.deleteExpiredLinks();

        assertEquals(2, deleted);
        verify(repository).deleteExpiredLinks(any(LocalDateTime.class));
    }

    @Test
    void cleanupDeletesStuckPendingLinksByConfiguredAge() {
        when(repository.deleteStuckPendingLinks(eq(SafeStatus.PENDING), any(LocalDateTime.class))).thenReturn(1);

        assertEquals(1, service.deleteStuckPendingLinks());

        verify(repository).deleteStuckPendingLinks(eq(SafeStatus.PENDING), any(LocalDateTime.class));
    }

    @Test
    void deduplicationReusesExistingActiveLink() {
        UrlMapping active = new UrlMapping("https://example.com");
        active.setShortCode("abc");
        active.setSafeStatus(SafeStatus.SAFE);
        active.setSafeCheckedAt(LocalDateTime.now());
        when(repository.findAllByLongUrl("https://example.com")).thenReturn(List.of(active));

        String code = service.shortenUrl("https://example.com", null, null, null, null, null);

        assertEquals("abc", code);
        verify(repository, never()).save(any(UrlMapping.class));
        verifyNoInteractions(threatChecker);
    }

    @Test
    void expiredExistingLinkIsNotReused() {
        UrlMapping expired = new UrlMapping("https://example.com");
        expired.setShortCode("oldcode");
        expired.setExpiresAt(LocalDateTime.now().minusDays(1));
        expired.setSafeStatus(SafeStatus.SAFE);
        expired.setSafeCheckedAt(LocalDateTime.now());
        when(repository.findAllByLongUrl("https://example.com")).thenReturn(List.of(expired));

        String code = service.shortenUrl("https://example.com", null, null, null, null, null);

        assertNotNull(code);
        assertNotEquals("oldcode", code);
        ArgumentCaptor<UrlMapping> captor = ArgumentCaptor.forClass(UrlMapping.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        UrlMapping firstSave = captor.getAllValues().get(0);
        assertNull(firstSave.getExpiresAt());
    }

    @Test
    void expiryThenNoExpiryCreatesDifferentLinksButReusesFreshSafeVerdict() {
        List<UrlMapping> rows = new java.util.ArrayList<>();
        stubMappingRows(rows);

        String expiringCode = service.shortenUrl("https://example.com", null, 60, null, null, null);
        String permanentCode = service.shortenUrl("https://example.com", null, null, null, null, null);

        assertNotEquals(expiringCode, permanentCode);
        assertEquals(2, rows.size());
        assertNotNull(findByCode(rows, expiringCode).getExpiresAt());
        assertNull(findByCode(rows, permanentCode).getExpiresAt());
        verify(threatChecker, times(1)).check("https://example.com");
    }

    @Test
    void noExpiryThenExpiryCreatesDifferentLinks() {
        List<UrlMapping> rows = new java.util.ArrayList<>();
        stubMappingRows(rows);

        String permanentCode = service.shortenUrl("https://example.com", null, null, null, null, null);
        String expiringCode = service.shortenUrl("https://example.com", null, 60, null, null, null);

        assertNotEquals(permanentCode, expiringCode);
        assertEquals(2, rows.size());
        assertNull(findByCode(rows, permanentCode).getExpiresAt());
        assertTrue(findByCode(rows, expiringCode).getExpiresAt().isAfter(LocalDateTime.now()));
    }

    @Test
    void twoRequestsWithoutExpiryReuseTheSameNonExpiringLink() {
        List<UrlMapping> rows = new java.util.ArrayList<>();
        stubMappingRows(rows);

        String firstCode = service.shortenUrl("https://example.com", null, null, null, null, null);
        String secondCode = service.shortenUrl("https://example.com", null, null, null, null, null);

        assertEquals(firstCode, secondCode);
        assertEquals(1, rows.size());
        verify(threatChecker, times(1)).check("https://example.com");
    }

    private void stubMappingRows(List<UrlMapping> rows) {
        AtomicLong ids = new AtomicLong(100);
        when(repository.findAllByLongUrl("https://example.com")).thenAnswer(invocation ->
                rows.stream().filter(mapping -> mapping.getLongUrl().equals("https://example.com")).toList()
        );
        doAnswer(invocation -> {
            UrlMapping mapping = invocation.getArgument(0);
            if (mapping.getId() == null) {
                mapping.setId(ids.getAndIncrement());
            }
            if (!rows.contains(mapping)) {
                rows.add(mapping);
            }
            return mapping;
        }).when(repository).save(any(UrlMapping.class));
    }

    private UrlMapping findByCode(List<UrlMapping> mappings, String code) {
        return mappings.stream()
                .filter(mapping -> code.equals(mapping.getShortCode()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void threatLookupFailureCreatesPendingWhenFailClosed() {
        doThrow(new ThreatLookupUnavailableException()).when(threatChecker).check("https://example.com");
        when(repository.findAllByLongUrl("https://example.com")).thenReturn(List.of());

        String code = service.shortenUrl("https://example.com", null, null, null, null, null);

        assertNotNull(code);
        ArgumentCaptor<UrlMapping> captor = ArgumentCaptor.forClass(UrlMapping.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        assertEquals(SafeStatus.PENDING, captor.getAllValues().get(0).getSafeStatus());
        assertTrue(pendingVerificationState.mayHavePending());
    }

    @Test
    void confirmedThreatIsRejectedBeforePersistence() {
        doThrow(new UnsafeDestinationException()).when(threatChecker).check("https://bad.example");

        assertThrows(UnsafeDestinationException.class,
                () -> service.shortenUrl("https://bad.example", "custom", null, null, null, null));

        verify(repository, never()).save(any(UrlMapping.class));
    }

    @Test
    void explicitFailOpenAllowsCreationAfterLookupFailure() {
        properties.setFailClosed(false);
        String sensitiveUrl = "https://example.com/path?token=private-value";
        doThrow(new ThreatLookupUnavailableException()).when(threatChecker).check(sensitiveUrl);
        when(repository.findAllByLongUrl(sensitiveUrl)).thenReturn(List.of());

        assertNotNull(service.shortenUrl(sensitiveUrl, null, null, null, null, null));

        verify(repository, atLeastOnce()).save(any(UrlMapping.class));
        ArgumentCaptor<UrlMapping> captor = ArgumentCaptor.forClass(UrlMapping.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        assertEquals(SafeStatus.SAFE, captor.getAllValues().get(0).getSafeStatus());
    }

    @Test
    void safeCheckPersistsSafeStatusAndCheckedTimestamp() {
        when(repository.findAllByLongUrl("https://safe.example")).thenReturn(List.of());

        service.shortenUrl("https://safe.example", null, null, null, null, null);

        ArgumentCaptor<UrlMapping> captor = ArgumentCaptor.forClass(UrlMapping.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        assertEquals(SafeStatus.SAFE, captor.getAllValues().get(0).getSafeStatus());
        assertNotNull(captor.getAllValues().get(0).getSafeCheckedAt());
    }

    @Test
    void freshSafeVerdictSkipsThreatChecker() {
        UrlMapping existing = new UrlMapping("https://fresh.example");
        existing.setShortCode("fresh");
        existing.setSafeStatus(SafeStatus.SAFE);
        existing.setSafeCheckedAt(LocalDateTime.now().minusHours(1));
        when(repository.findAllByLongUrl("https://fresh.example")).thenReturn(List.of(existing));

        assertEquals("fresh", service.shortenUrl("https://fresh.example", null, null, null, null, null));

        verifyNoInteractions(threatChecker);
    }

    @Test
    void recentSafeFreshnessWindowCanBeConfigured() {
        properties.setSafeVerdictFreshness(Duration.ofMinutes(5));
        UrlMapping existing = new UrlMapping("https://stale.example");
        existing.setShortCode("stale");
        existing.setSafeStatus(SafeStatus.SAFE);
        existing.setSafeCheckedAt(LocalDateTime.now().minusMinutes(6));
        when(repository.findAllByLongUrl("https://stale.example")).thenReturn(List.of(existing));

        assertNotNull(service.shortenUrl("https://stale.example", null, null, null, null, null));

        verify(threatChecker).check("https://stale.example");
    }

    @Test
    void budgetExceededCreatesPendingAndDoesNotCancelCheck() {
        properties.setInlineBudget(Duration.ofMillis(20));
        doAnswer(invocation -> {
            Thread.sleep(150);
            return null;
        }).when(threatChecker).check("https://slow.example");
        when(repository.findAllByLongUrl("https://slow.example")).thenReturn(List.of());

        assertNotNull(service.shortenUrl("https://slow.example", null, null, null, null, null));

        ArgumentCaptor<UrlMapping> captor = ArgumentCaptor.forClass(UrlMapping.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        assertEquals(SafeStatus.PENDING, captor.getAllValues().get(0).getSafeStatus());
    }

    @Test
    void existingBlockedLinkIsNotReused() {
        UrlMapping blocked = new UrlMapping("https://blocked.example");
        blocked.setShortCode("blocked");
        blocked.setSafeStatus(SafeStatus.BLOCKED);
        when(repository.findAllByLongUrl("https://blocked.example")).thenReturn(List.of(blocked));

        String code = service.shortenUrl("https://blocked.example", null, null, null, null, null);

        assertNotEquals("blocked", code);
        verify(repository, atLeastOnce()).save(any(UrlMapping.class));
    }

    @Test
    void pendingRedirectWithSafeVerdictConditionallyPromotesAndReturnsUrl() {
        UrlMapping pending = pendingMapping("safe-click", "https://safe.example");
        when(repository.findByShortCode("safe-click")).thenReturn(Optional.of(pending));
        when(repository.updateSafeStatusIfExpected(
                eq(pending.getId()), eq(SafeStatus.PENDING), eq(SafeStatus.SAFE), any(LocalDateTime.class)
        )).thenReturn(1);

        assertEquals("https://safe.example", service.getLongUrl("safe-click"));
        assertEquals(SafeStatus.SAFE, pending.getSafeStatus());
        verify(threatChecker).check("https://safe.example");
    }

    @Test
    void pendingRedirectWithUnsafeVerdictMarksBlocked() {
        UrlMapping pending = pendingMapping("unsafe-click", "https://bad.example");
        when(repository.findByShortCode("unsafe-click")).thenReturn(Optional.of(pending));
        doThrow(new UnsafeDestinationException()).when(threatChecker).check("https://bad.example");
        when(repository.updateSafeStatusIfExpected(
                eq(pending.getId()), eq(SafeStatus.PENDING), eq(SafeStatus.BLOCKED), any(LocalDateTime.class)
        )).thenReturn(1);

        assertThrows(LinkBlockedException.class, () -> service.getLongUrl("unsafe-click"));
    }

    @Test
    void pendingRedirectWithUnavailableLookupReturnsPending() {
        UrlMapping pending = pendingMapping("pending-click", "https://unavailable.example");
        when(repository.findByShortCode("pending-click")).thenReturn(Optional.of(pending));
        doThrow(new ThreatLookupUnavailableException()).when(threatChecker).check("https://unavailable.example");

        assertThrows(LinkPendingException.class, () -> service.getLongUrl("pending-click"));
        verify(repository, never()).updateSafeStatusIfExpected(
                any(), any(), any(), any()
        );
    }

    @Test
    void conditionalUpdateRaceDoesNotRedirectOrChangeStalePendingMapping() {
        UrlMapping pending = pendingMapping("race-click", "https://safe.example");
        when(repository.findByShortCode("race-click")).thenReturn(Optional.of(pending));
        when(repository.updateSafeStatusIfExpected(
                eq(pending.getId()), eq(SafeStatus.PENDING), eq(SafeStatus.SAFE), any(LocalDateTime.class)
        )).thenReturn(0);

        assertThrows(LinkPendingException.class, () -> service.getLongUrl("race-click"));
        assertEquals(SafeStatus.PENDING, pending.getSafeStatus());
        verify(repository).updateSafeStatusIfExpected(
                eq(pending.getId()), eq(SafeStatus.PENDING), eq(SafeStatus.SAFE), any(LocalDateTime.class)
        );
    }

    @Test
    void safeRedirectNeverCallsCheckerAndBlockedRedirectNeverCallsChecker() {
        UrlMapping safe = new UrlMapping("https://safe.example");
        safe.setId(50L);
        safe.setSafeStatus(SafeStatus.SAFE);
        when(repository.findByShortCode("safe-click")).thenReturn(Optional.of(safe));
        UrlMapping blocked = new UrlMapping("https://blocked.example");
        blocked.setId(51L);
        blocked.setSafeStatus(SafeStatus.BLOCKED);
        when(repository.findByShortCode("blocked-click")).thenReturn(Optional.of(blocked));

        assertEquals("https://safe.example", service.getLongUrl("safe-click"));
        assertThrows(LinkBlockedException.class, () -> service.getLongUrl("blocked-click"));
        verifyNoInteractions(threatChecker);
    }

    private UrlMapping pendingMapping(String code, String url) {
        UrlMapping mapping = new UrlMapping(url);
        mapping.setId((long) code.hashCode());
        mapping.setShortCode(code);
        mapping.setSafeStatus(SafeStatus.PENDING);
        return mapping;
    }

    @Test
    void failOpenWarningDoesNotLogTheDestinationOrApiKey() {
        properties.setFailClosed(false);
        properties.getSafeBrowsing().setApiKey("test-api-key");
        String sensitiveUrl = "https://example.com/path?token=private-value";
        doThrow(new ThreatLookupUnavailableException()).when(threatChecker).check(sensitiveUrl);
        when(repository.findAllByLongUrl(sensitiveUrl)).thenReturn(List.of());
        Logger logger = (Logger) LoggerFactory.getLogger(UrlServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            service.shortenUrl(sensitiveUrl, null, null, null, null, null);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        String messages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + right);
        assertFalse(messages.contains(sensitiveUrl));
        assertFalse(messages.contains("test-api-key"));
        assertTrue(messages.contains("fail-open is configured"));
    }
}