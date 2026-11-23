package com.photopulse.ingestion.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.ingestion")
@Getter
@Setter
public class IngestionProperties {
    private int chunkSize = 250;
    private int chunkOverlap = 50;
    private int parentChunkSize = 1000;
    private int minChunkSizeChars = 80;
    private String embeddingModel = "nomic-embed-text";
    private int batchSize = 20;
}