package com.vipul.urlshortener.dto;

public class UrlResponse {

    private String shortUrl;
    private String shortCode;
    private String expiresAt;
    private String expiryIn;
    private String status;

    public UrlResponse(String shortUrl, String shortCode, String expiresAt, String expiryIn) {
        this(shortUrl, shortCode, expiresAt, expiryIn, null);
    }

    public UrlResponse(String shortUrl, String shortCode, String expiresAt, String expiryIn, String status) {
        this.shortUrl = shortUrl;
        this.shortCode = shortCode;
        this.expiresAt = expiresAt;
        this.expiryIn = expiryIn;
        this.status = status;
    }

    public String getShortUrl() {
        return shortUrl;
    }

    public String getShortCode() {
        return shortCode;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(String expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getExpiryIn() {
        return expiryIn;
    }

    public void setExpiryIn(String expiryIn) {
        this.expiryIn = expiryIn;
    }

    public String getStatus() {
        return status;
    }
}