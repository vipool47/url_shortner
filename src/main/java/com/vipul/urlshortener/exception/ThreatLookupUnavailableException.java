package com.vipul.urlshortener.exception;

public class ThreatLookupUnavailableException extends RuntimeException {
    public ThreatLookupUnavailableException() {
        super("URL safety screening is temporarily unavailable");
    }

    public ThreatLookupUnavailableException(Throwable cause) {
        super("URL safety screening is temporarily unavailable", cause);
    }
}
