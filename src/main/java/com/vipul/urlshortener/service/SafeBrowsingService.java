package com.vipul.urlshortener.service;

import com.vipul.urlshortener.config.UrlSafetyProperties;
import com.vipul.urlshortener.exception.ThreatLookupUnavailableException;
import com.vipul.urlshortener.exception.UnsafeDestinationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
public class SafeBrowsingService implements UrlThreatChecker {

    private static final Logger logger = LoggerFactory.getLogger(SafeBrowsingService.class);
    private static final String ENDPOINT = "https://safebrowsing.googleapis.com/v4/threatMatches:find";
    private static final List<String> THREAT_TYPES = List.of(
            "MALWARE",
            "SOCIAL_ENGINEERING",
            "UNWANTED_SOFTWARE",
            "POTENTIALLY_HARMFUL_APPLICATION"
    );

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final UrlSafetyProperties properties;

    public SafeBrowsingService(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            UrlSafetyProperties properties
    ) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void check(String url) {
        if (!properties.isEnabled()) {
            return;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(buildRequestUri())
                    .timeout(properties.getSafeBrowsing().getResponseTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(url)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logProviderFailure(response.statusCode());
                throw new ThreatLookupUnavailableException();
            }
            inspectResponse(response.body());
        } catch (UnsafeDestinationException | ThreatLookupUnavailableException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Safe Browsing lookup failed: request interrupted");
            throw new ThreatLookupUnavailableException(e);
        } catch (IOException e) {
            logger.warn("Safe Browsing lookup failed: transport error");
            throw new ThreatLookupUnavailableException(e);
        } catch (RuntimeException e) {
            logger.warn("Safe Browsing lookup failed: request or response processing error");
            throw new ThreatLookupUnavailableException();
        }
    }

    private URI buildRequestUri() {
        String encodedApiKey = URLEncoder.encode(
                properties.getSafeBrowsing().getApiKey(),
                StandardCharsets.UTF_8
        );
        return URI.create(ENDPOINT + "?key=" + encodedApiKey);
    }

    private String buildRequestBody(String url) {
        Map<String, Object> request = Map.of(
                "client", Map.of(
                        "clientId", "bitshort",
                        "clientVersion", "1.0"
                ),
                "threatInfo", Map.of(
                        "threatTypes", THREAT_TYPES,
                        "platformTypes", List.of("ANY_PLATFORM"),
                        "threatEntryTypes", List.of("URL"),
                        "threatEntries", List.of(Map.of("url", url))
                )
        );
        return objectMapper.writeValueAsString(request);
    }

    private void inspectResponse(String body) {
        if (body == null || body.isBlank()) {
            throw invalidResponse();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (RuntimeException e) {
            throw invalidResponse();
        }
        if (root == null || !root.isObject()) {
            throw invalidResponse();
        }
        if (root.isEmpty()) {
            return;
        }

        JsonNode matches = root.get("matches");
        if (matches == null || !matches.isArray() || matches.isEmpty()) {
            throw invalidResponse();
        }
        for (JsonNode match : matches) {
            if (!isValidMatch(match)) {
                throw invalidResponse();
            }
        }
        throw new UnsafeDestinationException();
    }

    private ThreatLookupUnavailableException invalidResponse() {
        logger.warn("Safe Browsing lookup failed: invalid provider response");
        return new ThreatLookupUnavailableException();
    }

    private boolean isValidMatch(JsonNode match) {
        if (match == null || !match.isObject()) {
            return false;
        }
        JsonNode threatType = match.get("threatType");
        JsonNode platformType = match.get("platformType");
        JsonNode threatEntryType = match.get("threatEntryType");
        JsonNode threat = match.get("threat");
        JsonNode matchedUrl = threat == null || !threat.isObject() ? null : threat.get("url");
        return threatType != null && threatType.isTextual()
                && platformType != null && platformType.isTextual()
                && threatEntryType != null && threatEntryType.isTextual()
                && matchedUrl != null && matchedUrl.isTextual();
    }

    private void logProviderFailure(int status) {
        if (status == 403) {
            logger.warn(
                    "Safe Browsing lookup failed with HTTP 403: verify the API is enabled and the key has the required API restriction"
            );
        } else if (status == 429) {
            logger.warn("Safe Browsing lookup failed with HTTP 429: quota or rate limit exceeded");
        } else {
            logger.warn("Safe Browsing lookup failed with HTTP status {}: provider error", status);
        }
    }
}
