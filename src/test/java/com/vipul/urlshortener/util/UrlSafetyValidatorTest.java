package com.vipul.urlshortener.util;

import com.vipul.urlshortener.config.UrlSafetyProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UrlSafetyValidatorTest {

    private UrlSafetyProperties properties;
    private UrlSafetyValidator validator;

    @BeforeEach
    void setUp() {
        properties = new UrlSafetyProperties();
        validator = new UrlSafetyValidator(properties);
    }

    @Test
    void acceptsHttpAndHttps() {
        assertDoesNotThrow(() -> validator.validate("http://example.com/path"));
        assertDoesNotThrow(() -> validator.validate("https://example.com/path"));
    }

    @Test
    void rejectsMissingOrMalformedHostnameAndUnsupportedSchemes() {
        for (String url : new String[]{
                "https:///path",
                "http://",
                "http://exa mple.com",
                "javascript:alert(1)",
                "ftp://example.com",
                "https://example.com:abc/path"
        }) {
            assertThrows(IllegalArgumentException.class, () -> validator.validate(url), url);
        }
    }

    @Test
    void rejectsAuthorityUserInformationWithoutEchoingTheInput() {
        String deceptiveUrl = "https://trusted.example:secret@evil.example/login";

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> validator.validate(deceptiveUrl)
        );

        assertTrue(error.getMessage().contains("user information"));
        assertFalse(error.getMessage().contains("trusted.example"));
        assertDoesNotThrow(() -> validator.validate("https://example.com/path/@value?email=a@b.example"));
    }

    @Test
    void acceptsValidIpv4AndIpv6Literals() {
        assertDoesNotThrow(() -> validator.validate("http://192.0.2.10:8080/path"));
        assertDoesNotThrow(() -> validator.validate("http://192.0.2.10./path"));
        assertDoesNotThrow(() -> validator.validate("https://[2001:db8::1]:8443/path"));
        assertDoesNotThrow(() -> validator.validate("http://[::1]/"));
    }

    @Test
    void rejectsMalformedOrAmbiguousIpLiterals() {
        for (String url : new String[]{
                "http://256.1.1.1/",
                "http://127.1/",
                "http://192.168.001.1/",
                "http://0x7f.0.0.1/",
                "http://0x7f000001/",
                "http://[xyz]/",
                "http://[2001:::1]/",
                "http://2001:db8::1/"
        }) {
            assertThrows(IllegalArgumentException.class, () -> validator.validate(url), url);
        }
    }

    @Test
    void acceptsUnicodeAndPunycodeHostnames() {
        assertDoesNotThrow(() -> validator.validate("https://bücher.example/straße?q=1"));
        assertDoesNotThrow(() -> validator.validate("https://xn--bcher-kva.example/path"));
        assertThrows(IllegalArgumentException.class, () -> validator.validate("https://xn--/path"));
    }

    @Test
    void rejectsOnlyTheConfiguredHostnameShortCodeRedirectLoop() {
        properties.setPublicShortLinkHostname("Short.Example.");

        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("https://short.example/Ab12"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("https://SHORT.EXAMPLE./Ab12"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("https://short.example:443/Ab12"));
        assertDoesNotThrow(() -> validator.validate("https://short.example:8443/Ab12"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate("https://short.example/about"));
        assertDoesNotThrow(() -> validator.validate("https://short.example/about/page"));
        assertDoesNotThrow(() -> validator.validate("https://short.example/"));
        assertDoesNotThrow(() -> validator.validate("https://api.short.example/Ab12"));
        assertDoesNotThrow(() -> validator.validate("https://short.example.evil.test/Ab12"));
    }

    @Test
    void enforcesConfiguredMaximumLengthAtBoundary() {
        String prefix = "https://example.com/";
        properties.setMaxUrlLength(prefix.length() + 3);

        assertDoesNotThrow(() -> validator.validate(prefix + "abc"));
        assertThrows(IllegalArgumentException.class,
                () -> validator.validate(prefix + "abcd"));
    }
}
