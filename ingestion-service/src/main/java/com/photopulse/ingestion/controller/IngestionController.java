package com.photopulse.ingestion.controller;

import com.photopulse.ingestion.model.ManualDocument;
import com.photopulse.ingestion.repository.ManualDocumentRepository;
import com.photopulse.ingestion.service.DocumentIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.photopulse.ingestion.dto.DynamicIngestionDtos.*;
import com.photopulse.ingestion.service.DynamicInternetIngestionService;
import jakarta.validation.Valid;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ingestion")
@RequiredArgsConstructor
@Slf4j
public class IngestionController {

    private final DocumentIngestionService ingestionService;
    private final ManualDocumentRepository documentRepository;
    private final DynamicInternetIngestionService dynamicIngestionService;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "txt");

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadManual(
            @RequestParam("file") MultipartFile file,
            @RequestParam("cameraModel") String cameraModel,
            @RequestParam("brand") String brand,
            @RequestParam(value = "tenantId", defaultValue = "default-tenant") String tenantId,
            @RequestParam(value = "version", defaultValue = "1.0") String version
    ) throws IOException {

        if (file.isEmpty() || file.getOriginalFilename() == null) {
            return ResponseEntity.badRequest().body("File must not be empty.");
        }

        String originalName = file.getOriginalFilename();
        String extension = originalName.contains(".")
                ? originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase()
                : "";

        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            return ResponseEntity.badRequest().body("Unsupported file format. Allowed: PDF, DOCX, TXT.");
        }

        byte[] fileBytes = file.getBytes();
        String checksum = ingestionService.computeSha256(fileBytes);
        String docId = ingestionService.generateStableDocId(originalName, cameraModel, tenantId);

        // Idempotenta: daca acelasi fisier (acelasi SHA-256) a fost deja ingerat cu succes, nu cream duplicate
        Optional<ManualDocument> existing = documentRepository
                .findFirstByChecksumAndTenantIdAndCameraModelAndStatus(
                        checksum, tenantId, cameraModel, ManualDocument.IngestionStatus.COMPLETED);

        if (existing.isPresent()) {
            log.info("Duplicate file detected (docId={}, checksum={}). Skipping re-ingestion.", docId, checksum);
            return ResponseEntity.ok(existing.get());
        }

        ManualDocument savedDoc = documentRepository.save(ManualDocument.builder()
                .docId(docId)
                .checksum(checksum)
                .version(version)
                .fileName(originalName)
                .cameraModel(cameraModel)
                .brand(brand)
                .tenantId(tenantId)
                .status(ManualDocument.IngestionStatus.PROCESSING)
                .uploadedAt(LocalDateTime.now())
                .build());

        ingestionService.processDocumentAsync(
                savedDoc.getId(), fileBytes, originalName, docId, version, checksum, cameraModel, brand, tenantId
        );

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(savedDoc);
    }

    @GetMapping("/documents")
    public ResponseEntity<List<ManualDocument>> getDocuments(
            @RequestParam(value = "tenantId", defaultValue = "default-tenant") String tenantId) {
        return ResponseEntity.ok(documentRepository.findByTenantId(tenantId));
    }

    @GetMapping("/documents/{id}")
    public ResponseEntity<ManualDocument> getDocumentStatus(@PathVariable UUID id) {
        return documentRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/internet")
    public ResponseEntity<DynamicBatchResponse> ingestFromInternet(
            @Valid @RequestBody DynamicBatchRequest request) {
        log.info("Received dynamic internet ingestion batch with {} sources for tenant={}",
                request.sources().size(), request.resolvedTenantId());
        DynamicBatchResponse response = dynamicIngestionService.ingestBatch(request);
        return ResponseEntity.ok(response);
    }
}