package com.photopulse.ingestion;

import com.photopulse.ingestion.config.DynamicIngestionProperties;
import com.photopulse.ingestion.config.IngestionProperties;
import com.photopulse.ingestion.controller.IngestionController;
import com.photopulse.ingestion.dto.DynamicIngestionDtos.*;
import com.photopulse.ingestion.model.ManualDocument;
import com.photopulse.ingestion.repository.ManualDocumentRepository;
import com.photopulse.ingestion.service.DocumentIngestionService;
import com.photopulse.ingestion.service.DynamicInternetIngestionService;
import com.photopulse.ingestion.service.HtmlBoilerplateCleaner;
import com.photopulse.ingestion.service.StructureAwareChunker;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternetIngestionTask1Test {

    private VectorStore vectorStore;
    private ManualDocumentRepository documentRepository;
    private HttpClient httpClient;
    private DynamicInternetIngestionService dynamicIngestionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        documentRepository = mock(ManualDocumentRepository.class);
        httpClient = mock(HttpClient.class);

        IngestionProperties ingestionProps = new IngestionProperties();
        ingestionProps.setChunkSize(250);
        ingestionProps.setChunkOverlap(50);
        ingestionProps.setParentChunkSize(1000);
        ingestionProps.setMinChunkSizeChars(40);

        DynamicIngestionProperties dynamicProps = new DynamicIngestionProperties();
        dynamicProps.setMaxRepoFiles(5);

        StructureAwareChunker chunker = new StructureAwareChunker();
        HtmlBoilerplateCleaner cleaner = new HtmlBoilerplateCleaner();

        DocumentIngestionService staticService = new DocumentIngestionService(
                vectorStore, documentRepository, chunker, ingestionProps
        );

        dynamicIngestionService = new DynamicInternetIngestionService(
                vectorStore,
                documentRepository,
                staticService,
                chunker,
                cleaner,
                ingestionProps,
                dynamicProps,
                httpClient
        );

        when(documentRepository.save(any(ManualDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        IngestionController controller = new IngestionController(
                staticService, documentRepository, dynamicIngestionService
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingestWebPage_removesBoilerplateAndStoresSourceUrlAndFetchDate() throws Exception {
        String webUrl = "https://example-photo-blog.com/guides/sony-a7iv-autofocus";
        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                    <title>Sony A7 IV Autofocus Setup Guide</title>
                    <script>console.log('tracking-script-noise');</script>
                    <style>.cookie-banner { display: block; }</style>
                </head>
                <body>
                    <header><nav>Home | Reviews | Gear | Subscribe</nav></header>
                    <div id="cookie-consent-banner" class="gdpr-popup">
                        We use cookies to personalize ads. Accept all cookies.
                    </div>
                    <aside class="sidebar">Related Ads and Sponsored Links</aside>
                    <main>
                        <article>
                            <h1>Real-Time Eye AF Configuration</h1>
                            <p>To configure Real-Time Eye AF on the Sony A7 IV for wildlife and birds, set Focus Mode to AF-C and Focus Area to Tracking: Expand Spot.</p>
                            <p>Assign Eye AF activation to the AF-ON button under Custom Key Settings so tracking remains locked while recomposing.</p>
                        </article>
                    </main>
                    <footer>Copyright 2026 Example Photo Blog. Privacy Policy.</footer>
                </body>
                </html>
                """;

        HttpResponse<byte[]> response = mockHttpResponse(200, html.getBytes(StandardCharsets.UTF_8));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);

        DynamicBatchRequest request = new DynamicBatchRequest(
                List.of(new InternetSourceItem(SourceType.WEB_PAGE, webUrl, "Sony A7 IV", "Sony")),
                "studio-brasov",
                "1.0"
        );

        DynamicBatchResponse batchResponse = dynamicIngestionService.ingestBatch(request);

        assertEquals(1, batchResponse.totalSources());
        assertEquals(1, batchResponse.succeeded());
        assertEquals(0, batchResponse.failed());

        SourceIngestionResult itemResult = batchResponse.results().getFirst();
        assertEquals("SUCCESS", itemResult.status());
        assertTrue(itemResult.chunksIndexed() >= 1);
        assertDoesNotThrow(() -> Instant.parse(itemResult.fetchDate()));

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, atLeastOnce()).add(captor.capture());

        List<Document> savedChunks = captor.getValue();
        String allIndexedText = savedChunks.stream().map(Document::getText).reduce("", (a, b) -> a + "\n" + b);

        assertTrue(allIndexedText.contains("Real-Time Eye AF Configuration"));
        assertTrue(allIndexedText.contains("Focus Mode to AF-C"));

        assertFalse(allIndexedText.contains("Accept all cookies"));
        assertFalse(allIndexedText.contains("tracking-script-noise"));
        assertFalse(allIndexedText.contains("Copyright 2026 Example Photo Blog"));
        assertFalse(allIndexedText.contains("Sponsored Links"));

        for (Document chunk : savedChunks) {
            assertEquals(webUrl, chunk.getMetadata().get("source_url"));
            assertNotNull(chunk.getMetadata().get("fetch_date"));
            assertDoesNotThrow(() -> Instant.parse(chunk.getMetadata().get("fetch_date").toString()));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingestRemotePdf_parsesPdfAndAddsInternetMetadata() throws Exception {
        String pdfUrl = "https://cdn.camera-manuals.org/canon-r6-quick-guide.pdf";
        byte[] pdfBytes = createSamplePdf(
                "Canon EOS R6 Mark II Quick Reference Guide. " +
                "Use Electronic Shutter mode for continuous shooting up to 40 frames per second with full AF/AE tracking."
        );

        HttpResponse<byte[]> pdfResponse = mockHttpResponse(200, pdfBytes);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(pdfResponse);

        DynamicBatchRequest request = new DynamicBatchRequest(
                List.of(new InternetSourceItem(SourceType.REMOTE_PDF, pdfUrl, "Canon EOS R6 II", "Canon")),
                "studio-brasov",
                "1.0"
        );

        DynamicBatchResponse batchResponse = dynamicIngestionService.ingestBatch(request);

        assertEquals(1, batchResponse.succeeded());
        assertEquals(0, batchResponse.failed());

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, atLeastOnce()).add(captor.capture());

        Document firstChunk = captor.getValue().getFirst();
        assertEquals(pdfUrl, firstChunk.getMetadata().get("source_url"));
        assertEquals("remote_pdf", firstChunk.getMetadata().get("source_type"));
        assertNotNull(firstChunk.getMetadata().get("fetch_date"));
        assertTrue(firstChunk.getText().contains("40 frames per second"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingestGithubRepo_fetchesMarkdownDocsAndIndexesChunks() throws Exception {
        String repoUrl = "https://github.com/open-photo/camera-profiles";
        String repoInfoJson = """
                { "default_branch": "main" }
                """;
        String treeJson = """
                {
                  "tree": [
                    { "path": "README.md", "type": "blob" },
                    { "path": "docs/fujifilm-recipes.md", "type": "blob" },
                    { "path": "src/main.py", "type": "blob" }
                  ]
                }
                """;
        String readmeMd = "# Open Camera Profiles\nThis repository documents film simulation recipes and dynamic range settings for mirrorless cameras.";
        String recipesMd = "## Classic Chrome Recipe\nSet Dynamic Range to DR400, Highlight -1, Shadow +2, and Color Chrome Effect Strong for documentary street photography.";

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest req = invocation.getArgument(0);
                    String uri = req.uri().toString();
                    if (uri.equals("https://api.github.com/repos/open-photo/camera-profiles")) {
                        return mockHttpResponse(200, repoInfoJson.getBytes(StandardCharsets.UTF_8));
                    }
                    if (uri.contains("/git/trees/main")) {
                        return mockHttpResponse(200, treeJson.getBytes(StandardCharsets.UTF_8));
                    }
                    if (uri.endsWith("/README.md")) {
                        return mockHttpResponse(200, readmeMd.getBytes(StandardCharsets.UTF_8));
                    }
                    if (uri.endsWith("/docs/fujifilm-recipes.md")) {
                        return mockHttpResponse(200, recipesMd.getBytes(StandardCharsets.UTF_8));
                    }
                    return mockHttpResponse(404, new byte[0]);
                });

        DynamicBatchRequest request = new DynamicBatchRequest(
                List.of(new InternetSourceItem(SourceType.GITHUB_REPO, repoUrl, "Fujifilm X-T5", "Fujifilm")),
                "studio-brasov",
                "1.0"
        );

        DynamicBatchResponse batchResponse = dynamicIngestionService.ingestBatch(request);

        assertEquals(1, batchResponse.succeeded());
        assertEquals(0, batchResponse.failed());
        assertTrue(batchResponse.results().getFirst().chunksIndexed() >= 2);

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, atLeastOnce()).add(captor.capture());

        List<Document> chunks = captor.getValue();
        assertTrue(chunks.stream().anyMatch(c -> c.getMetadata().get("source_url").toString().endsWith("README.md")));
        assertTrue(chunks.stream().anyMatch(c -> c.getMetadata().get("source_url").toString().endsWith("docs/fujifilm-recipes.md")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingestBatch_oneFailedSourceIsLoggedAndDoesNotStopRemainingBatch() throws Exception {
        String validUrl1 = "https://example.com/page1";
        String brokenUrl = "https://example.com/missing-page-404";
        String validUrl2 = "https://example.com/page2";

        String validHtml = "<html><body><main><p>Shutter speed controls motion blur and exposure duration in manual photography mode.</p></main></body></html>";

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest req = invocation.getArgument(0);
                    if (req.uri().toString().equals(brokenUrl)) {
                        return mockHttpResponse(404, "Not Found".getBytes(StandardCharsets.UTF_8));
                    }
                    return mockHttpResponse(200, validHtml.getBytes(StandardCharsets.UTF_8));
                });

        DynamicBatchRequest request = new DynamicBatchRequest(
                List.of(
                        new InternetSourceItem(SourceType.WEB_PAGE, validUrl1, "Nikon Z8", "Nikon"),
                        new InternetSourceItem(SourceType.WEB_PAGE, brokenUrl, "Nikon Z8", "Nikon"),
                        new InternetSourceItem(SourceType.WEB_PAGE, validUrl2, "Nikon Z8", "Nikon")
                ),
                "studio-brasov",
                "1.0"
        );

        DynamicBatchResponse response = dynamicIngestionService.ingestBatch(request);

        assertEquals(3, response.totalSources());
        assertEquals(2, response.succeeded());
        assertEquals(1, response.failed());

        assertEquals("SUCCESS", response.results().get(0).status());
        assertEquals("FAILED", response.results().get(1).status());
        assertTrue(response.results().get(1).errorMessage().contains("HTTP 404"));
        assertEquals("SUCCESS", response.results().get(2).status());
    }

    @Test
    void controllerEndpoint_rejectsEmptySourcesWithBadRequest() throws Exception {
        String invalidPayload = """
                {
                  "sources": [],
                  "tenantId": "studio-brasov"
                }
                """;

        mockMvc.perform(post("/api/v1/ingestion/internet")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest());
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<byte[]> mockHttpResponse(int statusCode, byte[] body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.body()).thenReturn(body);
        return response;
    }

    private byte[] createSamplePdf(String text) throws IOException {
        try (PDDocument doc = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 11);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}