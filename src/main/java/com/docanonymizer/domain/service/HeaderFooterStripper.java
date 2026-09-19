package com.docanonymizer.domain.service;

import com.docanonymizer.domain.model.ExtractedDocument;
import com.docanonymizer.domain.model.PageText;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Elimina cabeceras y pies repetidos antes de detectar.
 *
 * <p>No es una medida de seguridad: si el pie lleva el nombre del despacho, el detector
 * lo encontraria igual. Es una medida de utilidad. Sin esto, el Markdown de un expediente
 * de 60 paginas repite la misma linea 60 veces intercalada en mitad del texto, y el
 * revisor humano —que es quien sostiene todo el argumento de seguridad— se cansa antes
 * de llegar a lo que importa.
 *
 * <p>Se ejecuta sobre el texto crudo por paginas, antes de normalizar y concatenar.
 */
public final class HeaderFooterStripper {

    /** Cuantas lineas del principio y del final de cada pagina se consideran candidatas. */
    private static final int ZONE_SIZE = 3;

    private static final int MIN_PAGES = 3;
    private static final double MIN_PAGE_FRACTION = 0.5;
    private static final int MIN_KEY_LENGTH = 3;

    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public record Result(ExtractedDocument document, int removedLines, Set<String> removedKeys) {
    }

    public Result strip(ExtractedDocument document) {
        if (document.pageCount() < MIN_PAGES) {
            return new Result(document, 0, Set.of());
        }

        Map<String, Integer> pagesPerKey = countKeysAcrossPages(document);
        int threshold = Math.max(2, (int) Math.ceil(document.pageCount() * MIN_PAGE_FRACTION));

        Set<String> repeated = new HashSet<>();
        for (Map.Entry<String, Integer> entry : pagesPerKey.entrySet()) {
            if (entry.getValue() >= threshold) {
                repeated.add(entry.getKey());
            }
        }
        if (repeated.isEmpty()) {
            return new Result(document, 0, Set.of());
        }

        List<PageText> cleaned = new ArrayList<>(document.pageCount());
        int removed = 0;
        for (PageText page : document.pages()) {
            List<String> lines = page.lines();
            List<String> kept = new ArrayList<>(lines.size());
            for (int i = 0; i < lines.size(); i++) {
                boolean inZone = isInZone(i, lines.size());
                if (inZone && repeated.contains(key(lines.get(i)))) {
                    removed++;
                    continue;
                }
                kept.add(lines.get(i));
            }
            cleaned.add(new PageText(page.number(), kept));
        }
        return new Result(
                new ExtractedDocument(cleaned, document.sourceSha256()), removed, repeated);
    }

    private Map<String, Integer> countKeysAcrossPages(ExtractedDocument document) {
        Map<String, Integer> pagesPerKey = new HashMap<>();
        for (PageText page : document.pages()) {
            Set<String> seenOnThisPage = new HashSet<>();
            List<String> lines = page.lines();
            for (int i = 0; i < lines.size(); i++) {
                if (!isInZone(i, lines.size())) {
                    continue;
                }
                String key = key(lines.get(i));
                if (key.length() >= MIN_KEY_LENGTH && seenOnThisPage.add(key)) {
                    pagesPerKey.merge(key, 1, Integer::sum);
                }
            }
        }
        return pagesPerKey;
    }

    private boolean isInZone(int index, int total) {
        return index < ZONE_SIZE || index >= total - ZONE_SIZE;
    }

    /**
     * Clave de comparacion. Los digitos se sustituyen por '#' para que
     * "Pagina 3 de 40" y "Pagina 4 de 40" cuenten como la misma cabecera.
     */
    private String key(String line) {
        String collapsed = WHITESPACE.matcher(line.strip()).replaceAll(" ");
        String masked = DIGITS.matcher(collapsed).replaceAll("#");
        return CanonicalForm.forCompareKeepingWords(masked);
    }
}
