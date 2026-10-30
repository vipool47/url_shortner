package com.vipul.urlshortener.service;

import com.vipul.urlshortener.config.UrlSafetyProperties;
import com.vipul.urlshortener.entity.UrlMapping;
import com.vipul.urlshortener.exception.InvalidExpiryException;
import com.vipul.urlshortener.exception.LinkExpiredException;
import com.vipul.urlshortener.exception.ThreatLookupUnavailableException;
import com.vipul.urlshortener.exception.UnsafeDestinationException;
import com.vipul.urlshortener.repository.UrlMappingRepository;
import com.vipul.urlshortener.util.UrlSafetyValidator;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UrlServiceImplTest {

    private UrlMappingRepository repository;
    private UrlServiceImpl service;
    private UrlThreatChecker threatChecker;
    private UrlSafetyProperties properties;
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        repository = mock(UrlMappingRepository.class);
        properties = new UrlSafetyProperties();
        threatChecker = mock(UrlThreatChecker.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        service = new UrlServiceImpl(
                repository,
                new UrlSafetyValidator(properties),
                threatChecker,
                properties,
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
        when(repository.findByShortCode("abc123")).thenReturn(Optional.of(active));

        assertEquals("https://active.example", service.getLongUrl("abc123"));
    }

    @Test
    void redirectRejectsExpiredLinkEvenBeforeCleanupRuns() {
        UrlMapping expired = new UrlMapping("https://old.example");
        expired.setShortCode("old123");
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
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
    void deduplicationReusesExistingActiveLink() {
        UrlMapping active = new UrlMapping("https://example.com");
        active.setShortCode("abc");
        active.setExpiresAt(LocalDateTime.now().plusDays(1));
        when(repository.findAllByLongUrl("https://example.com")).thenReturn(List.of(active));

        String code = service.shortenUrl("https://example.com", null, null, null, null, null);

        assertEquals("abc", code);
        verify(repository, never()).save(any(UrlMapping.class));
    }

    @Test
    void deduplicationSkipsExpiredLinkAndCreatesNewOne() {
        UrlMapping expired = new UrlMapping("https://example.com");
        expired.setShortCode("oldcode");
        expired.setExpiresAt(LocalDateTime.now().minusDays(1));
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
    void threatLookupFailureFailsClosedBeforePersistence() {
        doThrow(new ThreatLookupUnavailableException()).when(threatChecker).check("https://example.com");

        assertThrows(ThreatLookupUnavailableException.class,
                () -> service.shortenUrl("https://example.com", null, null, null, null, null));

        verify(repository, never()).save(any(UrlMapping.class));
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