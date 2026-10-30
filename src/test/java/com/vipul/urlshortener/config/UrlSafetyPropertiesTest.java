package com.vipul.urlshortener.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.*;

class UrlSafetyPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void missingOrBlankKeyFailsStartupWhenScreeningIsEnabled() {
        UrlSafetyProperties missing = new UrlSafetyProperties();
        IllegalStateException missingError = assertThrows(
                IllegalStateException.class,
                missing::afterPropertiesSet
        );
        assertTrue(missingError.getMessage().contains("SAFE_BROWSING_API_KEY"));

        UrlSafetyProperties blank = new UrlSafetyProperties();
        blank.getSafeBrowsing().setApiKey("   ");
        IllegalStateException blankError = assertThrows(
                IllegalStateException.class,
                blank::afterPropertiesSet
        );
        assertTrue(blankError.getMessage().contains("SAFE_BROWSING_API_KEY"));
    }

    @Test
    void unresolvedPlaceholderFailsStartupWithoutRevealingIt() {
        UrlSafetyProperties properties = new UrlSafetyProperties();
        properties.getSafeBrowsing().setApiKey("${SAFE_BROWSING_API_KEY}");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                properties::afterPropertiesSet
        );

        assertTrue(error.getMessage().contains("SAFE_BROWSING_API_KEY"));
        assertFalse(error.getMessage().contains("${"));
    }

    @Test
    void missingKeyDoesNotPreventStartupWhenScreeningIsDisabled() {
        UrlSafetyProperties properties = new UrlSafetyProperties();
        properties.setEnabled(false);

        assertDoesNotThrow(properties::afterPropertiesSet);
    }

    @Test
    void applicationContextFailsToStartWithoutKeyWhenScreeningIsEnabled() {
        contextRunner
                .withPropertyValues("bitshort.url-safety.enabled=true")
                .run(context -> {
                    assertNotNull(context.getStartupFailure());
                    assertTrue(context.getStartupFailure().getMessage().contains("SAFE_BROWSING_API_KEY"));
                });
    }

    @Test
    void applicationContextStartsWithoutKeyWhenScreeningIsDisabled() {
        contextRunner
                .withPropertyValues("bitshort.url-safety.enabled=false")
                .run(context -> assertNull(context.getStartupFailure()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(UrlSafetyProperties.class)
    static class TestConfiguration {
    }
}
