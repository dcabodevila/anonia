package com.docanonymizer.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docanonymizer.adapter.PipelineFactory;
import com.docanonymizer.domain.model.AnonymizationResult;
import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.service.CanonicalForm;
import com.docanonymizer.domain.service.TextFolding;
import com.docanonymizer.tools.SampleDocumentGenerator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Corpus de seguridad en miniatura: se genera un PDF con datos personales SEMBRADOS, se
 * pasa el pipeline completo y se comprueba que ninguno sobrevive en el Markdown.
 *
 * <p>Es la prueba que de verdad importa, porque es la unica que verifica la propiedad que
 * el producto promete. Las pruebas unitarias comprueban que cada pieza hace lo suyo; esta
 * comprueba que el conjunto no deja escapar nada, que es una afirmacion distinta.
 *
 * <p>Incluye tambien controles negativos: si el pipeline aprobase ocultandolo todo, no
 * serviria de nada, asi que se exige ademas que el contenido legitimo siga presente.
 */
@DisplayName("Corpus de seguridad: PDF sembrado -> Markdown")
class SecurityCorpusEndToEndTest {

    @TempDir
    static Path workDir;

    private static AnonymizationResult result;
    private static String markdown;

    @BeforeAll
    static void runPipeline() throws IOException {
        Path pdf = workDir.resolve("corpus.pdf");
        SampleDocumentGenerator.generate(pdf);

        result = PipelineFactory.standard().run(pdf);
        markdown = result.markdown();
    }

    @Test
    @DisplayName("la verificacion aprueba el documento")
    void verificationPasses() {
        assertTrue(result.deliverable(),
                () -> "verificacion bloqueada: " + result.verification().blocking());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "12345678Z",
            "X1234567L",
            "ES9121000418450200051332",
            "juan.perez@example.com",
            "612 345 678",
            "612345678",
            "1234 BCD",
            "Juan Perez Lopez",
            "JUAN PEREZ LOPEZ",
            "Perez Lopez",
            "Ana Ruiz Molina",
            "Carlos Diaz Vega",
            "Calle Mayor 15"})
    @DisplayName("ningun valor sembrado sobrevive, ni siquiera reformateado")
    void seededValuesDoNotSurvive(String secret) {
        assertFalse(TextFolding.fold(markdown).contains(TextFolding.fold(secret)),
                () -> "valor sembrado presente en la salida: " + secret);

        String squashed = CanonicalForm.forCompare(secret);
        if (squashed.length() >= 8) {
            assertFalse(CanonicalForm.forCompare(markdown).contains(squashed),
                    () -> "valor sembrado presente con otro formato: " + secret);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Perez", "Lopez", "Ruiz", "Molina", "Diaz", "Vega", "Carlos"})
    @DisplayName("tampoco sobrevive un apellido suelto: la propagacion los alcanza")
    void surnamesDoNotSurviveAlone(String token) {
        assertTrue(TextFolding.findWholeWordOccurrences(markdown, token).isEmpty(),
                () -> "token de nombre suelto en la salida: " + token);
    }

    @Test
    @DisplayName("un numero con forma de DNI pero digito de control invalido NO se oculta")
    void invalidChecksumIsNotRedacted() {
        // Control negativo: si el pipeline ocultase todo lo que se parece a un DNI,
        // pasaria las pruebas anteriores siendo inutil. La letra de control es lo que
        // distingue el documento de una persona de una referencia interna.
        assertTrue(markdown.contains("87654321A"),
                "una referencia interna no es un dato personal y debe conservarse");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ANTECEDENTES DE HECHO",
            "FUNDAMENTOS DE DERECHO",
            "Codigo Civil",
            "contrato de arrendamiento"})
    @DisplayName("el contenido legitimo del documento se conserva")
    void legitimateContentSurvives(String content) {
        assertTrue(markdown.contains(content),
                () -> "se perdio contenido que no era un dato personal: " + content);
    }

    @Test
    @DisplayName("la normalizacion arregla el guion de fin de linea y el titulo espaciado")
    void normalizationArtifactsAreFixed() {
        assertTrue(markdown.contains("manifestaciones"), "no se unio la palabra partida");
        assertTrue(markdown.contains("ACTA DE MANIFESTACIONES"),
                "no se junto el titulo escrito letra a letra");
    }

    @Test
    @DisplayName("la cabecera y el pie repetidos desaparecen")
    void repeatedHeaderAndFooterAreGone() {
        assertFalse(markdown.contains("DESPACHO JURIDICO EJEMPLO"));
        assertFalse(markdown.contains("Pagina 2 de 3"));
    }

    @Test
    @DisplayName("las tres menciones de la misma persona comparten un unico seudonimo")
    void sameHumanGetsOneLabel() {
        Set<String> personLabels = result.accepted().stream()
                .filter(d -> d.type() == DetectionType.PERSON)
                .map(d -> d.value().toLowerCase())
                .filter(v -> v.contains("perez"))
                .map(v -> labelOf(v))
                .collect(Collectors.toSet());

        assertEquals(1, personLabels.size(),
                () -> "la misma persona recibio varias etiquetas: " + personLabels);
    }

    @Test
    @DisplayName("cada tipo esperado fue detectado al menos una vez")
    void allExpectedTypesWereDetected() {
        Set<DetectionType> found = result.accepted().stream()
                .map(Detection::type)
                .collect(Collectors.toSet());

        for (DetectionType expected : List.of(
                DetectionType.PERSON, DetectionType.DNI, DetectionType.NIE,
                DetectionType.IBAN, DetectionType.EMAIL, DetectionType.PHONE,
                DetectionType.PLATE, DetectionType.ADDRESS, DetectionType.POSTAL_CODE)) {
            assertTrue(found.contains(expected), () -> "tipo no detectado: " + expected);
        }
    }

    @Test
    @DisplayName("la salida comienza con el documento anonimizado, sin cabecera generada")
    void outputHasNoGeneratedHeader() {
        assertTrue(markdown.startsWith("## ACTA DE MANIFESTACIONES"),
                () -> "la salida debe empezar por el cuerpo del documento: " + markdown);
        assertTrue(markdown.contains("[PERSONA_001]"),
                "el cuerpo debe conservar los reemplazos anonimizados");
        assertFalse(markdown.startsWith("<!--"),
                "la salida no debe incluir una cabecera HTML generada");
    }

    private String labelOf(String loweredValue) {
        Map<String, String> pseudonyms = result.pseudonyms();
        return result.accepted().stream()
                .filter(d -> d.value().toLowerCase().equals(loweredValue))
                .map(d -> pseudonyms.get(d.entityKey()))
                .findFirst()
                .orElseThrow();
    }
}
