package com.docanonymizer.domain.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.model.Finding;
import com.docanonymizer.domain.model.Provenance;
import com.docanonymizer.domain.model.VerificationReport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La puerta de salida es el unico control del prototipo que puede fallar de verdad, asi
 * que lo que hay que demostrar no es que deje pasar lo correcto, sino que BLOQUEA lo
 * incorrecto. Un verificador que nunca falla no verifica nada.
 */
@DisplayName("Puerta de salida: verificacion de la redaccion")
class RedactionVerifierTest {

    private final RedactionVerifier verifier = new RedactionVerifier(new RunScopedIdentifier());

    private static Detection person(String value) {
        return new Detection("d1", DetectionType.PERSON, 0, value.length(), value,
                "PERSON:" + CanonicalForm.forCompareKeepingWords(value),
                Provenance.STRUCTURAL_CUE, 0.95);
    }

    @Test
    void retainsPersonTokenChecksAfterTypeChange() {
        Detection original = person("Maria Garcia Lopez");
        Detection edited = new Detection(original.id(), DetectionType.EMAIL, original.start(),
                original.end(), original.value(), original.entityKey(), original.provenance(), original.confidence());
        VerificationReport report = verifier.verify("[EMAIL_001] y Garcia", List.of(edited),
                Map.of(edited.entityKey(), "[EMAIL_001]"), List.of(original));
        assertTrue(hasControl(report, "C3-TOKENS-DE-NOMBRE"));
    }

    @Test
    void retainsAbsorbedPersonChecksWithoutTreatingAddressWordsAsNames() {
        Detection original = new Detection("person", DetectionType.PERSON, 6, 24,
                "Maria Garcia Lopez", "person-key", Provenance.REGEX, .9);
        Detection expanded = new Detection("address", DetectionType.ADDRESS, 0, 28,
                "Calle Maria Garcia Lopez, 96", "address-key", Provenance.REGEX, .9);
        var labels = Map.of(expanded.entityKey(), "[DIRECCION_001]");
        var accepted = List.of(expanded);
        var originals = List.of(original);
        assertTrue(hasControl(verifier.verify("[DIRECCION_001] y Garcia", accepted, labels, originals),
                "C3-TOKENS-DE-NOMBRE"));
        assertTrue(verifier.verify("[DIRECCION_001] otra Calle", accepted, labels, originals).passed());
        assertFalse(hasControl(verifier.verify("Garcia", List.of(), Map.of(), originals),
                "C3-TOKENS-DE-NOMBRE"), "Rejected, uncovered people must not become accepted checks");
    }

    @Test
    void checksEveryAbsorbedOriginalPersonEvenWhenTheirEntityIsShared() {
        var originals = List.of(
                new Detection("p1", DetectionType.PERSON, 0, 17, "Maria Garcia Ruiz", "shared", Provenance.REGEX, .9),
                new Detection("p2", DetectionType.PERSON, 20, 30, "Ana Torres", "shared", Provenance.REGEX, .9));
        var expanded = new Detection("a", DetectionType.ADDRESS, 0, 40,
                "Maria Garcia Ruiz y Ana Torres en casa", "address", Provenance.REGEX, .9);
        assertTrue(hasControl(verifier.verify("[DIRECCION_001] y Torres", List.of(expanded),
                Map.of("address", "[DIRECCION_001]"), originals), "C3-TOKENS-DE-NOMBRE"));
    }

    private static Detection dni(String value) {
        return new Detection("d2", DetectionType.DNI, 0, value.length(), value,
                "DNI:" + CanonicalForm.forCompare(value),
                Provenance.CHECKSUM_VALIDATED, 0.99);
    }

    @Test
    @DisplayName("aprueba una salida en la que todo fue sustituido")
    void passesWhenEverythingWasReplaced() {
        Detection detection = person("Juan Perez Lopez");
        String markdown = "Comparece [PERSONA_001], mayor de edad.";

        VerificationReport report = verifier.verify(
                markdown, List.of(detection), Map.of(detection.entityKey(), "[PERSONA_001]"));

        assertTrue(report.passed(), () -> "no deberia bloquear: " + report.findings());
    }

    @Test
    @DisplayName("bloquea si el valor sigue literalmente en la salida")
    void blocksLiteralLeak() {
        Detection detection = dni("12345678Z");
        String markdown = "Con DNI [DNI_001], repetido por error: 12345678Z.";

        VerificationReport report = verifier.verify(
                markdown, List.of(detection), Map.of(detection.entityKey(), "[DNI_001]"));

        assertFalse(report.passed());
        assertTrue(hasControl(report, "C1-VALOR-LITERAL"));
    }

    @Test
    @DisplayName("bloquea si el valor reaparece escrito con otro formato")
    void blocksReformattedLeak() {
        Detection detection = dni("12345678Z");
        // Mismo dato, distinta puntuacion: la busqueda literal no lo ve, la aplastada si.
        String markdown = "Con DNI [DNI_001]. Referencia: 12.345.678-Z";

        VerificationReport report = verifier.verify(
                markdown, List.of(detection), Map.of(detection.entityKey(), "[DNI_001]"));

        assertFalse(report.passed());
        assertTrue(hasControl(report, "C2-VALOR-REFORMATEADO"));
    }

    @Test
    @DisplayName("bloquea si la propagacion dejo un apellido suelto")
    void blocksLeftoverSurname() {
        Detection detection = person("Juan Perez Lopez");
        // El nombre completo desaparecio, pero el apellido sigue ahi: la fuga que
        // ningun control de "valor completo" es capaz de ver.
        String markdown = "Comparece [PERSONA_001]. Mas abajo se cita a Lopez de nuevo.";

        VerificationReport report = verifier.verify(
                markdown, List.of(detection), Map.of(detection.entityKey(), "[PERSONA_001]"));

        assertFalse(report.passed());
        assertTrue(hasControl(report, "C3-TOKENS-DE-NOMBRE"));
    }

    @Test
    @DisplayName("detecta la fuga aunque cambien acentos y mayusculas")
    void blocksLeakIgnoringCaseAndAccents() {
        Detection detection = person("Andrés Ibáñez Soto");
        String markdown = "Comparece [PERSONA_001]. Firmado: ANDRES IBANEZ SOTO";

        VerificationReport report = verifier.verify(
                markdown, List.of(detection), Map.of(detection.entityKey(), "[PERSONA_001]"));

        assertFalse(report.passed(), "una fuga sin acentos sigue siendo una fuga");
    }

    @Test
    @DisplayName("bloquea si una entidad se quedo sin seudonimo")
    void blocksMissingPseudonym() {
        Detection detection = person("Juan Perez Lopez");

        VerificationReport report = verifier.verify("Texto sin etiquetas.",
                List.of(detection), Map.of());

        assertFalse(report.passed());
        assertTrue(hasControl(report, "C4-COBERTURA-DE-SUSTITUCION"));
    }

    @Test
    @DisplayName("el informe describe el control, nunca el valor filtrado")
    void findingsNeverContainTheValue() {
        Detection detection = dni("12345678Z");
        String markdown = "Fuga: 12345678Z [DNI_001]";

        VerificationReport report = verifier.verify(
                markdown, List.of(detection), Map.of(detection.entityKey(), "[DNI_001]"));

        for (Finding finding : report.findings()) {
            assertFalse(finding.detail().contains("12345678Z"),
                    () -> "el informe filtro el valor: " + finding.detail());
        }
    }

    private List<Detection> sharedAddressVariants() {
        return List.of(
                new Detection("long", DetectionType.ADDRESS, 0, 20, "Calle Mayor 12345678",
                        "shared-address", Provenance.REGEX, .9),
                new Detection("short", DetectionType.ADDRESS, 30, 38, "12345678",
                        "shared-address", Provenance.REGEX, .9));
    }

    @Test
    void blocksShorterSameEntityLiteralVariant() {
        var report = verifier.verify("[DIRECCION_001] referencia 12345678",
                sharedAddressVariants(), Map.of("shared-address", "[DIRECCION_001]"));

        assertFalse(hasControl(report, "C3-TOKENS-DE-NOMBRE"));
        assertFalse(hasControl(report, "C4-COBERTURA-DE-SUSTITUCION"));
        assertTrue(hasControl(report, "C1-VALOR-LITERAL"));
        assertFalse(report.passed());
    }

    @Test
    void blocksShorterSameEntityReformattedVariant() {
        var report = verifier.verify("[DIRECCION_001] referencia 12.345.678",
                sharedAddressVariants(), Map.of("shared-address", "[DIRECCION_001]"));

        assertFalse(hasControl(report, "C1-VALOR-LITERAL"));
        assertFalse(hasControl(report, "C3-TOKENS-DE-NOMBRE"));
        assertFalse(hasControl(report, "C4-COBERTURA-DE-SUSTITUCION"));
        assertTrue(hasControl(report, "C2-VALOR-REFORMATEADO"));
        assertFalse(report.passed());
    }

    @Test
    void sharedVariantsPassWhenRemovedAndKeepCoverageEntityDeduplicated() {
        var accepted = sharedAddressVariants();
        assertTrue(verifier.verify("[DIRECCION_001]", accepted,
                Map.of("shared-address", "[DIRECCION_001]")).passed());
        var missingLabel = verifier.verify("Texto sin etiquetas.", accepted, Map.of());
        org.junit.jupiter.api.Assertions.assertEquals(1, missingLabel.blocking().stream()
                .filter(f -> f.control().equals("C4-COBERTURA-DE-SUSTITUCION")).count());
    }

    private boolean hasControl(VerificationReport report, String control) {
        return report.blocking().stream().anyMatch(f -> f.control().equals(control));
    }
}
