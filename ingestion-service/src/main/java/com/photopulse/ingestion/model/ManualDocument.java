package com.photopulse.ingestion.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "manual_documents", indexes = {
        @Index(name = "idx_manual_checksum_tenant", columnList = "checksum, tenantId, cameraModel")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ManualDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String docId; // Identificator stabil determinist (ex: "fujifilm-ga645-zi-manual-v1")

    @Column(nullable = false)
    private String checksum; // SHA-256 al continutului fisierului

    @Column(nullable = false)
    @Builder.Default
    private String version = "1.0";

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String cameraModel;

    @Column(nullable = false)
    private String brand;

    @Column(nullable = false)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IngestionStatus status;

    private int totalChunks;
    private String errorMessage;
    private LocalDateTime uploadedAt;

    public enum IngestionStatus {
        PROCESSING, COMPLETED, SKIPPED_DUPLICATE, FAILED
    }
}