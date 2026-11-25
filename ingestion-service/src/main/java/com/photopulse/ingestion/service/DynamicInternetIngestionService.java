package com.photopulse.ingestion.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.photopulse.ingestion.config.DynamicIngestionProperties;
import com.photopulse.ingestion.config.IngestionProperties;
import com.photopulse.ingestion.dto.DynamicIngestionDtos.*;
import com.photopulse.ingestion.model.ManualDocument;
import com.photopulse.ingestion.repository.ManualDocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class DynamicInternetIngestionService {

    private static final Pattern GITHUB_REPO_PATTERN =
            Pattern.compile("^https?://github\\.com/([^/]+)/([^/#?]+)(?:/tree/([^/]+)(?:/(.*))?)?/?$");

    private final VectorStore vectorStore;
    private final ManualDocumentRepository documentRepository;
    private final DocumentIngestionService staticIngestionService;
    private final StructureAwareChunker chunker;
    private final HtmlBoilerplateCleaner boilerplateCleaner;
    private final IngestionProperties ingestionProperties;
    private final DynamicIngestionProperties dynamicProperties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public DynamicInternetIngestionService(VectorStore vectorStore,
                                           ManualDocumentRepository documentRepository,
                                           DocumentIngestionService staticIngestionService,
                                           StructureAwareChunker chunker,
                                           HtmlBoilerplateCleaner boilerplateCleaner,
                                           IngestionProperties ingestionProperties,
                                           DynamicIngestionProperties dynamicProperties) {
        this(
                vectorStore,
                documentRepository,
                staticIngestionService,
                chunker,
                boilerplateCleaner,
                ingestionProperties,
                dynamicProperties,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(dynamicProperties.getConnectTimeoutMs()))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build()
        );
    }

    public DynamicInternetIngestionService(VectorStore vectorStore,
                                    ManualDocumentRepository documentRepository,
                                    DocumentIngestionService staticIngestionService,
                                    StructureAwareChunker chunker,
                                    HtmlBoilerplateCleaner boilerplateCleaner,
                                    IngestionProperties ingestionProperties,
                                    DynamicIngestionProperties dynamicProperties,
                                    HttpClient httpClient) {
        this.vectorStore = vectorStore;
        this.documentRepository = documentRepository;
        this.staticIngestionService = staticIngestionService;
        this.chunker = chunker;
        this.boilerplateCleaner = boilerplateCleaner;
        this.ingestionProperties = ingestionProperties;
        this.dynamicProperties = dynamicProperties;
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
    }

    public DynamicBatchResponse ingestBatch(DynamicBatchRequest request) {
        String tenantId = request.resolvedTenantId();
        String version = request.resolvedVersion();

        List<SourceIngestionResult> results = new ArrayList<>();
        int succeeded = 0;
        int failed = 0;

        for (InternetSourceItem item : request.sources()) {
            String fetchDate = Instant.now().toString();
            try {
                SourceIngestionResult singleResult = switch (item.type()) {
                    case WEB_PAGE -> ingestWebPage(item, tenantId, version, fetchDate);
                    case REMOTE_PDF -> ingestRemotePdf(item, tenantId, version, fetchDate);
                    case GITHUB_REPO -> ingestGithubRepoDocs(item, tenantId, version, fetchDate);
                };
                results.add(singleResult);
                succeeded++;
                log.info("Successfully ingested internet source [type={}, url={}, chunks={}, fetchDate={}]",
                        item.type(), item.url(), singleResult.chunksIndexed(), fetchDate);
            } catch (Exception e) {
                failed++;
                log.error("Failed to ingest internet source [type={}, url={}]: {} (continuing batch)",
                        item.type(), item.url(), e.getMessage());
                results.add(new SourceIngestionResult(
                        item.url(),
                        item.type(),
                        "FAILED",
                        null,
                        0,
                        fetchDate,
                        e.getMessage()
                ));
            }
        }

        return new DynamicBatchResponse(request.sources().size(), succeeded, failed, results);
    }

    private SourceIngestionResult ingestWebPage(InternetSourceItem item, String tenantId,
                                                String version, String fetchDate) throws Exception {
        byte[] htmlBytes = fetchBytes(item.url());
        String rawHtml = new String(htmlBytes, StandardCharsets.UTF_8);
        String cleanText = boilerplateCleaner.extractCleanText(rawHtml, item.url());

        if (cleanText.isBlank()) {
            throw new IllegalStateException("No meaningful text extracted after boilerplate removal from: " + item.url());
        }

        String checksum = staticIngestionService.computeSha256(cleanText.getBytes(StandardCharsets.UTF_8));
        String cameraModel = resolveOrDefault(item.cameraModel(), "Web Guide");
        String brand = resolveOrDefault(item.brand(), "Online");
        String docId = staticIngestionService.generateStableDocId(item.url(), cameraModel, tenantId);

        List<Document> chunks = buildInternetTextChunks(
                cleanText, docId, version, item.url(), checksum, cameraModel, brand, tenantId, fetchDate, "web_page"
        );

        saveToVectorStoreAndDb(chunks, docId, checksum, version, item.url(), cameraModel, brand, tenantId);
        return new SourceIngestionResult(item.url(), SourceType.WEB_PAGE, "SUCCESS", docId, chunks.size(), fetchDate, null);
    }

    private SourceIngestionResult ingestRemotePdf(InternetSourceItem item, String tenantId,
                                                  String version, String fetchDate) throws Exception {
        byte[] pdfBytes = fetchBytes(item.url());
        if (pdfBytes.length < 4 || pdfBytes[0] != '%' || pdfBytes[1] != 'P' || pdfBytes[2] != 'D' || pdfBytes[3] != 'F') {
            throw new IllegalStateException("Remote resource is not a valid PDF file: " + item.url());
        }

        String checksum = staticIngestionService.computeSha256(pdfBytes);
        String cameraModel = resolveOrDefault(item.cameraModel(), "Remote Manual");
        String brand = resolveOrDefault(item.brand(), "Online");
        String fileName = extractFileNameFromUrl(item.url(), "remote-manual.pdf");
        String docId = staticIngestionService.generateStableDocId(item.url(), cameraModel, tenantId);

        List<Document> chunks = staticIngestionService.parseAndChunk(
                pdfBytes, fileName, docId, version, checksum, cameraModel, brand, tenantId
        );

        if (chunks.isEmpty()) {
            throw new IllegalStateException("No extractable text found in remote PDF: " + item.url());
        }

        // Adaugam metadatele obligatorii pentru Story 2 Task 1 (source_url, fetch_date)
        for (Document chunk : chunks) {
            chunk.getMetadata().put("source_url", item.url());
            chunk.getMetadata().put("fetch_date", fetchDate);
            chunk.getMetadata().put("source_type", "remote_pdf");
        }

        saveToVectorStoreAndDb(chunks, docId, checksum, version, item.url(), cameraModel, brand, tenantId);
        return new SourceIngestionResult(item.url(), SourceType.REMOTE_PDF, "SUCCESS", docId, chunks.size(), fetchDate, null);
    }

    private SourceIngestionResult ingestGithubRepoDocs(InternetSourceItem item, String tenantId,
                                                       String version, String fetchDate) throws Exception {
        Matcher matcher = GITHUB_REPO_PATTERN.matcher(item.url().trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Invalid GitHub repository URL. Expected format: https://github.com/{owner}/{repo}[/tree/{branch}/{path}]");
        }

        String owner = matcher.group(1);
        String repo = matcher.group(2).replaceAll("\\.git$", "");
        String branch = matcher.group(3);
        String pathPrefix = matcher.group(4) != null ? matcher.group(4) : "";

        if (branch == null || branch.isBlank()) {
            branch = resolveDefaultGithubBranch(owner, repo);
        }

        String treeApiUrl = String.format("https://api.github.com/repos/%s/%s/git/trees/%s?recursive=1", owner, repo, branch);
        String treeJson = new String(fetchBytes(treeApiUrl), StandardCharsets.UTF_8);
        JsonNode root = objectMapper.readTree(treeJson);
        JsonNode treeArray = root.path("tree");

        if (!treeArray.isArray()) {
            throw new IllegalStateException("Unable to read file tree from GitHub repository: " + item.url());
        }

        List<String> docPaths = new ArrayList<>();
        for (JsonNode node : treeArray) {
            if (!"blob".equals(node.path("type").asText())) continue;
            String path = node.path("path").asText();
            if (!pathPrefix.isBlank() && !path.startsWith(pathPrefix)) continue;

            String lower = path.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".rst")) {
                docPaths.add(path);
            }
        }

        if (docPaths.isEmpty()) {
            throw new IllegalStateException("No documentation files (.md, .txt, .rst) found in repository: " + item.url());
        }

        int limit = Math.min(docPaths.size(), Math.max(1, dynamicProperties.getMaxRepoFiles()));
        String cameraModel = resolveOrDefault(item.cameraModel(), repo + " Docs");
        String brand = resolveOrDefault(item.brand(), owner);
        String repoDocId = staticIngestionService.generateStableDocId(item.url(), cameraModel, tenantId);

        List<Document> allRepoChunks = new ArrayList<>();
        StringBuilder combinedContent = new StringBuilder();
        int chunkOffset = 0;

        for (int i = 0; i < limit; i++) {
            String filePath = docPaths.get(i);
            String rawFileUrl = String.format("https://raw.githubusercontent.com/%s/%s/%s/%s", owner, repo, branch, filePath);
            try {
                String fileText = chunker.sanitizeText(new String(fetchBytes(rawFileUrl), StandardCharsets.UTF_8));
                if (fileText.isBlank()) continue;

                combinedContent.append(fileText).append("\n");
                String fileChecksum = staticIngestionService.computeSha256(fileText.getBytes(StandardCharsets.UTF_8));
                String fileDocId = repoDocId + "-" + i;

                List<Document> fileChunks = buildInternetTextChunks(
                        fileText, fileDocId, version, rawFileUrl, fileChecksum, cameraModel, brand, tenantId, fetchDate, "github_repo"
                );
                for (Document c : fileChunks) {
                    c.getMetadata().put("repo_url", item.url());
                    c.getMetadata().put("repo_file_path", filePath);
                    c.getMetadata().put("chunk_index", chunkOffset++);
                }
                allRepoChunks.addAll(fileChunks);
            } catch (Exception fileEx) {
                log.warn("Skipping unreadable file {} in repo {}/{}: {}", filePath, owner, repo, fileEx.getMessage());
            }
        }

        if (allRepoChunks.isEmpty()) {
            throw new IllegalStateException("All documentation files in repository were empty or unreadable: " + item.url());
        }

        String repoChecksum = staticIngestionService.computeSha256(combinedContent.toString().getBytes(StandardCharsets.UTF_8));
        saveToVectorStoreAndDb(allRepoChunks, repoDocId, repoChecksum, version, item.url(), cameraModel, brand, tenantId);

        return new SourceIngestionResult(item.url(), SourceType.GITHUB_REPO, "SUCCESS", repoDocId, allRepoChunks.size(), fetchDate, null);
    }

    private String resolveDefaultGithubBranch(String owner, String repo) {
        try {
            String repoApiUrl = String.format("https://api.github.com/repos/%s/%s", owner, repo);
            String json = new String(fetchBytes(repoApiUrl), StandardCharsets.UTF_8);
            JsonNode node = objectMapper.readTree(json);
            String defaultBranch = node.path("default_branch").asText();
            if (defaultBranch != null && !defaultBranch.isBlank()) {
                return defaultBranch;
            }
        } catch (Exception e) {
            log.debug("Could not detect default branch for {}/{}, falling back to 'main': {}", owner, repo, e.getMessage());
        }
        return "main";
    }

    private List<Document> buildInternetTextChunks(String cleanText, String docId, String version,
                                                   String sourceUrl, String checksum, String cameraModel,
                                                   String brand, String tenantId, String fetchDate,
                                                   String sourceType) {
        List<Document> chunks = new ArrayList<>();
        int globalChunkIndex = 0;

        List<String> parentTexts = chunker.chunkWithOverlap(
                cleanText,
                ingestionProperties.getParentChunkSize(),
                ingestionProperties.getChunkOverlap(),
                ingestionProperties.getMinChunkSizeChars()
        );

        for (int pIdx = 0; pIdx < parentTexts.size(); pIdx++) {
            String parentText = parentTexts.get(pIdx);
            String parentId = docId + ":p1:" + pIdx;

            List<String> childTexts = chunker.chunkWithOverlap(
                    parentText,
                    ingestionProperties.getChunkSize(),
                    ingestionProperties.getChunkOverlap(),
                    ingestionProperties.getMinChunkSizeChars()
            );

            for (String childText : childTexts) {
                String deterministicPointId = UUID.nameUUIDFromBytes(
                        (docId + ":" + version + ":" + globalChunkIndex).getBytes(StandardCharsets.UTF_8)
                ).toString();

                Map<String, Object> metadata = new LinkedHashMap<>();
                // Schema Story 1 + Story 2 Task 1
                metadata.put("doc_id", docId);
                metadata.put("version", version);
                metadata.put("source", sourceUrl);
                metadata.put("source_url", sourceUrl);
                metadata.put("fetch_date", fetchDate);
                metadata.put("source_type", sourceType);
                metadata.put("page", 1);
                metadata.put("chunk_index", globalChunkIndex);
                metadata.put("checksum", checksum);
                metadata.put("embedding_model", ingestionProperties.getEmbeddingModel());

                // Compatibilitate cu assistant-service (multi-tenant & parent-child)
                metadata.put("document_id", docId);
                metadata.put("file_name", sourceUrl);
                metadata.put("page_number", 1);
                metadata.put("camera_model", cameraModel.trim());
                metadata.put("brand", brand.trim());
                metadata.put("tenant_id", tenantId.trim());
                metadata.put("parent_id", parentId);
                metadata.put("parent_content", parentText);

                chunks.add(new Document(deterministicPointId, childText, metadata));
                globalChunkIndex++;
            }
        }
        return chunks;
    }

    private void saveToVectorStoreAndDb(List<Document> chunks, String docId, String checksum,
                                        String version, String sourceUrl, String cameraModel,
                                        String brand, String tenantId) {
        int batchSize = Math.max(1, ingestionProperties.getBatchSize());
        for (int i = 0; i < chunks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, chunks.size());
            vectorStore.add(chunks.subList(i, end));
        }

        documentRepository.save(ManualDocument.builder()
                .docId(docId)
                .checksum(checksum)
                .version(version)
                .fileName(sourceUrl)
                .cameraModel(cameraModel.trim())
                .brand(brand.trim())
                .tenantId(tenantId.trim())
                .status(ManualDocument.IngestionStatus.COMPLETED)
                .totalChunks(chunks.size())
                .uploadedAt(LocalDateTime.now())
                .build());
    }

    private byte[] fetchBytes(String urlString) throws IOException, InterruptedException {
        URI uri = URI.create(urlString.trim());
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Unsupported URL scheme (only http/https allowed): " + urlString);
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(dynamicProperties.getReadTimeoutMs()))
                .header("User-Agent", dynamicProperties.getUserAgent())
                .header("Accept", "text/html,application/pdf,application/json,text/plain,*/*")
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " returned while fetching URL: " + urlString);
        }
        return response.body();
    }

    private String resolveOrDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value.trim();
    }

    private String extractFileNameFromUrl(String url, String fallback) {
        try {
            String path = URI.create(url).getPath();
            if (path != null && path.contains("/")) {
                String lastSegment = path.substring(path.lastIndexOf('/') + 1);
                if (!lastSegment.isBlank()) {
                    return lastSegment;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }
}