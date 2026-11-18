package com.photopulse.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;

public class AssistantDtos {

    public record AskRequest(
            @NotBlank(message = "Question must not be empty")
            String question,
            String cameraModel,
            String tenantId
    ) {
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

    public record AskResponse(
            UUID auditId,
            String answer,
            List<String> expandedQueries,
            List<CitationDto> citations
    ) {}

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