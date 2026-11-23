package com.photopulse.ingestion.service;

import com.photopulse.ingestion.config.IngestionProperties;
import com.photopulse.ingestion.model.ManualDocument;
import com.photopulse.ingestion.repository.ManualDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentIngestionService {

    private final VectorStore vectorStore;
    private final ManualDocumentRepository documentRepository;
    private final StructureAwareChunker chunker;
    private final IngestionProperties properties;

    public record ExtractedPage(int pageNumber, String text) {}

    public String computeSha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    public String generateStableDocId(String fileName, String cameraModel, String tenantId) {
        return (tenantId + "_" + cameraModel + "_" + fileName)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    /**
     * Extrage textul pagina cu pagina din PDF folosind Apache PDFBox cu sortByPosition=true
     * (pastreaza ordinea corecta in tabele si layout-uri pe coloane multiple).
     */
    public List<ExtractedPage> extractPages(byte[] fileBytes, String fileName) throws IOException {
        if (fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            List<ExtractedPage> pages = new ArrayList<>();
            try (PDDocument pdf = Loader.loadPDF(fileBytes)) {
                int totalPages = pdf.getNumberOfPages();
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);

                for (int page = 1; page <= totalPages; page++) {
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    String rawText = stripper.getText(pdf);
                    String clean = chunker.sanitizeText(rawText);
                    if (!clean.isBlank()) {
                        pages.add(new ExtractedPage(page, clean));
                    }
                }
            }
            return pages;
        }

        ByteArrayResource resource = new ByteArrayResource(fileBytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
        TikaDocumentReader tikaReader = new TikaDocumentReader(resource);
        List<Document> docs = tikaReader.get();
        List<ExtractedPage> pages = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            String clean = chunker.sanitizeText(docs.get(i).getText());
            if (!clean.isBlank()) {
                pages.add(new ExtractedPage(i + 1, clean));
            }
        }
        return pages;
    }

    public List<Document> parseAndChunk(byte[] fileBytes, String fileName, String docId,
                                        String version, String checksum, String cameraModel,
                                        String brand, String tenantId) throws IOException {
        List<ExtractedPage> pages = extractPages(fileBytes, fileName);
        List<Document> allChildChunks = new ArrayList<>();
        int globalChunkIndex = 0;

        for (ExtractedPage page : pages) {
            List<String> parentTexts = chunker.chunkWithOverlap(
                    page.text(),
                    properties.getParentChunkSize(),
                    properties.getChunkOverlap(),
                    properties.getMinChunkSizeChars()
            );

            for (int pIdx = 0; pIdx < parentTexts.size(); pIdx++) {
                String parentText = parentTexts.get(pIdx);
                String parentId = docId + ":p" + page.pageNumber() + ":" + pIdx;

                List<String> childTexts = chunker.chunkWithOverlap(
                        parentText,
                        properties.getChunkSize(),
                        properties.getChunkOverlap(),
                        properties.getMinChunkSizeChars()
                );

                for (String childText : childTexts) {
                    String deterministicPointId = UUID.nameUUIDFromBytes(
                            (docId + ":" + version + ":" + globalChunkIndex).getBytes(StandardCharsets.UTF_8)
                    ).toString();

                    Map<String, Object> metadata = new LinkedHashMap<>();
                    // Schema obligatorie Story 1 - Task 1
                    metadata.put("doc_id", docId);
                    metadata.put("version", version);
                    metadata.put("source", fileName);
                    metadata.put("page", page.pageNumber());
                    metadata.put("chunk_index", globalChunkIndex);
                    metadata.put("checksum", checksum);
                    metadata.put("embedding_model", properties.getEmbeddingModel());

                    // Compatibilitate cu assistant-service (multi-tenant & parent-child)
                    metadata.put("document_id", docId);
                    metadata.put("file_name", fileName);
                    metadata.put("page_number", page.pageNumber());
                    metadata.put("camera_model", cameraModel.trim());
                    metadata.put("brand", brand.trim());
                    metadata.put("tenant_id", tenantId.trim());
                    metadata.put("parent_id", parentId);
                    metadata.put("parent_content", parentText);

                    allChildChunks.add(new Document(deterministicPointId, childText, metadata));
                    globalChunkIndex++;
                }
            }
        }

        return allChildChunks;
    }

    @Async
    public void processDocumentAsync(UUID recordId, byte[] fileBytes, String fileName,
                                     String docId, String version, String checksum,
                                     String cameraModel, String brand, String tenantId) {
        log.info("Starting async PDF ingestion: docId={}, version={}, file={}, checksum={}, model={}, chunkSize={}, overlap={}",
                docId, version, fileName, checksum, cameraModel, properties.getChunkSize(), properties.getChunkOverlap());

        ManualDocument manualDoc = documentRepository.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException("Document record not found: " + recordId));

        try {
            List<Document> allChildChunks = parseAndChunk(
                    fileBytes, fileName, docId, version, checksum, cameraModel, brand, tenantId
            );

            if (allChildChunks.isEmpty()) {
                throw new IllegalStateException("No extractable text found in document: " + fileName);
            }

            int batchSize = Math.max(1, properties.getBatchSize());
            for (int i = 0; i < allChildChunks.size(); i += batchSize) {
                int end = Math.min(i + batchSize, allChildChunks.size());
                vectorStore.add(allChildChunks.subList(i, end));
                log.info("Indexed batch {}/{} for docId={}", end, allChildChunks.size(), docId);
            }

            manualDoc.setStatus(ManualDocument.IngestionStatus.COMPLETED);
            manualDoc.setTotalChunks(allChildChunks.size());
            documentRepository.save(manualDoc);
            log.info("Completed ingestion for docId={} ({} chunks, embeddingModel={})",
                    docId, allChildChunks.size(), properties.getEmbeddingModel());

        } catch (Exception e) {
            log.error("Failed to ingest document {} (docId={}): {}", fileName, docId, e.getMessage(), e);
            manualDoc.setStatus(ManualDocument.IngestionStatus.FAILED);
            manualDoc.setErrorMessage(e.getMessage());
            documentRepository.save(manualDoc);
        }
    }
}