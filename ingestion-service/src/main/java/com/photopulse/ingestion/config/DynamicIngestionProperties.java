package com.photopulse.ingestion.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.dynamic-ingestion")
@Getter
@Setter
public class DynamicIngestionProperties {
    private int connectTimeoutMs = 10000;
    private int readTimeoutMs = 15000;
    private int maxRepoFiles = 15;
    private String userAgent = "PhotoPulse-IngestionBot/1.0";
}