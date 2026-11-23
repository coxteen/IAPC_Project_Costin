package com.photopulse.ingestion.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class StructureAwareChunker {

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern MULTI_WHITESPACE = Pattern.compile("[ \\t]{2,}");
    private static final Pattern MULTI_NEWLINE = Pattern.compile("\\n{3,}");

    /**
     * Curata artefactele de extragere PDF pastrand structura de paragrafe si tabele.
     */
    public String sanitizeText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String cleaned = CONTROL_CHARS.matcher(raw).replaceAll("");
        cleaned = cleaned.replace("\r\n", "\n").replace("\r", "\n");
        cleaned = MULTI_WHITESPACE.matcher(cleaned).replaceAll(" ");
        cleaned = MULTI_NEWLINE.matcher(cleaned).replaceAll("\n\n");
        return cleaned.trim();
    }

    /**
     * Structure-aware chunking cu overlap (in caractere, derivat din tokeni ~4 chars/token).
     * Respecta granitele de paragraf (\n\n), linie/rand tabel (\n) si propozitie (. ! ?).
     */
    public List<String> chunkWithOverlap(String text, int targetTokens, int overlapTokens, int minChunkChars) {
        String clean = sanitizeText(text);
        if (clean.isEmpty()) {
            return List.of();
        }

        int targetChars = Math.max(100, targetTokens * 4);
        int overlapChars = Math.max(0, Math.min(overlapTokens * 4, targetChars / 2));

        if (clean.length() <= targetChars) {
            return clean.length() >= minChunkChars ? List.of(clean) : List.of(clean);
        }

        List<String> chunks = new ArrayList<>();
        int start = 0;
        int length = clean.length();

        while (start < length) {
            int maxEnd = Math.min(start + targetChars, length);
            int end = maxEnd;

            if (maxEnd < length) {
                end = findBestBoundary(clean, start, maxEnd);
            }

            String piece = clean.substring(start, end).trim();
            if (!piece.isEmpty()) {
                if (!chunks.isEmpty() && piece.length() < minChunkChars) {
                    // Lipim un fragment final prea scurt de ultimul chunk pentru a evita fragmente izolate
                    String last = chunks.remove(chunks.size() - 1);
                    chunks.add(last + "\n" + piece);
                } else {
                    chunks.add(piece);
                }
            }

            if (end >= length) {
                break;
            }

            int nextStart = end - overlapChars;
            if (nextStart <= start) {
                nextStart = end; // Garantam progresul pentru a preveni bucle infinite
            } else {
                // Aliniem inceputul overlap-ului la inceput de cuvant/linie
                int wordBoundary = clean.indexOf(' ', nextStart);
                if (wordBoundary != -1 && wordBoundary < end) {
                    nextStart = wordBoundary + 1;
                }
            }
            start = nextStart;
        }

        return chunks;
    }

    private int findBestBoundary(String text, int start, int maxEnd) {
        int minSearch = start + (maxEnd - start) / 2;

        // 1. Prioritate maxima: sfarsit de paragraf (\n\n)
        int paraBreak = text.lastIndexOf("\n\n", maxEnd);
        if (paraBreak >= minSearch) {
            return paraBreak;
        }

        // 2. Rand nou (util pentru tabele si liste)
        int lineBreak = text.lastIndexOf('\n', maxEnd);
        if (lineBreak >= minSearch) {
            return lineBreak;
        }

        // 3. Sfarsit de propozitie (. ! ?)
        for (int i = maxEnd - 1; i >= minSearch; i--) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && (i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1)))) {
                return i + 1;
            }
        }

        // 4. Spatiu intre cuvinte
        int spaceBreak = text.lastIndexOf(' ', maxEnd);
        if (spaceBreak >= minSearch) {
            return spaceBreak;
        }

        return maxEnd;
    }
}