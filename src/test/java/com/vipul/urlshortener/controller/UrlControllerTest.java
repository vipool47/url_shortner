package com.vipul.urlshortener.controller;

import com.vipul.urlshortener.dto.UrlRequest;
import com.vipul.urlshortener.dto.UrlResponse;
import com.vipul.urlshortener.dto.UrlStatusResponse;
import com.vipul.urlshortener.entity.SafeStatus;
import com.vipul.urlshortener.entity.UrlMapping;
import com.vipul.urlshortener.exception.LinkBlockedException;
import com.vipul.urlshortener.exception.LinkPendingException;
import com.vipul.urlshortener.exception.UnsafeDestinationException;
import com.vipul.urlshortener.service.UrlServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UrlControllerTest {

    @Test
    void createResponseContainsSavedStatusWithoutLookingMappingUpAgain() {
        UrlServiceImpl service = mock(UrlServiceImpl.class);
        UrlMapping mapping = mapping("abc123", SafeStatus.PENDING);
        when(service.shortenUrlWithStatus(
                eq("https://example.com"), isNull(), isNull(), isNull(), isNull(), isNull()
        )).thenReturn(mapping);
        UrlController controller = new UrlController(service);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setScheme("https");
        request.setServerName("short.example");
        request.setServerPort(443);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        ResponseEntity<?> response;
        try {
            response = controller.shortenUrl(new UrlRequest("https://example.com"));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("PENDING", ((UrlResponse) response.getBody()).getStatus());
        assertEquals("abc123", ((UrlResponse) response.getBody()).getShortCode());
        verify(service, never()).findByShortCode("abc123");
    }

    @Test
    void confirmedThreatReturnsBadRequest() {
        UrlServiceImpl service = mock(UrlServiceImpl.class);
        doThrow(new UnsafeDestinationException()).when(service).shortenUrlWithStatus(
                eq("https://bad.example"), isNull(), isNull(), isNull(), isNull(), isNull()
        );
        UrlController controller = new UrlController(service);

        ResponseEntity<?> response = controller.shortenUrl(new UrlRequest("https://bad.example"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void statusEndpointReturnsOnlyStatusAndDoesNotMutateMapping() {
        UrlServiceImpl service = mock(UrlServiceImpl.class);
        when(service.findByShortCode("pending1")).thenReturn(Optional.of(mapping("pending1", SafeStatus.PENDING)));
        UrlController controller = new UrlController(service);

        ResponseEntity<UrlStatusResponse> response = controller.getUrlStatus("pending1");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(new UrlStatusResponse("PENDING"), response.getBody());
        verify(service).findByShortCode("pending1");
        verify(service, never()).shortenUrlWithStatus(any(), any(), any(), any(), any(), any());
    }

    @Test
    void statusEndpointReturnsNotFoundForUnknownOrExpiredLink() {
        UrlServiceImpl service = mock(UrlServiceImpl.class);
        when(service.findByShortCode("missing")).thenReturn(Optional.empty());
        UrlMapping expired = mapping("expired", SafeStatus.SAFE);
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(service.findByShortCode("expired")).thenReturn(Optional.of(expired));
        UrlController controller = new UrlController(service);

        assertEquals(HttpStatus.NOT_FOUND, controller.getUrlStatus("missing").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.getUrlStatus("expired").getStatusCode());
    }

    @Test
    void blockedAndPendingRedirectsUseStaticReadableResponses() {
        UrlServiceImpl service = mock(UrlServiceImpl.class);
        doThrow(new LinkBlockedException()).when(service).getLongUrl("blocked");
        doThrow(new LinkPendingException()).when(service).getLongUrl("pending");
        UrlController controller = new UrlController(service);

        ResponseEntity<?> blocked = controller.redirect("blocked");
        assertEquals(HttpStatus.FORBIDDEN, blocked.getStatusCode());
        assertEquals(MediaType.TEXT_PLAIN, blocked.getHeaders().getContentType());
        assertTrue(blocked.getBody().toString().contains("flagged as unsafe"));

        ResponseEntity<?> pending = controller.redirect("pending");
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, pending.getStatusCode());
        assertEquals("30", pending.getHeaders().getFirst("Retry-After"));
        assertEquals(MediaType.TEXT_PLAIN, pending.getHeaders().getContentType());
        assertTrue(pending.getBody().toString().contains("safety-checked"));
    }

    @Test
    void safeRedirectRemains302AndExpiredOrUnknownRemain404() {
        UrlServiceImpl service = mock(UrlServiceImpl.class);
        when(service.getLongUrl("safe")).thenReturn("https://example.com");
        doThrow(new RuntimeException()).when(service).getLongUrl("missing");
        UrlController controller = new UrlController(service);

        ResponseEntity<?> safe = controller.redirect("safe");
        assertEquals(302, safe.getStatusCode().value());
        assertEquals("https://example.com", safe.getHeaders().getLocation().toString());
        assertEquals(HttpStatus.NOT_FOUND, controller.redirect("missing").getStatusCode());
    }

    private UrlMapping mapping(String code, SafeStatus status) {
        UrlMapping mapping = new UrlMapping("https://example.com");
        mapping.setShortCode(code);
        mapping.setSafeStatus(status);
        mapping.setSafeCheckedAt(status == SafeStatus.PENDING ? null : LocalDateTime.now());
        return mapping;
    }
}
