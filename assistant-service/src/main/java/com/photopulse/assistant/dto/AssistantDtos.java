package com.photopulse.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;

public class AssistantDtos {

    public record AskRequest(
            @NotBlank(message = "Question must not be empty")
            String question,
            String cameraModel,
            String tenantId,
            String filter
    ) {
        public AskRequest(String question, String cameraModel, String tenantId) {
            this(question, cameraModel, tenantId, null);
        }

        public String resolvedTenantId() {
            return (tenantId == null || tenantId.isBlank()) ? "default-tenant" : tenantId.trim();
        }
    }

    public record CitationDto(
            String fileName,
            String cameraModel,
            Integer pageNumber,
            String snippet
    ) {}

    public record RerankMetadataDto(
            boolean reranked,
            String modelVersion,
            long latencyMs,
            int candidatesEvaluated,
            int returnedCount
    ) {}

    public record AskResponse(
            UUID auditId,
            String answer,
            List<String> expandedQueries,
            List<CitationDto> citations,
            RerankMetadataDto rerankInfo
    ) {
        public AskResponse(UUID auditId,
                           String answer,
                           List<String> expandedQueries,
                           List<CitationDto> citations) {
            this(auditId, answer, expandedQueries, citations, null);
        }
    }

    public record GapAnalysisRequest(
            @NotBlank(message = "Target requirements or comparison checklist must not be empty")
            String targetChecklist,
            @NotBlank(message = "Camera model to evaluate must be specified")
            String cameraModel,
            String tenantId
    ) {
        public String resolvedTenantId() {
            return (tenantId == null || tenantId.isBlank()) ? "default-tenant" : tenantId.trim();
        }
    }
}