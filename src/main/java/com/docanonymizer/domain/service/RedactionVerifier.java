package com.docanonymizer.domain.service;

import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.model.Finding;
import com.docanonymizer.domain.model.Severity;
import com.docanonymizer.domain.model.VerificationReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puerta de salida. Comprueba sobre el Markdown FINAL que ningun valor aceptado sobrevive.
 *
 * <p>Este verificador puede fallar de verdad, y esa es toda su razon de ser. En el diseno
 * anterior —reconstruir un PDF desde un bitmap enmascarado— los controles de "no queda
 * texto", "no hay JavaScript" y "no hay metadatos heredados" pasaban siempre por
 * construccion: no probaban nada. Con salida en texto plano la comprobacion es directa:
 * el valor esta o no esta.
 *
 * <p>Se ejecuta sobre el artefacto exacto que se va a escribir, cabecera incluida, no
 * sobre un intermedio.
 */
public final class RedactionVerifier {

    /**
     * Longitud minima para buscar la version "aplastada" (sin espacios ni puntuacion).
     * Por debajo de este umbral la busqueda genera falsos bloqueos: un CP de 5 digitos
     * aparece por casualidad dentro de cualquier importe largo.
     */
    private static final int MIN_SQUASHED_LENGTH = 8;

    private static final String C1 = "C1-VALOR-LITERAL";
    private static final String C2 = "C2-VALOR-REFORMATEADO";
    private static final String C3 = "C3-TOKENS-DE-NOMBRE";
    private static final String C4 = "C4-COBERTURA-DE-SUSTITUCION";
    private static final String C5 = "C5-SALIDA-NO-VACIA";

    private final RunScopedIdentifier identifier;

    public RedactionVerifier(RunScopedIdentifier identifier) {
        this.identifier = identifier;
    }

    public VerificationReport verify(
            String markdown, List<Detection> accepted, Map<String, String> pseudonyms) {

        return verify(markdown, accepted, pseudonyms, accepted);
    }

    /** Original classification remains relevant after a reviewer changes the display type. */
    public VerificationReport verify(String markdown, List<Detection> accepted,
            Map<String, String> pseudonyms, List<Detection> original) {
        return verify(markdown, accepted, pseudonyms, original, java.util.Set.of());
    }

    /** Rejections come from validated server replay, never from missing effective spans. */
    public VerificationReport verify(String markdown, List<Detection> accepted,
            Map<String, String> pseudonyms, List<Detection> original, java.util.Set<String> rejectedIds) {
        var originalPeople = original.stream().filter(d -> d.type() == DetectionType.PERSON)
                .map(Detection::id).collect(java.util.stream.Collectors.toSet());
        List<Detection> nameChecks = accepted.stream().map(d -> originalPeople.contains(d.id())
                ? new Detection(d.id(), DetectionType.PERSON, d.start(), d.end(), d.value(),
                        d.entityKey(), d.provenance(), d.confidence()) : d).toList();
        List<Finding> findings = new ArrayList<>();
        String squashedOutput = CanonicalForm.forCompare(markdown);

        checkOutputNotEmpty(markdown, findings);
        checkLiteralValues(markdown, accepted, findings);
        checkReformattedValues(squashedOutput, accepted, findings);
        checkNameTokens(markdown, nameChecks, findings);
        // Accepted outer spans hide retained tags, including accepted PERSON tags.
        // Only explicit rejections suppress their original name checks.
        var acceptedIds = accepted.stream().map(Detection::id).collect(java.util.stream.Collectors.toSet());
        for (Detection person : original) {
            if (person.type() == DetectionType.PERSON && !rejectedIds.contains(person.id())
                    && !acceptedIds.contains(person.id())
                    && accepted.stream().anyMatch(d -> d.start() <= person.start() && d.end() >= person.end())) {
                checkNameTokens(markdown, List.of(person), findings);
            }
        }
        checkSubstitutionCoverage(markdown, accepted, pseudonyms, findings);

        boolean clean = findings.stream().allMatch(f -> f.severity() == Severity.INFO);
        if (clean) {
            findings.add(Finding.info(
                    "RESUMEN", accepted.size() + " sustituciones verificadas sin incidencias"));
        }
        return new VerificationReport(findings);
    }

    private void checkOutputNotEmpty(String markdown, List<Finding> findings) {
        if (markdown.isBlank()) {
            findings.add(Finding.blocking(C5, "el Markdown generado esta vacio"));
        }
    }

    /** Fuga evidente: el valor sigue ahi tal cual, ignorando caja y acentos. */
    private void checkLiteralValues(
            String markdown, List<Detection> accepted, List<Finding> findings) {
        for (Detection detection : accepted) {
            if (!TextFolding.findWholeWordOccurrences(markdown, detection.value()).isEmpty()) {
                findings.add(Finding.blocking(C1,
                        describe(detection) + " sigue presente literalmente en la salida"));
            }
        }
    }

    /**
     * Fuga con otro formato: "ES91 2100 0418 45" frente a "ES9121000418 45". Se compara
     * sobre la forma aplastada, que ignora espacios, guiones y puntuacion.
     */
    private void checkReformattedValues(
            String squashedOutput, List<Detection> accepted, List<Finding> findings) {
        for (Detection detection : accepted) {
            String squashedValue = CanonicalForm.forCompare(detection.value());
            if (squashedValue.length() < MIN_SQUASHED_LENGTH) {
                continue;
            }
            if (squashedOutput.contains(squashedValue)) {
                findings.add(Finding.blocking(C2,
                        describe(detection) + " reaparece en la salida con otro formato"));
            }
        }
    }

    /**
     * El control que de verdad importa para nombres: comprueba que la propagacion no
     * dejo suelto un apellido. Si se oculto "Maria Garcia Perez" pero mas abajo quedaba
     * un "Garcia" sin tocar, la desidentificacion no sirve de nada, y ninguno de los
     * controles anteriores lo ve porque el valor completo si desaparecio.
     */
    private void checkNameTokens(
            String markdown, List<Detection> accepted, List<Finding> findings) {
        Map<String, String> longestNameByEntity = new LinkedHashMap<>();
        for (Detection detection : accepted) {
            if (detection.type() != DetectionType.PERSON) {
                continue;
            }
            longestNameByEntity.merge(detection.entityKey(), detection.value(),
                    (a, b) -> a.length() >= b.length() ? a : b);
        }

        longestNameByEntity.forEach((entityKey, fullName) -> {
            for (String token : NamePolicy.significantTokens(fullName)) {
                if (!TextFolding.findWholeWordOccurrences(markdown, token).isEmpty()) {
                    findings.add(Finding.blocking(C3,
                            "un fragmento de nombre de una entidad aceptada (entidad " + identifier.of(entityKey)
                                    + ") permanece en el resultado; revise las sustituciones antes de entregarlo"));
                }
            }
        });
    }

    /** Comprueba que cada entidad dejo su etiqueta en el resultado. */
    private void checkSubstitutionCoverage(
            String markdown,
            List<Detection> accepted,
            Map<String, String> pseudonyms,
            List<Finding> findings) {

        for (Detection detection : distinctByEntity(accepted)) {
            String label = pseudonyms.get(detection.entityKey());
            if (label == null) {
                findings.add(Finding.blocking(C4,
                        describe(detection) + " no tiene seudonimo asignado"));
            } else if (!markdown.contains(label)) {
                findings.add(Finding.blocking(C4,
                        "el seudonimo " + label + " no aparece en la salida pese a tener "
                                + "detecciones aceptadas"));
            }
        }
    }

    private List<Detection> distinctByEntity(List<Detection> accepted) {
        Map<String, Detection> byEntity = new LinkedHashMap<>();
        for (Detection detection : accepted) {
            byEntity.merge(detection.entityKey(), detection,
                    (a, b) -> a.value().length() >= b.value().length() ? a : b);
        }
        return List.copyOf(byEntity.values());
    }

    /** Descripcion segura para informes: tipo y entidad opaca, jamas el valor. */
    private String describe(Detection detection) {
        return detection.type().label() + " (entidad " + identifier.of(detection.entityKey()) + ")";
    }
}
