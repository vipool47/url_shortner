package com.vipul.urlshortener.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

@Configuration
public class UrlSafetyConfiguration {

    @Bean
    HttpClient safeBrowsingHttpClient(UrlSafetyProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.getSafeBrowsing().getConnectTimeout())
                .build();
    }
}
