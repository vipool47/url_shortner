package com.vipul.urlshortener.rateLimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitTest {

    @Test
    void requestsWithinLimitAreAllowed() {
        RateLimitProperties properties = properties(2, Duration.ofMinutes(1));
        RateLimitService service = new RateLimitService(properties);
        MockHttpServletRequest request = request("POST", "/api/shorten");

        assertTrue(service.tryConsume("203.0.113.10", request).allowed());
        assertTrue(service.tryConsume("203.0.113.10", request).allowed());
        assertFalse(service.tryConsume("203.0.113.10", request).allowed());
    }

    @Test
    void differentIpsHaveIndependentBuckets() {
        RateLimitService service = new RateLimitService(properties(1, Duration.ofMinutes(1)));
        MockHttpServletRequest request = request("POST", "/api/shorten");

        assertTrue(service.tryConsume("198.51.100.1", request).allowed());
        assertFalse(service.tryConsume("198.51.100.1", request).allowed());
        assertTrue(service.tryConsume("198.51.100.2", request).allowed());
    }

    @Test
    void differentEndpointsUseTheirAppropriateLimits() {
        RateLimitProperties properties = properties(1, Duration.ofMinutes(1));
        properties.setRedirect(2);
        RateLimitService service = new RateLimitService(properties);

        MockHttpServletRequest shortenRequest = request("POST", "/api/shorten");
        MockHttpServletRequest redirectRequest = request("GET", "/abc123");

        assertTrue(service.tryConsume("203.0.113.20", shortenRequest).allowed());
        assertFalse(service.tryConsume("203.0.113.20", shortenRequest).allowed());
        assertTrue(service.tryConsume("203.0.113.20", redirectRequest).allowed());
        assertTrue(service.tryConsume("203.0.113.20", redirectRequest).allowed());
    }

    @Test
    void safetyStatusEndpointUsesDefaultRateLimitPolicy() {
        RateLimitService service = new RateLimitService(properties(1, Duration.ofMinutes(1)));
        MockHttpServletRequest request = request("GET", "/api/urls/abc123/status");

        assertEquals(RateLimitPolicy.DEFAULT, service.determinePolicy(request));
    }

    @Test
    void tokensBecomeAvailableAfterTheRefillPeriod() throws InterruptedException {
        RateLimitProperties properties = properties(1, Duration.ofSeconds(1));
        RateLimitService service = new RateLimitService(properties);
        MockHttpServletRequest request = request("POST", "/api/shorten");

        assertTrue(service.tryConsume("203.0.113.30", request).allowed());
        assertFalse(service.tryConsume("203.0.113.30", request).allowed());

        Thread.sleep(1100L);
        assertTrue(service.tryConsume("203.0.113.30", request).allowed());
    }

    @Test
    void retryAfterHeaderIsPresentOnRejectedRequests() throws Exception {
        RateLimitProperties properties = properties(1, Duration.ofMinutes(1));
        RateLimitService service = new RateLimitService(properties);
        RateLimitFilter filter = new RateLimitFilter(service, properties);
        MockHttpServletRequest request = request("POST", "/api/shorten");
        request.setRemoteAddr("203.0.113.40");
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();

        filter.doFilter(request, firstResponse, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(200));
        filter.doFilter(request, secondResponse, (req, res) -> fail("Filter should stop the chain when rate limited."));

        assertEquals(200, firstResponse.getStatus());
        assertEquals(429, secondResponse.getStatus());
        assertNotNull(secondResponse.getHeader("Retry-After"));
    }

    @Test
    void existingShortenEndpointContinuesWorkingWhenRateLimitedIsEnabled() throws Exception {
        RateLimitProperties properties = properties(2, Duration.ofMinutes(1));
        RateLimitService service = new RateLimitService(properties);
        RateLimitFilter filter = new RateLimitFilter(service, properties);

        MockHttpServletRequest request = request("POST", "/api/shorten");
        request.setRemoteAddr("203.0.113.50");

        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();

        filter.doFilter(request, first, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(200));
        filter.doFilter(request, second, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(200));

        assertEquals(200, first.getStatus());
        assertEquals(200, second.getStatus());
    }

    private RateLimitProperties properties(int urlCreation, Duration urlCreationDuration) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setUrlCreation(urlCreation);
        properties.setUrlCreationDuration(urlCreationDuration);
        properties.setQrGeneration(20);
        properties.setQrGenerationDuration(Duration.ofMinutes(1));
        properties.setRedirect(60);
        properties.setRedirectDuration(Duration.ofMinutes(1));
        properties.setDefaultLimit(60);
        properties.setDefaultDuration(Duration.ofMinutes(1));
        return properties;
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr("203.0.113.99");
        return request;
    }
}
