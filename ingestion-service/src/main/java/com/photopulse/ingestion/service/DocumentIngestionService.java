package com.photopulse.ingestion.service;

import com.photopulse.ingestion.model.ManualDocument;
import com.photopulse.ingestion.repository.ManualDocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentIngestionService {

    private final VectorStore vectorStore;
    private final ManualDocumentRepository documentRepository;

    private final TokenTextSplitter parentSplitter = TokenTextSplitter.builder()
            .withChunkSize(1000)
            .withMinChunkSizeChars(400)
            .withMinChunkLengthToEmbed(50)
            .withMaxNumChunks(5000)
            .withKeepSeparator(true)
            .build();

    private final TokenTextSplitter childSplitter = TokenTextSplitter.builder()
            .withChunkSize(200)
            .withMinChunkSizeChars(80)
            .withMinChunkLengthToEmbed(20)
            .withMaxNumChunks(10000)
            .withKeepSeparator(true)
            .build();

    @Async
    public void processDocumentAsync(UUID documentId, byte[] fileBytes, String fileName,
                                     String cameraModel, String brand, String tenantId) {
        log.info("Starting async ingestion for manual: {} (Model: {}, Tenant: {})", fileName, cameraModel, tenantId);

        ManualDocument manualDoc = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document record not found: " + documentId));

        try {
            ByteArrayResource resource = new ByteArrayResource(fileBytes) {
                @Override
                public String getFilename() {
                    return fileName;
                }
            };

            TikaDocumentReader reader = new TikaDocumentReader(resource);
            List<Document> rawDocuments = reader.get();

            List<Document> allChildChunks = new ArrayList<>();
            int estimatedPage = 1;

            for (Document rawDoc : rawDocuments) {
                List<Document> parentDocs = parentSplitter.apply(List.of(rawDoc));

                for (int pIdx = 0; pIdx < parentDocs.size(); pIdx++) {
                    Document parentDoc = parentDocs.get(pIdx);
                    String parentText = parentDoc.getText();
                    if (parentText == null || parentText.isBlank()) continue;

                    String parentId = UUID.randomUUID().toString();
                    int currentPage = estimatedPage + (pIdx / 2);

                    List<Document> childDocs = childSplitter.apply(List.of(parentDoc));

                    for (Document childDoc : childDocs) {
                        Map<String, Object> metadata = new HashMap<>();
                        metadata.put("document_id", documentId.toString());
                        metadata.put("file_name", fileName);
                        metadata.put("camera_model", cameraModel.trim());
                        metadata.put("brand", brand.trim());
                        metadata.put("tenant_id", tenantId.trim());
                        metadata.put("parent_id", parentId);
                        metadata.put("parent_content", parentText);
                        metadata.put("page_number", currentPage);

                        Document enrichedChild = new Document(
                                UUID.randomUUID().toString(),
                                childDoc.getText(),
                                metadata
                        );
                        allChildChunks.add(enrichedChild);
                    }
                }
                estimatedPage++;
            }

            if (allChildChunks.isEmpty()) {
                throw new IllegalStateException("No extractable text found in document: " + fileName);
            }

            int batchSize = 20;
            for (int i = 0; i < allChildChunks.size(); i += batchSize) {
                int end = Math.min(i + batchSize, allChildChunks.size());
                vectorStore.add(allChildChunks.subList(i, end));
                log.info("Indexed batch {}/{} for {}", end, allChildChunks.size(), fileName);
            }

            manualDoc.setStatus(ManualDocument.IngestionStatus.COMPLETED);
            manualDoc.setTotalChunks(allChildChunks.size());
            documentRepository.save(manualDoc);
            log.info("Successfully completed ingestion for {} ({} child chunks)", fileName, allChildChunks.size());

        } catch (Exception e) {
            log.error("Failed to ingest document {}: {}", fileName, e.getMessage(), e);
            manualDoc.setStatus(ManualDocument.IngestionStatus.FAILED);
            manualDoc.setErrorMessage(e.getMessage());
            documentRepository.save(manualDoc);
        }
    }
}