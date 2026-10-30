package com.vipul.urlshortener.util;

import com.vipul.urlshortener.config.UrlSafetyProperties;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

@Component
public class UrlSafetyValidator {

    private final UrlSafetyProperties properties;

    public UrlSafetyValidator(UrlSafetyProperties properties) {
        this.properties = properties;
    }

    public void validate(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("URL cannot be empty");
        }
        String url = input.trim();
        if (url.length() > properties.getMaxUrlLength()) {
            throw new IllegalArgumentException("URL exceeds the maximum allowed length");
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL format");
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Only HTTP/HTTPS URLs are supported");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("URLs containing embedded user information are not supported");
        }

        String rawAuthority = uri.getRawAuthority();
        if (rawAuthority == null || rawAuthority.isBlank()) {
            throw new IllegalArgumentException("URL must contain a valid hostname");
        }

        Authority authority = parseAuthority(rawAuthority);
        if (authority.ipv6() && !authority.host().contains(":")) {
            throw new IllegalArgumentException("Invalid IPv6 address");
        }
        String normalizedHost = normalizeHost(authority.host());
        validatePort(authority.port());
        rejectOwnShortLinkLoop(uri, normalizedHost, authority.port());
    }

    private Authority parseAuthority(String authority) {
        if (authority.indexOf('@') >= 0) {
            throw new IllegalArgumentException("URLs containing embedded user information are not supported");
        }

        if (authority.startsWith("[")) {
            int closingBracket = authority.indexOf(']');
            if (closingBracket < 0) {
                throw new IllegalArgumentException("Invalid URL hostname");
            }
            String host = authority.substring(1, closingBracket);
            String suffix = authority.substring(closingBracket + 1);
            String port = null;
            if (!suffix.isEmpty()) {
                if (!suffix.startsWith(":") || suffix.length() == 1) {
                    throw new IllegalArgumentException("Invalid URL port");
                }
                port = suffix.substring(1);
            }
            return new Authority(host, port, true);
        }

        int firstColon = authority.indexOf(':');
        int lastColon = authority.lastIndexOf(':');
        if (firstColon != lastColon) {
            throw new IllegalArgumentException("IPv6 addresses must be enclosed in brackets");
        }
        if (lastColon < 0) {
            return new Authority(authority, null, false);
        }
        return new Authority(authority.substring(0, lastColon), authority.substring(lastColon + 1), false);
    }

    private String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL must contain a valid hostname");
        }
        if (host.indexOf(':') >= 0) {
            try {
                InetAddress address = InetAddress.getByName(host);
                if (address instanceof Inet6Address) {
                    return host.toLowerCase(Locale.ROOT);
                }
            } catch (UnknownHostException ignored) {
            }
            throw new IllegalArgumentException("Invalid IPv6 address");
        }
        String hostWithoutTrailingDot = host.endsWith(".")
                ? host.substring(0, host.length() - 1)
                : host;
        if (hostWithoutTrailingDot.matches("[0-9.]+")) {
            if (!isValidIpv4(hostWithoutTrailingDot)) {
                throw new IllegalArgumentException("Invalid or ambiguous IPv4 address");
            }
            return hostWithoutTrailingDot;
        }
        if (isAmbiguousNumericAddress(hostWithoutTrailingDot)) {
            throw new IllegalArgumentException("Invalid or ambiguous IPv4 address");
        }
        if (host.indexOf('%') >= 0) {
            throw new IllegalArgumentException("Invalid URL hostname");
        }

        String ascii;
        try {
            ascii = IDN.toASCII(hostWithoutTrailingDot, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid internationalized hostname");
        }
        String comparisonHost = ascii;
        if (comparisonHost.isEmpty() || comparisonHost.length() > 253) {
            throw new IllegalArgumentException("Invalid hostname length");
        }
        for (String label : comparisonHost.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63) {
                throw new IllegalArgumentException("Invalid hostname");
            }
        }
        return comparisonHost;
    }

    private boolean isValidIpv4(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || (part.length() > 1 && part.startsWith("0"))) {
                return false;
            }
            try {
                if (Integer.parseInt(part) > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private boolean isAmbiguousNumericAddress(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length > 4) {
            return false;
        }
        for (String part : parts) {
            if (!part.matches("(?i)(0x[0-9a-f]*|[0-9]+)")) {
                return false;
            }
        }
        return true;
    }

    private void validatePort(String port) {
        if (port == null) {
            return;
        }
        if (!port.matches("[0-9]{1,5}")) {
            throw new IllegalArgumentException("Invalid URL port");
        }
        try {
            if (Integer.parseInt(port) > 65535) {
                throw new IllegalArgumentException("Invalid URL port");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid URL port");
        }
    }

    private void rejectOwnShortLinkLoop(URI uri, String normalizedHost, String rawPort) {
        String configuredHostname = properties.getPublicShortLinkHostname();
        if (configuredHostname == null || configuredHostname.isBlank()) {
            return;
        }
        String ownHostname = normalizeConfiguredHostname(configuredHostname);
        String path = uri.getRawPath();
        boolean defaultPort = rawPort == null
                || (uri.getScheme().equalsIgnoreCase("http") && Integer.parseInt(rawPort) == 80)
                || (uri.getScheme().equalsIgnoreCase("https") && Integer.parseInt(rawPort) == 443);
        if (defaultPort
                && normalizedHost.equals(ownHostname)
                && path != null
                && path.matches("/[A-Za-z0-9]+")) {
            throw new IllegalArgumentException("A short link cannot point back to this short-link URL");
        }
    }

    private String normalizeConfiguredHostname(String hostname) {
        String configured = hostname.trim();
        if (configured.startsWith("[") && configured.endsWith("]")) {
            configured = configured.substring(1, configured.length() - 1);
        }
        return normalizeHost(configured);
    }

    private record Authority(String host, String port, boolean ipv6) {
    }
}
