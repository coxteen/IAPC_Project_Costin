package com.photopulse.ingestion.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public class DynamicIngestionDtos {

    public enum SourceType {
        WEB_PAGE,
        REMOTE_PDF,
        GITHUB_REPO
    }

    public record InternetSourceItem(
            @NotNull(message = "Source type is required (WEB_PAGE, REMOTE_PDF, GITHUB_REPO)")
            SourceType type,

            @NotBlank(message = "URL must not be blank")
            String url,

            String cameraModel,
            String brand
    ) {}

    public record DynamicBatchRequest(
            @NotEmpty(message = "Sources list must not be empty")
            @Valid
            List<InternetSourceItem> sources,

            String tenantId,
            String version
    ) {
        public String resolvedTenantId() {
            return (tenantId == null || tenantId.isBlank()) ? "default-tenant" : tenantId.trim();
        }

        public String resolvedVersion() {
            return (version == null || version.isBlank()) ? "1.0" : version.trim();
        }
    }

    public record SourceIngestionResult(
            String url,
            SourceType type,
            String status, // SUCCESS or FAILED
            String docId,
            int chunksIndexed,
            String fetchDate,
            String errorMessage
    ) {}

    public record DynamicBatchResponse(
            int totalSources,
            int succeeded,
            int failed,
            List<SourceIngestionResult> results
    ) {}
}