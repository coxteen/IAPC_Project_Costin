package com.photopulse.ingestion.repository;

import com.photopulse.ingestion.model.ManualDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ManualDocumentRepository extends JpaRepository<ManualDocument, UUID> {
    List<ManualDocument> findByTenantId(String tenantId);
}