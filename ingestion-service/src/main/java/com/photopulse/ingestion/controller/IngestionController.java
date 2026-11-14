package com.photopulse.ingestion.controller;

import com.photopulse.ingestion.model.ManualDocument;
import com.photopulse.ingestion.repository.ManualDocumentRepository;
import com.photopulse.ingestion.service.DocumentIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ingestion")
@RequiredArgsConstructor
public class IngestionController {

    private final DocumentIngestionService ingestionService;
    private final ManualDocumentRepository documentRepository;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "txt");

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadManual(
            @RequestParam("file") MultipartFile file,
            @RequestParam("cameraModel") String cameraModel,
            @RequestParam("brand") String brand,
            @RequestParam(value = "tenantId", defaultValue = "default-tenant") String tenantId
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

        ManualDocument savedDoc = documentRepository.save(ManualDocument.builder()
                .fileName(originalName)
                .cameraModel(cameraModel)
                .brand(brand)
                .tenantId(tenantId)
                .status(ManualDocument.IngestionStatus.PROCESSING)
                .uploadedAt(LocalDateTime.now())
                .build());

        byte[] fileBytes = file.getBytes();
        ingestionService.processDocumentAsync(savedDoc.getId(), fileBytes, originalName, cameraModel, brand, tenantId);

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
}