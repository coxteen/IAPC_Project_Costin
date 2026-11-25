package com.photopulse.assistant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.photopulse.assistant.dto.AssistantDtos.*;
import com.photopulse.assistant.filter.SafeMetadataFilterParser;
import com.photopulse.assistant.filter.ValidatedMetadataFilter;
import com.photopulse.assistant.model.AuditLog;
import com.photopulse.assistant.repository.AuditLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CameraAssistantService {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final AuditLogRepository auditLogRepository;
    private final MultiQueryExpander queryExpander;
    private final ObjectMapper objectMapper;
    private final SafeMetadataFilterParser filterParser;

    @Autowired
    public CameraAssistantService(ChatClient.Builder chatClientBuilder,
                                  VectorStore vectorStore,
                                  AuditLogRepository auditLogRepository,
                                  ObjectMapper objectMapper,
                                  SafeMetadataFilterParser filterParser) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
        this.filterParser = filterParser;
        this.queryExpander = MultiQueryExpander.builder()
                .chatClientBuilder(chatClientBuilder)
                .numberOfQueries(3)
                .includeOriginal(true)
                .build();
    }

    public CameraAssistantService(ChatClient.Builder chatClientBuilder,
                                  VectorStore vectorStore,
                                  AuditLogRepository auditLogRepository,
                                  ObjectMapper objectMapper) {
        this(chatClientBuilder, vectorStore, auditLogRepository, objectMapper, new SafeMetadataFilterParser());
    }

    public AskResponse askQuestion(AskRequest request) {
        String tenantId = request.resolvedTenantId();
        log.info("Processing RAG query for tenant '{}', camera '{}', filter '{}': {}",
                tenantId, request.cameraModel(), request.filter(), request.question());

        // 0. Validare stricta a filtrului din URL/request INAINTE de orice apel spre VectorStore
        ValidatedMetadataFilter customFilter = filterParser.parse(request.filter());
        Filter.Expression filterExpression = buildCombinedFilter(tenantId, request.cameraModel(), customFilter);

        // 1. Pre-Retrieval: Multi-Query Expansion (3 variations + original)
        List<Query> expandedQueries = queryExpander.expand(new Query(request.question()));
        List<String> queryTexts = expandedQueries.stream().map(Query::text).toList();

        // 2. Retrieval: Hybrid Metadata Guardrails (tenant isolation + optional camera model + validated custom filter)
        Map<String, Document> uniqueParentChunks = new LinkedHashMap<>();
        List<CitationDto> citations = new ArrayList<>();

        for (Query q : expandedQueries) {
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(q.text())
                    .topK(4)
                    .similarityThreshold(0.35)
                    .filterExpression(filterExpression)
                    .build();

            List<Document> matches = vectorStore.similaritySearch(searchRequest);

            for (Document childDoc : matches) {
                Map<String, Object> meta = childDoc.getMetadata();
                String parentId = String.valueOf(meta.getOrDefault("parent_id", childDoc.getId()));

                if (!uniqueParentChunks.containsKey(parentId)) {
                    uniqueParentChunks.put(parentId, childDoc);

                    String fileName = String.valueOf(meta.getOrDefault("file_name", "Unknown Manual"));
                    String camModel = String.valueOf(meta.getOrDefault("camera_model", "Unknown Camera"));
                    Integer pageNum = meta.get("page_number") instanceof Number n ? n.intValue() : 1;
                    String snippet = childDoc.getText() != null
                            ? childDoc.getText().substring(0, Math.min(200, childDoc.getText().length())).replace("\n", " ") + "..."
                            : "";

                    citations.add(new CitationDto(fileName, camModel, pageNum, snippet));
                }
            }
        }

        // Guardrail determinist: daca Qdrant nu returneaza niciun chunk relevant, oprim executia inainte de LLM
        if (uniqueParentChunks.isEmpty()) {
            String noDocsMsg = "I could not find any matching information in the uploaded camera manuals for tenant '"
                    + tenantId + "'" + (request.cameraModel() != null ? " and model '" + request.cameraModel() + "'." : ".");
            AuditLog savedLog = saveAuditLog(tenantId, request.cameraModel(), request.question(), noDocsMsg, List.of());
            return new AskResponse(savedLog.getId(), noDocsMsg, queryTexts, List.of());
        }

        // 3. Context Assembly (folosim parent_content pentru context complet)
        String assembledContext = uniqueParentChunks.values().stream()
                .map(doc -> {
                    Map<String, Object> meta = doc.getMetadata();
                    String parentText = String.valueOf(meta.getOrDefault("parent_content", doc.getText()));
                    return String.format("[Manual: %s | Camera: %s | Page: %s]\n%s",
                            meta.getOrDefault("file_name", "Manual"),
                            meta.getOrDefault("camera_model", "Camera"),
                            meta.getOrDefault("page_number", "?"),
                            parentText);
                })
                .limit(5)
                .collect(Collectors.joining("\n\n---\n\n"));

        // 4. Generation + Topic Boundary & Anti-Hallucination Guardrails
        String systemPrompt = """
                You are PhotoPulse AI, a strict technical assistant specialized EXCLUSIVELY in cameras, lenses, photography equipment, and camera instruction manuals.
                
                STRICT RULES:
                1. TOPIC BOUNDARY: If the user's question is NOT related to photography, cameras, lenses, or camera settings, refuse politely and state that you only assist with photography equipment manuals.
                2. GROUNDING: Answer ONLY using the provided CONTEXT from the camera manuals. Do NOT guess or invent camera specs or menu paths.
                3. CITATIONS: Always cite the source manual name and page number for every technical instruction you provide.
                4. MISSING INFO: If the provided context does not contain the answer, explicitly say: "I could not find this information in the uploaded camera manuals for your organization."
                5. LANGUAGE: Respond in the same language the user asked the question in (e.g., Romanian or English).
                """;

        String userPrompt = String.format("""
                CONTEXT FROM CAMERA MANUALS:
                %s

                USER QUESTION:
                %s
                """, assembledContext, request.question());

        String aiAnswer = chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content();

        // 5. Audit Trail Persistence
        AuditLog savedLog = saveAuditLog(tenantId, request.cameraModel(), request.question(), aiAnswer, citations);

        return new AskResponse(savedLog.getId(), aiAnswer, queryTexts, citations);
    }

    public AskResponse performGapAnalysis(GapAnalysisRequest request) {
        String tenantId = request.resolvedTenantId();
        log.info("Running Gap Analysis for camera '{}' in tenant '{}'", request.cameraModel(), tenantId);

        Filter.Expression filterExpression = buildCombinedFilter(tenantId, request.cameraModel(), null);

        String[] requirements = request.targetChecklist().split("\\r?\\n");
        Set<String> seenParents = new HashSet<>();
        StringBuilder gatheredSpecs = new StringBuilder();
        List<CitationDto> citations = new ArrayList<>();

        for (String reqLine : requirements) {
            if (reqLine.isBlank()) continue;

            List<Document> matches = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(reqLine.trim())
                            .topK(2)
                            .similarityThreshold(0.50)
                            .filterExpression(filterExpression)
                            .build()
            );

            for (Document doc : matches) {
                Map<String, Object> meta = doc.getMetadata();
                String parentId = String.valueOf(meta.getOrDefault("parent_id", doc.getId()));
                if (seenParents.add(parentId)) {
                    String fileName = String.valueOf(meta.getOrDefault("file_name", "Manual"));
                    Integer pageNum = meta.get("page_number") instanceof Number n ? n.intValue() : 1;
                    String parentText = String.valueOf(meta.getOrDefault("parent_content", doc.getText()));

                    gatheredSpecs.append(String.format("[Manual: %s, Page %d]\n%s\n\n", fileName, pageNum, parentText));
                    citations.add(new CitationDto(fileName, request.cameraModel(), pageNum,
                            doc.getText().substring(0, Math.min(150, doc.getText().length())) + "..."));
                }
            }
        }

        String gapSystemPrompt = """
                You are a senior camera hardware auditor. Compare the TARGET REQUIREMENTS / CHECKLIST against the EXISTING CAMERA MANUAL SPECIFICATIONS provided in the context.
                Produce a structured Gap Analysis Report with three clear sections:
                1. SUPPORTED / COMPLIANT FEATURES (with exact page citations)
                2. MISSING / GAPS IDENTIFIED (requirements not supported or not documented in the manual)
                3. TECHNICAL RECOMMENDATIONS / WORKAROUNDS
                Respond in the same language as the target checklist.
                """;

        String gapUserPrompt = String.format("""
                CAMERA MODEL EVALUATED: %s

                TARGET REQUIREMENTS / CHECKLIST:
                %s

                RETRIEVED MANUAL SPECIFICATIONS:
                %s
                """, request.cameraModel(), request.targetChecklist(),
                gatheredSpecs.isEmpty() ? "No specifications found for this camera model." : gatheredSpecs.toString());

        String report = chatClient.prompt()
                .system(gapSystemPrompt)
                .user(gapUserPrompt)
                .call()
                .content();

        AuditLog savedLog = saveAuditLog(tenantId, request.cameraModel(),
                "[GAP ANALYSIS] " + request.targetChecklist(), report, citations);

        return new AskResponse(savedLog.getId(), report, List.of(request.targetChecklist()), citations);
    }

    public Filter.Expression buildCombinedFilter(String tenantId, String cameraModel, ValidatedMetadataFilter customFilter) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();
        FilterExpressionBuilder.Op baseOp = (cameraModel != null && !cameraModel.isBlank())
                ? b.and(b.eq("tenant_id", tenantId), b.eq("camera_model", cameraModel.trim()))
                : b.eq("tenant_id", tenantId);

        if (customFilter == null) {
            return baseOp.build();
        }
        return b.and(baseOp, customFilter.toOp(b)).build();
    }

    private AuditLog saveAuditLog(String tenantId, String cameraModel, String query,
                                  String answer, List<CitationDto> citations) {
        String citationsJson = "[]";
        try {
            citationsJson = objectMapper.writeValueAsString(citations);
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize citations for audit log", e);
        }

        return auditLogRepository.save(AuditLog.builder()
                .tenantId(tenantId)
                .cameraModel(cameraModel)
                .userQuery(query)
                .aiResponse(answer)
                .retrievedSourcesJson(citationsJson)
                .createdAt(LocalDateTime.now())
                .build());
    }
}