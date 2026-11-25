package com.photopulse.assistant.rerank;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.reranker")
@Getter
@Setter
public class RerankerProperties {
    private boolean enabled = true;
    private int topN = 20;
    private int topK = 5;
    private String modelVersion = "BAAI/bge-reranker-v2-m3@rev-2025.10";
    private String endpointUrl = "http://localhost:11434/api/rerank";
    private int timeoutMs = 3000;
}