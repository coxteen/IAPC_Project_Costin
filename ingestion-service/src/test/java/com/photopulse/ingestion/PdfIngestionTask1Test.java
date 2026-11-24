package com.photopulse.ingestion;

import com.photopulse.ingestion.config.IngestionProperties;
import com.photopulse.ingestion.controller.IngestionController;
import com.photopulse.ingestion.model.ManualDocument;
import com.photopulse.ingestion.repository.ManualDocumentRepository;
import com.photopulse.ingestion.service.DocumentIngestionService;
import com.photopulse.ingestion.service.StructureAwareChunker;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import com.photopulse.ingestion.service.DynamicInternetIngestionService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PdfIngestionTask1Test {

    private VectorStore vectorStore;
    private ManualDocumentRepository documentRepository;
    private StructureAwareChunker chunker;
    private IngestionProperties properties;
    private DocumentIngestionService ingestionService;
    private IngestionController ingestionController;

    @BeforeEach
    void setUp() {
        vectorStore = mock(VectorStore.class);
        documentRepository = mock(ManualDocumentRepository.class);
        chunker = new StructureAwareChunker();

        properties = new IngestionProperties();
        properties.setChunkSize(120);
        properties.setChunkOverlap(25);
        properties.setParentChunkSize(450);
        properties.setMinChunkSizeChars(40);
        properties.setEmbeddingModel("nomic-embed-text");
        properties.setBatchSize(10);

        ingestionService = new DocumentIngestionService(vectorStore, documentRepository, chunker, properties);
        DynamicInternetIngestionService dynamicIngestionService = mock(DynamicInternetIngestionService.class);
        ingestionController = new IngestionController(ingestionService, documentRepository, dynamicIngestionService);
    }

    /**
     * Genereaza un PDF real cu 2 pagini:
     * - Pagina 1: Layout pe 2 coloane (specificatii tehnice Fujifilm GA645Zi)
     * - Pagina 2: Tabel cu moduri de expunere, diafragma si viteza obturatorului
     */
    private byte[] createSampleMultiColumnAndTablePdf() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font fontBold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font fontRegular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

            // Pagina 1: Multi-column layout
            PDPage page1 = new PDPage(PDRectangle.A4);
            doc.addPage(page1);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
                cs.beginText();
                cs.setFont(fontBold, 14);
                cs.newLineAtOffset(50, 770);
                cs.showText("FUJIFILM GA645Zi PROFESSIONAL MANUAL");
                cs.endText();

                // Coloana stanga
                String[] leftCol = {
                        "1. LOADING 120/220 ROLL FILM",
                        "Open the back cover by sliding the lock lever.",
                        "Align the film leader start mark with the red index.",
                        "Close the back cover firmly until it clicks.",
                        "The camera automatically advances to frame 1."
                };
                float y = 730;
                for (String line : leftCol) {
                    cs.beginText();
                    cs.setFont(fontRegular, 10);
                    cs.newLineAtOffset(50, y);
                    cs.showText(line);
                    cs.endText();
                    y -= 18;
                }

                // Coloana dreapta (aceleasi coordonate Y, X=310)
                String[] rightCol = {
                        "2. AUTOFOCUS & METERING SYSTEM",
                        "Active infrared hybrid autofocus with 710 steps.",
                        "Center-weighted SPD metering cell (EV 3 to EV 19).",
                        "Half-press the shutter button to lock focus distance.",
                        "Use AF lock when the subject is off-center."
                };
                y = 730;
                for (String line : rightCol) {
                    cs.beginText();
                    cs.setFont(fontRegular, 10);
                    cs.newLineAtOffset(310, y);
                    cs.showText(line);
                    cs.endText();
                    y -= 18;
                }
            }

            // Pagina 2: Tabel structurat
            PDPage page2 = new PDPage(PDRectangle.A4);
            doc.addPage(page2);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page2)) {
                cs.beginText();
                cs.setFont(fontBold, 13);
                cs.newLineAtOffset(50, 770);
                cs.showText("EXPOSURE MODES & SHUTTER SPEED TABLE");
                cs.endText();

                String[][] tableRows = {
                        {"Mode", "Aperture Range", "Shutter Speed", "Recommended Use"},
                        {"Program (P)", "f/4.5 - f/22", "2s - 1/700s", "General street and travel"},
                        {"Aperture (A)", "f/4.5 - f/22", "2s - 1/700s", "Depth of field portrait control"},
                        {"Manual (M)", "f/4.5 - f/22", "B, 4s - 1/700s", "Studio flash and night scenes"},
                        {"ISO Range", "ISO 25 - 1600", "DX auto-sensing", "120 and 220 medium format film"}
                };

                float y = 730;
                for (String[] row : tableRows) {
                    float[] colX = {50, 150, 270, 390};
                    for (int c = 0; c < row.length; c++) {
                        cs.beginText();
                        cs.setFont(y == 730 ? fontBold : fontRegular, 10);
                        cs.newLineAtOffset(colX[c], y);
                        cs.showText(row[c]);
                        cs.endText();
                    }
                    y -= 22;
                }
            }

            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("Success Path: PDF cu tabele si coloane multiple este parsat cu schema completa de metadate si fara text corupt")
    void shouldIngestMultiColumnAndTablePdfWithCompleteMetadataSchema() throws IOException {
        byte[] pdfBytes = createSampleMultiColumnAndTablePdf();
        String checksum = ingestionService.computeSha256(pdfBytes);
        String docId = ingestionService.generateStableDocId("fujifilm-ga645zi.pdf", "Fujifilm GA645Zi", "studio-brasov");

        List<Document> chunks = ingestionService.parseAndChunk(
                pdfBytes,
                "fujifilm-ga645zi.pdf",
                docId,
                "1.0",
                checksum,
                "Fujifilm GA645Zi",
                "Fujifilm",
                "studio-brasov"
        );

        assertFalse(chunks.isEmpty(), "Trebuie sa se genereze cel putin un chunk din PDF");

        // Verificam existenta continutului din ambele pagini (coloane + tabel)
        String allText = String.join("\n", chunks.stream().map(Document::getText).toList());
        assertTrue(allText.contains("LOADING 120/220 ROLL FILM"), "Coloana stanga din pagina 1 trebuie extrasa");
        assertTrue(allText.contains("AUTOFOCUS & METERING SYSTEM"), "Coloana dreapta din pagina 1 trebuie extrasa");
        assertTrue(allText.contains("Program (P)") && allText.contains("1/700s"), "Randurile din tabelul de pe pagina 2 trebuie pastrate");

        // Verificam schema completa de metadate pe fiecare chunk
        Set<Integer> pagesFound = new HashSet<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            Map<String, Object> meta = chunk.getMetadata();

            assertEquals(docId, meta.get("doc_id"));
            assertEquals("1.0", meta.get("version"));
            assertEquals("fujifilm-ga645zi.pdf", meta.get("source"));
            assertNotNull(meta.get("page"));
            assertEquals(i, meta.get("chunk_index"));
            assertEquals(checksum, meta.get("checksum"));
            assertEquals("nomic-embed-text", meta.get("embedding_model"));
            assertEquals("studio-brasov", meta.get("tenant_id"));
            assertNotNull(meta.get("parent_content"));

            pagesFound.add((Integer) meta.get("page"));

            // Verificam absenta caracterelor corupte (replacement char \uFFFD sau control chars)
            assertFalse(chunk.getText().contains("\uFFFD"), "Textul nu trebuie sa contina caractere corupte");
        }

        assertTrue(pagesFound.contains(1) && pagesFound.contains(2), "Chunk-urile trebuie sa mapeze paginile reale 1 si 2");
    }

    @Test
    @DisplayName("Idempotency: Re-rularea aceluiasi PDF produce aceleasi ID-uri deterministe si nu creeaza duplicate")
    void shouldProduceDeterministicIdsAndSkipDuplicateUploads() throws IOException {
        byte[] pdfBytes = createSampleMultiColumnAndTablePdf();
        String checksum = ingestionService.computeSha256(pdfBytes);
        String docId = ingestionService.generateStableDocId("fujifilm-ga645zi.pdf", "Fujifilm GA645Zi", "studio-brasov");

        // 1. Verificam determinist Point ID la nivel de parseAndChunk
        List<Document> firstRun = ingestionService.parseAndChunk(
                pdfBytes, "fujifilm-ga645zi.pdf", docId, "1.0", checksum, "Fujifilm GA645Zi", "Fujifilm", "studio-brasov"
        );
        List<Document> secondRun = ingestionService.parseAndChunk(
                pdfBytes, "fujifilm-ga645zi.pdf", docId, "1.0", checksum, "Fujifilm GA645Zi", "Fujifilm", "studio-brasov"
        );

        assertEquals(firstRun.size(), secondRun.size());
        for (int i = 0; i < firstRun.size(); i++) {
            assertEquals(firstRun.get(i).getId(), secondRun.get(i).getId(),
                    "Re-rularea aceluiasi fisier trebuie sa genereze exact acelasi UUID determinist per chunk");
        }

        // 2. Verificam deduplicarea la nivel de Controller (/upload)
        ManualDocument existingDoc = ManualDocument.builder()
                .id(UUID.randomUUID())
                .docId(docId)
                .checksum(checksum)
                .version("1.0")
                .fileName("fujifilm-ga645zi.pdf")
                .cameraModel("Fujifilm GA645Zi")
                .brand("Fujifilm")
                .tenantId("studio-brasov")
                .status(ManualDocument.IngestionStatus.COMPLETED)
                .totalChunks(firstRun.size())
                .uploadedAt(LocalDateTime.now())
                .build();

        when(documentRepository.findFirstByChecksumAndTenantIdAndCameraModelAndStatus(
                checksum, "studio-brasov", "Fujifilm GA645Zi", ManualDocument.IngestionStatus.COMPLETED
        )).thenReturn(Optional.of(existingDoc));

        MockMultipartFile multipartFile = new MockMultipartFile(
                "file", "fujifilm-ga645zi.pdf", "application/pdf", pdfBytes
        );

        ResponseEntity<?> response = ingestionController.uploadManual(
                multipartFile, "Fujifilm GA645Zi", "Fujifilm", "studio-brasov", "1.0"
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(documentRepository, never()).save(any());
        verify(vectorStore, never()).add(any());
    }

    @Test
    @DisplayName("Failure Path 1: PDF corupt seteaza status FAILED si salveaza mesajul de eroare")
    void shouldMarkDocumentAsFailedWhenPdfIsCorrupted() {
        UUID recordId = UUID.randomUUID();
        ManualDocument docRecord = ManualDocument.builder()
                .id(recordId)
                .docId("corrupt-doc")
                .checksum("abc123")
                .version("1.0")
                .fileName("broken.pdf")
                .cameraModel("Sony A7 IV")
                .brand("Sony")
                .tenantId("studio-brasov")
                .status(ManualDocument.IngestionStatus.PROCESSING)
                .build();

        when(documentRepository.findById(recordId)).thenReturn(Optional.of(docRecord));

        byte[] corruptedBytes = "THIS_IS_NOT_A_VALID_PDF_HEADER_%%%".getBytes(StandardCharsets.UTF_8);

        ingestionService.processDocumentAsync(
                recordId, corruptedBytes, "broken.pdf", "corrupt-doc", "1.0", "abc123",
                "Sony A7 IV", "Sony", "studio-brasov"
        );

        ArgumentCaptor<ManualDocument> captor = ArgumentCaptor.forClass(ManualDocument.class);
        verify(documentRepository).save(captor.capture());

        ManualDocument saved = captor.getValue();
        assertEquals(ManualDocument.IngestionStatus.FAILED, saved.getStatus());
        assertNotNull(saved.getErrorMessage());
        verify(vectorStore, never()).add(any());
    }

    @Test
    @DisplayName("Failure Path 2: Fisier cu extensie nepermisa sau gol este respins cu 400 Bad Request")
    void shouldRejectUnsupportedOrEmptyFiles() throws IOException {
        MockMultipartFile exeFile = new MockMultipartFile(
                "file", "malware.exe", "application/octet-stream", "MZ90".getBytes(StandardCharsets.UTF_8)
        );
        ResponseEntity<?> resUnsupported = ingestionController.uploadManual(
                exeFile, "Canon R6", "Canon", "studio-brasov", "1.0"
        );
        assertEquals(HttpStatus.BAD_REQUEST, resUnsupported.getStatusCode());

        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]
        );
        ResponseEntity<?> resEmpty = ingestionController.uploadManual(
                emptyFile, "Canon R6", "Canon", "studio-brasov", "1.0"
        );
        assertEquals(HttpStatus.BAD_REQUEST, resEmpty.getStatusCode());
    }
}