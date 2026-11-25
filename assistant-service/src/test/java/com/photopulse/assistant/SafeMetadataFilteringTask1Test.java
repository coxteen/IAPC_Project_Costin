package com.photopulse.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.photopulse.assistant.controller.AssistantController;
import com.photopulse.assistant.dto.AssistantDtos.AskRequest;
import com.photopulse.assistant.filter.InvalidMetadataFilterException;
import com.photopulse.assistant.filter.MetadataFilterExceptionHandler;
import com.photopulse.assistant.filter.SafeMetadataFilterParser;
import com.photopulse.assistant.filter.ValidatedMetadataFilter;
import com.photopulse.assistant.model.AuditLog;
import com.photopulse.assistant.repository.AuditLogRepository;
import com.photopulse.assistant.service.CameraAssistantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SafeMetadataFilteringTask1Test {

    private SafeMetadataFilterParser parser;
    private VectorStore vectorStore;
    private AuditLogRepository auditLogRepository;
    private CameraAssistantService assistantService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        parser = new SafeMetadataFilterParser();
        vectorStore = mock(VectorStore.class);
        auditLogRepository = mock(AuditLogRepository.class);

        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> {
            AuditLog logEntry = inv.getArgument(0);
            logEntry.setId(UUID.randomUUID());
            return logEntry;
        });

        // Mock fluent ChatClient pentru MultiQueryExpander si raspunsul final
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().user(anyString()).call().content())
                .thenReturn("How to configure Eye AF on Sony A7 IV?");
        when(chatClient.prompt().system(anyString()).user(anyString()).call().content())
                .thenReturn("Enable Eye AF in Menu > AF1 > Face/Eye Priority [Manual: sony-a7iv.pdf, Page 42].");

        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(chatClient);

        assistantService = new CameraAssistantService(
                builder, vectorStore, auditLogRepository, new ObjectMapper(), parser
        );

        AssistantController controller = new AssistantController(assistantService, auditLogRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new MetadataFilterExceptionHandler())
                .build();
    }

    @Test
    void parse_successPath_supportsCompoundAndOrExpressionsAndCompilesToFilterExpression() {
        String rawFilter = "brand:eq:Sony AND (camera_model:eq:'Sony A7 IV' OR version:in:(1.0,2.0)) AND source_type:ne:web_page";

        ValidatedMetadataFilter parsed = parser.parse(rawFilter);
        assertNotNull(parsed);
        assertInstanceOf(ValidatedMetadataFilter.Compound.class, parsed);

        Filter.Expression expression = parsed.toSpringAiExpression();
        assertNotNull(expression);
        assertEquals(Filter.ExpressionType.AND, expression.type());
    }

    @Test
    void parse_nullOrBlankFilter_fallsThroughToStandardFlow() {
        assertNull(parser.parse(null));
        assertNull(parser.parse("   "));

        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        var response = assistantService.askQuestion(new AskRequest("What is ISO?", "Sony A7 IV", "studio-brasov", null));
        assertNotNull(response);
        assertNotNull(response.auditId());

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, atLeastOnce()).similaritySearch(captor.capture());
        assertNotNull(captor.getValue().getFilterExpression());
    }

    @Test
    void parse_failurePath_rejectsNonWhitelistedFieldAndInjectionAttempt() {
        InvalidMetadataFilterException exField = assertThrows(
                InvalidMetadataFilterException.class,
                () -> parser.parse("password_hash:eq:secret")
        );
        assertEquals("password_hash", exField.getInvalidToken());
        assertTrue(exField.getAllowedFields().containsAll(
                List.of("tenant_id", "camera_model", "brand", "doc_id", "version", "source_type")
        ));

        // Tentativa de injectare in valoare trebuie respinsa inainte de a atinge VectorStore
        assertThrows(
                InvalidMetadataFilterException.class,
                () -> parser.parse("brand:eq:'Sony; DROP TABLE users--'")
        );
        verifyNoInteractions(vectorStore);
    }

    @Test
    void endpointGetAsk_withValidUrlFilter_passesValidatedFilterToVectorStore() throws Exception {
        Document matchedDoc = new Document(
                "Press MENU -> AF1 -> Face/Eye Priority in AF -> ON.",
                Map.of(
                        "parent_id", "p-1",
                        "parent_content", "Press MENU -> AF1 -> Face/Eye Priority in AF -> ON.",
                        "file_name", "sony-a7iv.pdf",
                        "camera_model", "Sony A7 IV",
                        "page_number", 42
                )
        );
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(matchedDoc));

        mockMvc.perform(get("/api/v1/assistant/ask")
                        .param("question", "How do I enable Eye AF?")
                        .param("tenantId", "studio-brasov")
                        .param("filter", "brand:eq:Sony AND version:eq:1.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer", containsString("Eye AF")))
                .andExpect(jsonPath("$.citations[0].fileName", is("sony-a7iv.pdf")));

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, atLeastOnce()).similaritySearch(captor.capture());
        assertNotNull(captor.getValue().getFilterExpression());
    }

    @Test
    void endpointGetAsk_withInvalidUrlFilter_returns400WithValidFieldsList() throws Exception {
        mockMvc.perform(get("/api/v1/assistant/ask")
                        .param("question", "How do I enable Eye AF?")
                        .param("tenantId", "studio-brasov")
                        .param("filter", "unknown_field:eq:Sony"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error", is("Invalid Metadata Filter")))
                .andExpect(jsonPath("$.invalidToken", is("unknown_field")))
                .andExpect(jsonPath("$.validFields", hasItems(
                        "tenant_id", "camera_model", "brand", "doc_id", "version", "source_type"
                )))
                .andExpect(jsonPath("$.validOperators", hasItems("eq", "ne", "in", "AND", "OR")));

        // Ne asiguram ca string-ul brut nu ajunge niciodata la VectorStore
        verifyNoInteractions(vectorStore);
    }
}