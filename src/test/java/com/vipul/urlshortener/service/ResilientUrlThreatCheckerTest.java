package com.vipul.urlshortener.service;

import com.vipul.urlshortener.exception.ThreatLookupUnavailableException;
import com.vipul.urlshortener.exception.UnsafeDestinationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doThrow;

class ResilientUrlThreatCheckerTest {

    private UrlThreatChecker delegate;
    private ResilientUrlThreatChecker checker;

    @BeforeEach
    void setUp() {
        delegate = mock(UrlThreatChecker.class);
        checker = new ResilientUrlThreatChecker(delegate);
    }

    @Test
    void cachesSafeUrlVerdict() {
        String url = "https://safe.example";

        checker.check(url);
        checker.check(url);

        verify(delegate, times(1)).check(url);
    }

    @Test
    void unsafeVerdictIsPropagatedAndNotCached() {
        String url = "https://unsafe.example";
        UnsafeDestinationException unsafe = new UnsafeDestinationException();
        doThrow(unsafe).when(delegate).check(url);

        assertSame(unsafe, assertThrows(UnsafeDestinationException.class, () -> checker.check(url)));
        assertSame(unsafe, assertThrows(UnsafeDestinationException.class, () -> checker.check(url)));

        verify(delegate, times(2)).check(url);
    }

    @Test
    void opensCircuitAfterProviderFailuresAndStopsCallingDelegate() {
        String url = "https://provider-error.example";
        ThreatLookupUnavailableException unavailable = new ThreatLookupUnavailableException();
        doThrow(unavailable).when(delegate).check(url);

        for (int i = 0; i < 10; i++) {
            assertSame(unavailable,
                    assertThrows(ThreatLookupUnavailableException.class, () -> checker.check(url)));
        }

        assertThrows(ThreatLookupUnavailableException.class, () -> checker.check(url));
        verify(delegate, times(10)).check(url);
    }
}
