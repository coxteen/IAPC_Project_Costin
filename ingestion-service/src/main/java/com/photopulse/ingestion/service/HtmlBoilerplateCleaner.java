package com.photopulse.ingestion.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class HtmlBoilerplateCleaner {

    private static final List<String> BOILERPLATE_SELECTORS = List.of(
            "nav", "footer", "header", "aside",
            "script", "style", "noscript", "iframe", "svg", "form", "button",
            "[role=navigation]", "[role=banner]", "[role=contentinfo]", "[role=complementary]",
            ".cookie", "#cookie", ".cookies", "#cookies",
            "[class*=cookie]", "[id*=cookie]",
            "[class*=consent]", "[id*=consent]",
            "[class*=gdpr]", "[id*=gdpr]",
            "[class*=banner]", "[class*=popup]", "[class*=modal]",
            "[class*=navbar]", "[class*=sidebar]", "[class*=footer]", "[class*=header]",
            "[class*=breadcrumb]", "[class*=advertisement]", "[class*=promo]", "[class*=social-share]"
    );

    /**
     * Parseaza HTML brut, elimina elementele de navigatie, subsoluri, bannere cookie si scripturi,
     * apoi extrage textul curat al articolului / continutului principal.
     */
    public String extractCleanText(String rawHtml, String baseUrl) {
        if (rawHtml == null || rawHtml.isBlank()) {
            return "";
        }

        Document doc = Jsoup.parse(rawHtml, baseUrl == null ? "" : baseUrl);

        // 1. Eliminam toate elementele boilerplate
        for (String selector : BOILERPLATE_SELECTORS) {
            doc.select(selector).remove();
        }

        // 2. Prioritizam containerul principal de continut daca exista
        Element contentRoot = doc.selectFirst("article, main, [role=main], .markdown-body, .post-content, .entry-content");
        if (contentRoot == null) {
            contentRoot = doc.body();
        }
        if (contentRoot == null) {
            return "";
        }

        // 3. Adaugam delimitari de linie la blocurile structurale inainte de extragerea textului
        contentRoot.select("h1, h2, h3, h4, h5, h6, p, li, tr, br, div, section").prepend("\\n");

        String title = doc.title() != null ? doc.title().trim() : "";
        String extracted = contentRoot.text()
                .replace("\\n", "\n")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();

        if (!title.isBlank() && !extracted.startsWith(title)) {
            return title + "\n\n" + extracted;
        }
        return extracted;
    }
}