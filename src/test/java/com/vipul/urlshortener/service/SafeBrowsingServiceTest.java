package com.vipul.urlshortener.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vipul.urlshortener.config.UrlSafetyProperties;
import com.vipul.urlshortener.exception.ThreatLookupUnavailableException;
import com.vipul.urlshortener.exception.UnsafeDestinationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SafeBrowsingServiceTest {

    private static final String API_KEY = "test key+&/secret";
    private static final String SUBMITTED_URL = "https://example.com/private?token=sensitive";

    private HttpClient httpClient;
    private UrlSafetyProperties properties;
    private ObjectMapper objectMapper;
    private SafeBrowsingService service;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        properties = new UrlSafetyProperties();
        properties.getSafeBrowsing().setApiKey(API_KEY);
        properties.getSafeBrowsing().setResponseTimeout(Duration.ofMillis(1750));
        objectMapper = new ObjectMapper();
        service = new SafeBrowsingService(httpClient, objectMapper, properties);
    }

    @Test
    void emptyObjectMeansNoThreatWasReported() throws Exception {
        respond(200, "{}");

        assertDoesNotThrow(() -> service.check(SUBMITTED_URL));
    }

    @Test
    void nonEmptyMatchesArrayRejectsDestination() throws Exception {
        respond(200, """
                {"matches":[{
                  "threatType":"MALWARE",
                  "platformType":"ANY_PLATFORM",
                  "threatEntryType":"URL",
                  "threat":{"url":"https://example.com/private?token=sensitive"}
                }]}
                """);

        UnsafeDestinationException error = assertThrows(
                UnsafeDestinationException.class,
                () -> service.check(SUBMITTED_URL)
        );

        assertFalse(error.getMessage().contains(SUBMITTED_URL));
        assertFalse(error.getMessage().contains(API_KEY));
    }

    @Test
    void forbiddenResponseLogsSafeConfigurationMessage() throws Exception {
        respond(403, "{\"error\":{\"message\":\"sensitive provider body\"}}");

        List<String> logs = captureLogs(() ->
                assertThrows(ThreatLookupUnavailableException.class, () -> service.check(SUBMITTED_URL)));

        assertTrue(logs.stream().anyMatch(message ->
                message.contains("HTTP 403") && message.contains("API is enabled")));
        assertLogsContainNoSecrets(logs);
    }

    @Test
    void rateLimitedResponseLogsQuotaMessage() throws Exception {
        respond(429, "{\"error\":{\"message\":\"sensitive provider body\"}}");

        List<String> logs = captureLogs(() ->
                assertThrows(ThreatLookupUnavailableException.class, () -> service.check(SUBMITTED_URL)));

        assertTrue(logs.stream().anyMatch(message ->
                message.contains("HTTP 429") && message.contains("quota or rate limit")));
        assertLogsContainNoSecrets(logs);
    }

    @Test
    void otherProviderErrorsFailClosed() throws Exception {
        respond(500, "internal provider detail");

        List<String> logs = captureLogs(() ->
                assertThrows(ThreatLookupUnavailableException.class, () -> service.check(SUBMITTED_URL)));

        assertTrue(logs.stream().anyMatch(message -> message.contains("HTTP status 500")));
        assertLogsContainNoSecrets(logs);
    }

    @Test
    void malformedAndUnexpectedResponsesFailClosed() throws Exception {
        for (String body : List.of(
                "not-json",
                "[]",
                "{\"other\":{}}",
                "{\"matches\":null}",
                "{\"matches\":{}}",
                "{\"matches\":[]}",
                "{\"matches\":[null]}",
                "{\"matches\":[{}]}",
                "{\"matches\":[{\"threatType\":\"MALWARE\"}]}"
        )) {
            respond(200, body);
            assertThrows(ThreatLookupUnavailableException.class,
                    () -> service.check(SUBMITTED_URL), body);
        }
    }

    @Test
    void timeoutPreservesCauseButDoesNotLogSecrets() throws Exception {
        HttpTimeoutException timeout = new HttpTimeoutException("request timed out");
        doThrow(timeout).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        List<String> logs = captureLogs(() -> {
            ThreatLookupUnavailableException error = assertThrows(
                    ThreatLookupUnavailableException.class,
                    () -> service.check(SUBMITTED_URL)
            );
            assertSame(timeout, error.getCause());
            assertFalse(error.getMessage().contains(SUBMITTED_URL));
            assertFalse(error.getMessage().contains(API_KEY));
        });

        assertTrue(logs.stream().anyMatch(message -> message.contains("transport error")));
        assertLogsContainNoSecrets(logs);
    }

    @Test
    void connectionFailurePreservesCauseButDoesNotLogSecrets() throws Exception {
        IOException connectionFailure = new IOException("connection refused");
        doThrow(connectionFailure).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        List<String> logs = captureLogs(() -> {
            ThreatLookupUnavailableException error = assertThrows(
                    ThreatLookupUnavailableException.class,
                    () -> service.check(SUBMITTED_URL)
            );
            assertSame(connectionFailure, error.getCause());
        });

        assertLogsContainNoSecrets(logs);
    }

    @Test
    void requestUsesExpectedEndpointHeadersTimeoutAndJsonBody() throws Exception {
        respond(200, "{}");

        service.check(SUBMITTED_URL);

        verify(httpClient).send(argThat((HttpRequest request) -> {
            try {
                String rawQuery = request.uri().getRawQuery();
                JsonNode body = objectMapper.readTree(request.bodyPublisher().orElseThrow()
                        .contentLength() == 0 ? "" : readBody(request));
                JsonNode threatInfo = body.get("threatInfo");
                List<String> threatTypes = asStrings(threatInfo.get("threatTypes"));
                return request.method().equals("POST")
                        && request.uri().getHost().equals("safebrowsing.googleapis.com")
                        && request.uri().getPath().equals("/v4/threatMatches:find")
                        && rawQuery.equals("key=test+key%2B%26%2Fsecret")
                        && request.headers().firstValue("Content-Type").orElse("").equals("application/json")
                        && request.timeout().orElseThrow().equals(Duration.ofMillis(1750))
                        && body.get("client").get("clientId").asString().equals("bitshort")
                        && body.get("client").get("clientVersion").asString().equals("1.0")
                        && threatTypes.equals(List.of(
                                "MALWARE",
                                "SOCIAL_ENGINEERING",
                                "UNWANTED_SOFTWARE",
                                "POTENTIALLY_HARMFUL_APPLICATION"
                        ))
                        && asStrings(threatInfo.get("platformTypes")).equals(List.of("ANY_PLATFORM"))
                        && asStrings(threatInfo.get("threatEntryTypes")).equals(List.of("URL"))
                        && threatInfo.get("threatEntries").get(0).get("url").asString().equals(SUBMITTED_URL);
            } catch (Exception e) {
                return false;
            }
        }), any());
    }

    @Test
    void disabledScreeningDoesNotCallProvider() {
        properties.setEnabled(false);

        assertDoesNotThrow(() -> service.check(SUBMITTED_URL));

        verifyNoInteractions(httpClient);
    }

    private List<String> asStrings(JsonNode values) {
        List<String> result = new ArrayList<>();
        for (JsonNode value : values) {
            result.add(value.asString());
        }
        return result;
    }

    private String readBody(HttpRequest request) throws Exception {
        var buffer = new java.io.ByteArrayOutputStream();
        var completed = new java.util.concurrent.CompletableFuture<String>();
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(java.nio.ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                buffer.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                completed.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                completed.complete(buffer.toString(java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        return completed.join();
    }

    private List<String> captureLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(SafeBrowsingService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private void assertLogsContainNoSecrets(List<String> logs) {
        String combined = String.join("\n", logs);
        assertFalse(combined.contains(API_KEY));
        assertFalse(combined.contains(SUBMITTED_URL));
        assertFalse(combined.contains("sensitive provider body"));
    }

    private void respond(int status, String body) throws Exception {
        @SuppressWarnings("unchecked")
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        doReturn(response).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
}
