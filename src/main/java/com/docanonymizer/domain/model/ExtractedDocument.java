package com.docanonymizer.domain.model;

import java.util.List;

/**
 * Resultado de la extraccion: paginas crudas, sin normalizar ni limpiar.
 *
 * <p>{@code nativeText} indica que el texto es el que el formato guarda tal cual (Word,
 * Excel, Markdown, TXT) y no una inferencia a partir de lo renderizado (capa de texto de un
 * PDF, OCR). Por defecto es falso: ante la duda se aplica la guarda de cordura completa.
 */
public record ExtractedDocument(List<PageText> pages, String sourceSha256, boolean nativeText) {

    public ExtractedDocument {
        pages = List.copyOf(pages);
    }

    public ExtractedDocument(List<PageText> pages, String sourceSha256) {
        this(pages, sourceSha256, false);
    }

    public static ExtractedDocument nativeText(List<PageText> pages, String sourceSha256) {
        return new ExtractedDocument(pages, sourceSha256, true);
    }

    public int pageCount() {
        return pages.size();
    }

    public String rawText() {
        return String.join("\n", pages.stream().map(PageText::joined).toList());
    }
}
