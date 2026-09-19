package com.docanonymizer.domain.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Esta guarda existe para un modo de fallo silencioso: si el PDF extrae basura, no se
 * detecta nada, la verificacion pasa —no hay valores que buscar— y se entrega un fichero
 * ilegible que el usuario cree anonimizado. Fallar cerrado es la unica respuesta correcta.
 */
@DisplayName("Guarda de cordura sobre el texto extraido")
class TextSanityGuardTest {

    private final TextSanityGuard guard = new TextSanityGuard();

    private static final String REAL_TEXT = """
            En Madrid, a 14 de marzo de 2024, comparece ante mi el interesado,
            mayor de edad, y manifiesta que el expediente se encuentra pendiente
            de resolucion conforme a lo previsto en la normativa aplicable.
            """;

    @Test
    @DisplayName("acepta texto en lenguaje natural")
    void acceptsNaturalLanguage() {
        assertTrue(guard.check(REAL_TEXT).usable());
    }

    @Test
    @DisplayName("rechaza un documento practicamente sin texto, como un escaneo")
    void rejectsScannedDocument() {
        TextSanityGuard.Result result = guard.check("  \n \n 3 \n");

        assertFalse(result.usable());
        assertTrue(result.reason().contains("capa de texto"));
    }

    @Test
    @DisplayName("rechaza el mojibake de un /ToUnicode roto")
    void rejectsBrokenToUnicode() {
        String replacementChar = String.valueOf((char) 0xFFFD);
        String garbage = REAL_TEXT + replacementChar.repeat(REAL_TEXT.length());

        TextSanityGuard.Result result = guard.check(garbage);

        assertFalse(result.usable());
        assertTrue(result.reason().contains("ToUnicode"));
    }

    @Test
    @DisplayName("rechaza texto mapeado al area de uso privado de Unicode")
    void rejectsPrivateUseArea() {
        // Sintoma tipico de una fuente embebida sin mapa de caracteres correcto.
        String privateUse = String.valueOf((char) 0xE000).repeat(200);

        assertFalse(guard.check(privateUse).usable());
    }

    @Test
    @DisplayName("rechaza contenido que no parece lenguaje natural")
    void rejectsNonLinguisticContent() {
        String mostlySymbols = "8,45 / 12,90 * 33,11 + 902,55 = 41,07 - 55,20 % 71,03 ".repeat(6);

        TextSanityGuard.Result result = guard.check(mostlySymbols);

        assertFalse(result.usable());
        assertTrue(result.reason().contains("letras"));
    }
}
