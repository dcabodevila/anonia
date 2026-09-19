package com.docanonymizer.domain.model;

import java.util.List;

/** Resultado de la extraccion: paginas crudas, sin normalizar ni limpiar. */
public record ExtractedDocument(List<PageText> pages, String sourceSha256) {

    public ExtractedDocument {
        pages = List.copyOf(pages);
    }

    public int pageCount() {
        return pages.size();
    }

    public String rawText() {
        return String.join("\n", pages.stream().map(PageText::joined).toList());
    }
}
