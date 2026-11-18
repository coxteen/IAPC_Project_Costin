package com.photopulse.assistant.controller;

import com.photopulse.assistant.dto.AssistantDtos.*;
import com.photopulse.assistant.model.AuditLog;
import com.photopulse.assistant.repository.AuditLogRepository;
import com.photopulse.assistant.service.CameraAssistantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/assistant")
@RequiredArgsConstructor
public class AssistantController {

    private final CameraAssistantService assistantService;
    private final AuditLogRepository auditLogRepository;

    @PostMapping("/ask")
    public ResponseEntity<AskResponse> ask(@Valid @RequestBody AskRequest request) {
        return ResponseEntity.ok(assistantService.askQuestion(request));
    }

    @PostMapping("/gap-analysis")
    public ResponseEntity<AskResponse> gapAnalysis(@Valid @RequestBody GapAnalysisRequest request) {
        return ResponseEntity.ok(assistantService.performGapAnalysis(request));
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<List<AuditLog>> getAuditLogs(
            @RequestParam(value = "tenantId", defaultValue = "default-tenant") String tenantId) {
        return ResponseEntity.ok(auditLogRepository.findByTenantIdOrderByCreatedAtDesc(tenantId));
    }
}