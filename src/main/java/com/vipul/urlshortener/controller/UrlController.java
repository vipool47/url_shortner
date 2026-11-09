package com.vipul.urlshortener.controller;

import com.vipul.urlshortener.dto.ErrorResponse;
import com.vipul.urlshortener.dto.UrlRequest;
import com.vipul.urlshortener.dto.UrlResponse;
import com.vipul.urlshortener.dto.UrlStatusResponse;
import com.vipul.urlshortener.entity.UrlMapping;
import com.vipul.urlshortener.exception.InvalidExpiryException;
import com.vipul.urlshortener.exception.LinkBlockedException;
import com.vipul.urlshortener.exception.LinkExpiredException;
import com.vipul.urlshortener.exception.LinkPendingException;
import com.vipul.urlshortener.service.UrlServiceImpl;
import com.vipul.urlshortener.util.ExpiryUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

@RestController
@CrossOrigin(origins = "*")
public class UrlController {

    private final UrlServiceImpl service;

    public UrlController(UrlServiceImpl service) {
        this.service = service;
    }

    @PostMapping("/api/shorten")
    public ResponseEntity<?> shortenUrl(@RequestBody UrlRequest request) {
        try {

            if (request.getLongUrl() == null || request.getLongUrl().trim().isEmpty()) {
                return ResponseEntity
                        .badRequest()
                        .body(new ErrorResponse(
                                "VALIDATION_ERROR",
                                "URL cannot be empty"
                        ));
            }

            UrlMapping mapping = service.shortenUrlWithStatus(
                    request.getLongUrl(),
                    request.getCustomCode(),
                    request.getExpiryTimeMinutes(),
                    request.getExpiryTimeHours(),
                    request.getExpiryTimeDays(),
                    request.getExpiresAtTimestamp()
            );
            String shortCode = mapping.getShortCode();

            String shortUrl = ServletUriComponentsBuilder
                    .fromCurrentContextPath()
                    .path("/")
                    .path(shortCode)
                    .toUriString();

            String expiresAt = ExpiryUtil.formatDateTime(mapping.getExpiresAt());
            String expiryIn = mapping.getExpiresAt() != null ? ExpiryUtil.getTimeUntilExpiry(mapping.getExpiresAt()) : null;

            return ResponseEntity.ok(
                    new UrlResponse(shortUrl, shortCode, expiresAt, expiryIn, mapping.getSafeStatus().name())
            );

        } catch (InvalidExpiryException e) {
            return ResponseEntity
                    .badRequest()
                    .body(new ErrorResponse(
                            "INVALID_EXPIRY",
                            e.getMessage()
                    ));

        } catch (IllegalArgumentException e) {

            return ResponseEntity
                    .badRequest()
                    .body(new ErrorResponse(
                            "VALIDATION_ERROR",
                            e.getMessage()
                    ));

        } catch (Exception e) {

            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse(
                            "SERVER_ERROR",
                            "An unexpected error occurred"
                    ));
        }
    }

    @GetMapping("/api/urls/{shortCode}/status")
    public ResponseEntity<UrlStatusResponse> getUrlStatus(@PathVariable String shortCode) {
        return service.findByShortCode(shortCode)
                .filter(mapping -> !mapping.isExpired())
                .map(mapping -> ResponseEntity.ok(new UrlStatusResponse(mapping.getSafeStatus().name())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{shortCode:[a-zA-Z0-9]+}")
    public ResponseEntity<?> redirect(@PathVariable String shortCode) {

        try {

            String longUrl = service.getLongUrl(shortCode);

            return ResponseEntity
                    .status(302)
                    .location(URI.create(longUrl))
                    .build();

        } catch (LinkExpiredException ex) {
            return ResponseEntity.notFound().build();

        } catch (LinkBlockedException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("This link was blocked because its destination was flagged as unsafe.");

        } catch (LinkPendingException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "30")
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("This link is still being safety-checked. Please try again shortly.");

        } catch (RuntimeException ex) {

            return ResponseEntity.notFound().build();
        }
    }
}