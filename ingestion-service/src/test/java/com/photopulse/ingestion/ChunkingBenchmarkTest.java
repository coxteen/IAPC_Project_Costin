package com.photopulse.ingestion;

import com.photopulse.ingestion.config.IngestionProperties;
import com.photopulse.ingestion.repository.ManualDocumentRepository;
import com.photopulse.ingestion.service.DocumentIngestionService;
import com.photopulse.ingestion.service.StructureAwareChunker;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class ChunkingBenchmarkTest {

    record ConfigProfile(String name, int chunkSize, int overlap, int parentSize, int minChars) {}

    @Test
    @DisplayName("Benchmark: Evalueaza 6 configuratii de chunking (size, overlap, parentSize, minChars)")
    void runChunkingParameterBenchmark() throws IOException {
        byte[] benchmarkPdf = generateRealisticCameraManualPdf();
        StructureAwareChunker chunker = new StructureAwareChunker();

        List<ConfigProfile> profiles = List.of(
                new ConfigProfile("1. Fine-Grained (120t / 0 overlap)", 120, 0, 600, 50),
                new ConfigProfile("2. Fine-Grained + Overlap (120t / 30t)", 120, 30, 600, 50),
                new ConfigProfile("3. Balanced No-Overlap (250t / 0t)", 250, 0, 1000, 80),
                new ConfigProfile("4. Balanced Recommended (250t / 50t)", 250, 50, 1000, 80),
                new ConfigProfile("5. High-Overlap Dense (250t / 80t)", 250, 80, 1200, 80),
                new ConfigProfile("6. Coarse Context (500t / 75t)", 500, 75, 1500, 100)
        );

        System.out.println("\n=== CHUNKING PARAMETER BENCHMARK RESULTS ===");
        System.out.printf("%-38s | %6s | %9s | %11s | %13s | %8s%n",
                "Profile", "Chunks", "Avg Chars", "Overlap %", "Table Intact", "Time(ms)");
        System.out.println("-".repeat(102));

        for (ConfigProfile p : profiles) {
            IngestionProperties props = new IngestionProperties();
            props.setChunkSize(p.chunkSize());
            props.setChunkOverlap(p.overlap());
            props.setParentChunkSize(p.parentSize());
            props.setMinChunkSizeChars(p.minChars());
            props.setEmbeddingModel("nomic-embed-text");

            DocumentIngestionService svc = new DocumentIngestionService(
                    mock(VectorStore.class), mock(ManualDocumentRepository.class), chunker, props
            );

            long t0 = System.nanoTime();
            List<Document> docs = svc.parseAndChunk(
                    benchmarkPdf, "benchmark-manual.pdf", "bench-doc", "1.0", "sha-bench",
                    "Fujifilm GA645Zi", "Fujifilm", "benchmark"
            );
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

            assertFalse(docs.isEmpty());

            double avgChars = docs.stream().mapToInt(d -> d.getText().length()).average().orElse(0);
            double overlapRetention = computeBoundaryOverlapRatio(docs);
            boolean tableRowIntact = docs.stream().anyMatch(d ->
                    d.getText().contains("Program (P)") && d.getText().contains("1/700s")
            );

            System.out.printf(Locale.US, "%-38s | %6d | %9.1f | %10.1f%% | %13s | %8d%n",
                    p.name(), docs.size(), avgChars, overlapRetention * 100.0,
                    tableRowIntact ? "YES (100%)" : "SPLIT", elapsedMs);
        }
    }

    private double computeBoundaryOverlapRatio(List<Document> docs) {
        if (docs.size() < 2) return 0.0;
        int overlappingPairs = 0;
        int samePagePairs = 0;
        for (int i = 0; i < docs.size() - 1; i++) {
            Integer p1 = (Integer) docs.get(i).getMetadata().get("page");
            Integer p2 = (Integer) docs.get(i + 1).getMetadata().get("page");
            if (Objects.equals(p1, p2)) {
                samePagePairs++;
                String t1 = docs.get(i).getText();
                String t2 = docs.get(i + 1).getText();
                String tail = t1.substring(Math.max(0, t1.length() - 35));
                if (t2.contains(tail.trim().substring(Math.min(10, tail.trim().length())))) {
                    overlappingPairs++;
                }
            }
        }
        return samePagePairs == 0 ? 0.0 : (double) overlappingPairs / samePagePairs;
    }

    private byte[] generateRealisticCameraManualPdf() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int p = 1; p <= 4; p++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    float y = 760;
                    String[] paragraphs = {
                            "Section " + p + ".1: Film Loading & ISO DX Coding System on Medium Format Cameras.",
                            "When loading 120 or 220 roll film into the Fujifilm GA645Zi, ensure the pressure plate matches the film format.",
                            "The camera reads the DX barcode automatically for ISO 25 through ISO 1600. For non-DX film, set ISO manually via the dial.",
                            "Section " + p + ".2: Autofocus Calibration & Flash Synchronization Table.",
                            "Program (P) | f/4.5 - f/22 | 2s - 1/700s | Built-in flash syncs at all shutter speeds up to 1/700s.",
                            "Aperture Priority (A) | f/4.5 - f/22 | 2s - 1/700s | Displays selected shutter speed in the LCD viewfinder.",
                            "Manual Mode (M) | Bulb (B), 4s - 1/700s | Ideal for tripod long exposures and studio strobes via PC sync terminal."
                    };
                    for (String line : paragraphs) {
                        cs.beginText();
                        cs.setFont(font, 10);
                        cs.newLineAtOffset(45, y);
                        cs.showText(line);
                        cs.endText();
                        y -= 24;
                    }
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}