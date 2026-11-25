package com.photopulse.assistant.rerank;

import org.springframework.ai.document.Document;

import java.util.List;

public record RerankResult(
        List<Document> documents,
        boolean reranked,
        String modelVersion,
        long latencyMs,
        String fallbackReason
) {
    public RerankResult {
        documents = List.copyOf(documents);
    }
}