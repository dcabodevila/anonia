package com.docanonymizer.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Normalizacion del texto extraido")
class TextNormalizerTest {

    private final TextNormalizer normalizer = new TextNormalizer();

    @Test
    @DisplayName("une la palabra partida por guion al final de linea")
    void joinsHyphenatedLineBreak() {
        String result = normalizer.normalize("las manifesta-\nciones recogidas");

        assertEquals("las manifestaciones recogidas", result);
    }

    @Test
    @DisplayName("conserva el guion de un nombre propio compuesto")
    void keepsHyphenBeforeUppercase() {
        // "Vitoria-\nGasteiz" lleva mayuscula detras: es un guion legitimo, no un corte.
        String result = normalizer.normalize("domicilio en Vitoria-\nGasteiz, provincia");

        assertTrue(result.contains("Vitoria-"), () -> "se perdio el guion legitimo: " + result);
    }

    @Test
    @DisplayName("junta un titulo escrito letra a letra")
    void collapsesLetterSpacedHeading() {
        String result = normalizer.normalize("A C T A   D E   M A N I F E S T A C I O N E S");

        assertEquals("ACTA DE MANIFESTACIONES", result);
    }

    @Test
    @DisplayName("no toca una linea normal con palabras de una sola letra")
    void leavesOrdinaryLineAlone() {
        String line = "el articulo 5 y la letra a del apartado b";

        assertEquals(line, normalizer.normalize(line));
    }

    @Test
    @DisplayName("elimina el guion blando, que rompe los patrones sin verse")
    void removesSoftHyphen() {
        String withSoftHyphen = "12345678" + (char) 0x00AD + "Z";

        assertEquals("12345678Z", normalizer.normalize(withSoftHyphen));
    }

    @Test
    @DisplayName("convierte el espacio duro en espacio normal")
    void normalizesNoBreakSpace() {
        String withNoBreakSpace = "612" + (char) 0x00A0 + "345" + (char) 0x00A0 + "678";

        assertEquals("612 345 678", normalizer.normalize(withNoBreakSpace));
    }

    @Test
    @DisplayName("unifica las variantes tipograficas de guion")
    void normalizesDashVariants() {
        String withEnDash = "12345678" + (char) 0x2013 + "Z";

        assertEquals("12345678-Z", normalizer.normalize(withEnDash));
    }

    @Test
    @DisplayName("colapsa espacios repetidos pero respeta la separacion de parrafos")
    void collapsesWhitespaceKeepingParagraphs() {
        String result = normalizer.normalize("primer    parrafo\n\n\n\nsegundo parrafo");

        assertEquals("primer parrafo\n\nsegundo parrafo", result);
    }
}
