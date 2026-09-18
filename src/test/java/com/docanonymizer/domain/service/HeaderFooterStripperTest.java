package com.docanonymizer.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docanonymizer.domain.model.ExtractedDocument;
import com.docanonymizer.domain.model.PageText;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Limpieza de cabeceras y pies repetidos")
class HeaderFooterStripperTest {

    private final HeaderFooterStripper stripper = new HeaderFooterStripper();

    private static ExtractedDocument document(List<List<String>> pages) {
        List<PageText> pageTexts = new java.util.ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            pageTexts.add(new PageText(i + 1, pages.get(i)));
        }
        return new ExtractedDocument(pageTexts, "hash-de-prueba");
    }

    @Test
    @DisplayName("elimina la cabecera que se repite en todas las paginas")
    void removesRepeatedHeader() {
        ExtractedDocument document = document(List.of(
                List.of("DESPACHO EJEMPLO", "contenido uno"),
                List.of("DESPACHO EJEMPLO", "contenido dos"),
                List.of("DESPACHO EJEMPLO", "contenido tres")));

        String result = stripper.strip(document).document().rawText();

        assertFalse(result.contains("DESPACHO EJEMPLO"));
        assertTrue(result.contains("contenido uno"));
        assertTrue(result.contains("contenido tres"));
    }

    @Test
    @DisplayName("reconoce el pie aunque cambie el numero de pagina")
    void removesFooterWithVaryingPageNumber() {
        // "Pagina 1 de 3" y "Pagina 2 de 3" son el mismo pie; los digitos se enmascaran
        // antes de comparar para que no cuenten como lineas distintas.
        ExtractedDocument document = document(List.of(
                List.of("contenido uno", "Pagina 1 de 3"),
                List.of("contenido dos", "Pagina 2 de 3"),
                List.of("contenido tres", "Pagina 3 de 3")));

        String result = stripper.strip(document).document().rawText();

        assertFalse(result.contains("Pagina"));
    }

    @Test
    @DisplayName("no toca una linea que solo aparece una vez")
    void keepsUniqueLines() {
        ExtractedDocument document = document(List.of(
                List.of("CABECERA", "unico en la primera"),
                List.of("CABECERA", "contenido dos"),
                List.of("CABECERA", "contenido tres")));

        String result = stripper.strip(document).document().rawText();

        assertTrue(result.contains("unico en la primera"));
    }

    @Test
    @DisplayName("no actua con menos de tres paginas: no hay evidencia de repeticion")
    void doesNothingOnShortDocuments() {
        ExtractedDocument document = document(List.of(
                List.of("CABECERA", "contenido uno"),
                List.of("CABECERA", "contenido dos")));

        HeaderFooterStripper.Result result = stripper.strip(document);

        assertEquals(0, result.removedLines());
        assertTrue(result.document().rawText().contains("CABECERA"));
    }

    @Test
    @DisplayName("no elimina una linea repetida que esta en mitad del texto")
    void ignoresRepetitionOutsideHeaderZone() {
        ExtractedDocument document = document(List.of(
                List.of("a", "b", "c", "REPETIDA EN EL CUERPO", "d", "e", "f"),
                List.of("a", "b", "c", "REPETIDA EN EL CUERPO", "d", "e", "f"),
                List.of("a", "b", "c", "REPETIDA EN EL CUERPO", "d", "e", "f")));

        String result = stripper.strip(document).document().rawText();

        assertTrue(result.contains("REPETIDA EN EL CUERPO"),
                "solo se limpian las zonas de cabecera y pie, no el cuerpo");
    }
}
