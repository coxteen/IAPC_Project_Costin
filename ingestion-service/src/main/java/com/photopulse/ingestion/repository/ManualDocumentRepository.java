package com.photopulse.ingestion.repository;

import com.photopulse.ingestion.model.ManualDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ManualDocumentRepository extends JpaRepository<ManualDocument, UUID> {
    List<ManualDocument> findByTenantId(String tenantId);

    Optional<ManualDocument> findFirstByChecksumAndTenantIdAndCameraModelAndStatus(
            String checksum,
            String tenantId,
            String cameraModel,
            ManualDocument.IngestionStatus status
    );
}