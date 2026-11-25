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

   @PostMapping("/ask")
    public ResponseEntity<AskResponse> ask(
            @RequestParam(value = "filter", required = false) String urlFilter,
            @Valid @RequestBody AskRequest request) {
        String effectiveFilter = (urlFilter != null && !urlFilter.isBlank()) ? urlFilter : request.filter();
        AskRequest resolvedRequest = new AskRequest(
                request.question(),
                request.cameraModel(),
                request.tenantId(),
                effectiveFilter
        );
        return ResponseEntity.ok(assistantService.askQuestion(resolvedRequest));
    }

    @GetMapping("/ask")
    public ResponseEntity<AskResponse> askByQueryParams(
            @RequestParam("question") String question,
            @RequestParam(value = "cameraModel", required = false) String cameraModel,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "filter", required = false) String filter) {
        AskRequest request = new AskRequest(question, cameraModel, tenantId, filter);
        return ResponseEntity.ok(assistantService.askQuestion(request));
    }
}