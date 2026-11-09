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
    private Duration inlineBudget = Duration.ofSeconds(1);
    private Duration safeVerdictFreshness = Duration.ofHours(24);
    private Duration safeVerdictTtl = Duration.ofHours(1);
    private final SafeBrowsing safeBrowsing = new SafeBrowsing();
    private final Verification verification = new Verification();

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

    public Duration getInlineBudget() {
        return inlineBudget;
    }

    public void setInlineBudget(Duration inlineBudget) {
        this.inlineBudget = inlineBudget;
    }

    public Duration getSafeVerdictFreshness() {
        return safeVerdictFreshness;
    }

    public void setSafeVerdictFreshness(Duration safeVerdictFreshness) {
        this.safeVerdictFreshness = safeVerdictFreshness;
    }

    public Duration getSafeVerdictTtl() {
        return safeVerdictTtl;
    }

    public void setSafeVerdictTtl(Duration safeVerdictTtl) {
        this.safeVerdictTtl = safeVerdictTtl;
    }

    public Verification getVerification() {
        return verification;
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
        private Duration connectTimeout = Duration.ofSeconds(1);
        private Duration responseTimeout = Duration.ofMillis(1500);

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

    public static class Verification {
        private long delayMs = 10_000;
        private int batchSize = 25;
        private Duration maxPendingAge = Duration.ofHours(24);

        public long getDelayMs() {
            return delayMs;
        }

        public void setDelayMs(long delayMs) {
            this.delayMs = delayMs;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public Duration getMaxPendingAge() {
            return maxPendingAge;
        }

        public void setMaxPendingAge(Duration maxPendingAge) {
            this.maxPendingAge = maxPendingAge;
        }
    }
}
