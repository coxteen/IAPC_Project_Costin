package com.photopulse.assistant.rerank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

@Component
@Slf4j
public class CrossEncoderReranker {

    private final RerankerProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Autowired
    public CrossEncoderReranker(RerankerProperties properties, ObjectMapper objectMapper) {
        this(
                properties,
                objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(Math.max(500, properties.getTimeoutMs())))
                        .build()
        );
    }

    public CrossEncoderReranker(RerankerProperties properties,
                                ObjectMapper objectMapper,
                                HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /**
     * Reordoneaza candidatii (top-N) folosind un Cross-Encoder cu versiune pinned si returneaza top-k.
     * Daca reranker-ul este dezactivat sau esueaza, pastreaza ordinea initiala (trunchiata la top-k).
     */
    public RerankResult rerank(String query, List<Document> candidates) {
        long startNanos = System.nanoTime();
        int topK = Math.max(1, properties.getTopK());

        if (candidates == null || candidates.isEmpty()) {
            return new RerankResult(List.of(), false, properties.getModelVersion(), 0L, "empty_candidates");
        }

        List<Document> originalTopK = candidates.stream().limit(topK).toList();

        if (!properties.isEnabled()) {
            long elapsedMs = elapsedMillis(startNanos);
            return new RerankResult(originalTopK, false, properties.getModelVersion(), elapsedMs, "reranker_disabled");
        }

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", properties.getModelVersion());
            payload.put("query", query);
            payload.put("top_n", topK);
            payload.put("documents", candidates.stream().map(this::extractScoringText).toList());

            String jsonBody = objectMapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getEndpointUrl()))
                    .timeout(Duration.ofMillis(Math.max(500, properties.getTimeoutMs())))
                    .header("Content-Type", "application/json")
                    .header("X-Reranker-Model-Version", properties.getModelVersion())
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Reranker HTTP status " + response.statusCode());
            }

            List<Document> reordered = parseAndSortResults(response.body(), candidates, topK);
            long elapsedMs = elapsedMillis(startNanos);

            log.info("Cross-encoder reranking succeeded [model={}, candidates={}, topK={}, latencyMs={}]",
                    properties.getModelVersion(), candidates.size(), reordered.size(), elapsedMs);

            return new RerankResult(reordered, true, properties.getModelVersion(), elapsedMs, null);

        } catch (Exception ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            long elapsedMs = elapsedMillis(startNanos);
            log.warn("Cross-encoder reranker failed after {} ms (model={}), falling back to initial vector order: {}",
                    elapsedMs, properties.getModelVersion(), ex.getMessage());

            return new RerankResult(originalTopK, false, properties.getModelVersion(), elapsedMs, ex.getMessage());
        }
    }

    private List<Document> parseAndSortResults(String responseBody, List<Document> candidates, int topK) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode resultsNode = root.has("results") ? root.get("results") : root;

        if (resultsNode == null || !resultsNode.isArray() || resultsNode.isEmpty()) {
            throw new IllegalStateException("Invalid or empty reranker results payload");
        }

        record ScoredCandidate(int index, double score) {}
        List<ScoredCandidate> scored = new ArrayList<>();
        Set<Integer> seenIndices = new HashSet<>();

        for (JsonNode item : resultsNode) {
            if (!item.has("index") || !item.has("relevance_score")) {
                throw new IllegalStateException("Missing 'index' or 'relevance_score' in reranker response");
            }
            int idx = item.get("index").asInt(-1);
            double score = item.get("relevance_score").asDouble(Double.NaN);

            if (idx < 0 || idx >= candidates.size() || Double.isNaN(score)) {
                throw new IllegalStateException("Out-of-bounds index or NaN score in reranker response");
            }
            if (seenIndices.add(idx)) {
                scored.add(new ScoredCandidate(idx, score));
            }
        }

        scored.sort(Comparator.comparingDouble(ScoredCandidate::score).reversed());

        List<Document> rankedDocs = new ArrayList<>(Math.min(topK, scored.size()));
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            ScoredCandidate sc = scored.get(i);
            Document orig = candidates.get(sc.index());
            Map<String, Object> enrichedMeta = new LinkedHashMap<>(orig.getMetadata());
            enrichedMeta.put("rerank_score", sc.score());
            enrichedMeta.put("rerank_model_version", properties.getModelVersion());
            rankedDocs.add(new Document(orig.getId(), orig.getText(), enrichedMeta));
        }

        return rankedDocs;
    }

    private String extractScoringText(Document doc) {
        Object parent = doc.getMetadata().get("parent_content");
        if (parent instanceof String s && !s.isBlank()) {
            return s;
        }
        return doc.getText() != null ? doc.getText() : "";
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}