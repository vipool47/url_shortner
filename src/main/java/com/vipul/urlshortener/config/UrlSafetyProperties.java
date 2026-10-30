package com.vipul.urlshortener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.beans.factory.InitializingBean;

import java.time.Duration;

@ConfigurationProperties(prefix = "bitshort.url-safety")
public class UrlSafetyProperties implements InitializingBean {

    private boolean enabled = true;
    private boolean failClosed = true;
    private int maxUrlLength = 8192;
    private String publicShortLinkHostname = "";
    private final SafeBrowsing safeBrowsing = new SafeBrowsing();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFailClosed() {
        return failClosed;
    }

    public void setFailClosed(boolean failClosed) {
        this.failClosed = failClosed;
    }

    public int getMaxUrlLength() {
        return maxUrlLength;
    }

    public void setMaxUrlLength(int maxUrlLength) {
        this.maxUrlLength = maxUrlLength;
    }

    public String getPublicShortLinkHostname() {
        return publicShortLinkHostname;
    }

    public void setPublicShortLinkHostname(String publicShortLinkHostname) {
        this.publicShortLinkHostname = publicShortLinkHostname;
    }

    public SafeBrowsing getSafeBrowsing() {
        return safeBrowsing;
    }

    @Override
    public void afterPropertiesSet() {
        String apiKey = safeBrowsing.getApiKey();
        if (enabled && (apiKey == null || apiKey.isBlank() || isUnresolvedPlaceholder(apiKey))) {
            throw new IllegalStateException(
                    "SAFE_BROWSING_API_KEY is required when URL safety screening is enabled"
            );
        }
    }

    private boolean isUnresolvedPlaceholder(String value) {
        return (value.startsWith("${") && value.endsWith("}"))
                || value.startsWith("PASTE_YOUR_") && value.endsWith("_HERE");
    }

    public static class SafeBrowsing {
        private String apiKey = "";
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration responseTimeout = Duration.ofSeconds(4);

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getResponseTimeout() {
            return responseTimeout;
        }

        public void setResponseTimeout(Duration responseTimeout) {
            this.responseTimeout = responseTimeout;
        }
    }
}
