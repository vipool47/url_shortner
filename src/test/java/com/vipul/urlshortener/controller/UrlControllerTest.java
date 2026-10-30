package com.vipul.urlshortener.controller;

import com.vipul.urlshortener.dto.ErrorResponse;
import com.vipul.urlshortener.dto.UrlRequest;
import com.vipul.urlshortener.exception.ThreatLookupUnavailableException;
import com.vipul.urlshortener.exception.UnsafeDestinationException;
import com.vipul.urlshortener.service.UrlServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class UrlControllerTest {

    @Test
    void providerFailureReturnsServiceUnavailableUsingExistingErrorShape() {
        UrlServiceImpl service = mock(UrlServiceImpl.class);
        doThrow(new ThreatLookupUnavailableException()).when(service).shortenUrl(
                eq("https://example.com"), isNull(), isNull(), isNull(), isNull(), isNull()
        );
        UrlController controller = new UrlController(service);

        ResponseEntity<?> response = controller.shortenUrl(new UrlRequest("https://example.com"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        ErrorResponse body = (ErrorResponse) response.getBody();
        assertEquals("URL_SAFETY_UNAVAILABLE", body.getError());
        assertEquals("URL safety screening is temporarily unavailable", body.getMessage());
    }

    @Test
    void confirmedThreatReturnsBadRequest() {
        UrlServiceImpl service = mock(UrlServiceImpl.class);
        doThrow(new UnsafeDestinationException()).when(service).shortenUrl(
                eq("https://bad.example"), isNull(), isNull(), isNull(), isNull(), isNull()
        );
        UrlController controller = new UrlController(service);

        ResponseEntity<?> response = controller.shortenUrl(new UrlRequest("https://bad.example"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_ERROR", ((ErrorResponse) response.getBody()).getError());
    }
}
