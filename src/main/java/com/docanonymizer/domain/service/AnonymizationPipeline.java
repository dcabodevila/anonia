package com.docanonymizer.domain.service;

import com.docanonymizer.domain.model.AnonymizationResult;
import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.ExtractedDocument;
import com.docanonymizer.domain.model.VerificationReport;
import com.docanonymizer.domain.port.ReviewPort;
import com.docanonymizer.domain.port.TextExtractorPort;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Caso de uso completo: PDF nativo en, Markdown desidentificado fuera.
 *
 * <p>Orquesta el orden del pipeline, que no es negociable:
 *
 * <ol>
 *   <li>extraer por paginas,
 *   <li>guarda de cordura (falla cerrado si el texto no es fiable),
 *   <li>quitar cabeceras y pies repetidos, con las paginas todavia separadas,
 *   <li>normalizar, que fija el sistema de coordenadas unico,
 *   <li>detectar y arbitrar solapes,
 *   <li>propagar entidades, volver a arbitrar y unificar personas,
 *   <li>revision humana,
 *   <li>asignar seudonimos, sustituir y renderizar,
 *   <li>verificar el artefacto final.
 * </ol>
 *
 * <p>El paso 3 va antes del 4 porque necesita las paginas sin concatenar; el 4 antes del
 * 5 porque sin normalizar los patrones no ven un NIF partido por un guion de linea; y el
 * 9 sobre el artefacto exacto que se escribe, no sobre un intermedio.
 *
 * <p>Los pasos 1-6 ({@link #analyze}) y 8-9 ({@link #complete}) se exponen por separado
 * porque una interfaz de revision real necesita ensenar los candidatos y esperar a que
 * una persona decida. {@link #run} encadena las dos mitades con el {@link ReviewPort}
 * configurado, que es lo que usa la linea de comandos.
 */
public final class AnonymizationPipeline {

    public static final String TOOL_VERSION = "0.3.0";

    /** Estado intermedio entre la deteccion y la decision humana. */
    public record Analysis(
            String normalizedText,
            List<Detection> candidates,
            String sourceSha256,
            int pageCount) {

        public Analysis {
            candidates = List.copyOf(candidates);
        }
    }

    private final TextExtractorPort extractor;
    private final DetectionEngine detectionEngine;
    private final ReviewPort review;
    private final Clock clock;
    private final ExcludedEntities exclusions;

    private final TextSanityGuard sanityGuard = new TextSanityGuard();
    private final HeaderFooterStripper headerFooterStripper = new HeaderFooterStripper();
    private final TextNormalizer normalizer = new TextNormalizer();
    private final EntityPropagator propagator = new EntityPropagator();
    private final PersonEntityResolver entityResolver = new PersonEntityResolver();
    private final PseudonymAssigner pseudonymAssigner = new PseudonymAssigner();
    private final TextAnonymizer anonymizer = new TextAnonymizer();
    private final MarkdownRenderer renderer = new MarkdownRenderer();
    private final RedactionVerifier verifier;

    public AnonymizationPipeline(
            TextExtractorPort extractor,
            DetectionEngine detectionEngine,
            ReviewPort review,
            RunScopedIdentifier identifier,
            Clock clock) {
        this(extractor, detectionEngine, review, identifier, clock, ExcludedEntities.empty());
    }

    public AnonymizationPipeline(
            TextExtractorPort extractor,
            DetectionEngine detectionEngine,
            ReviewPort review,
            RunScopedIdentifier identifier,
            Clock clock,
            ExcludedEntities exclusions) {
        this.extractor = extractor;
        this.exclusions = exclusions;
        this.detectionEngine = detectionEngine;
        this.review = review;
        this.clock = clock;
        this.verifier = new RedactionVerifier(identifier);
    }

    /** Pipeline completo con el puerto de revision configurado. */
    public AnonymizationResult run(Path pdf) throws IOException {
        Analysis analysis = analyze(pdf);
        List<Detection> accepted =
                review.review(analysis.normalizedText(), analysis.candidates());
        return complete(analysis, accepted);
    }

    /** Pasos 1-6: del PDF a la lista de candidatos, sin decidir nada todavia. */
    public Analysis analyze(Path pdf) throws IOException {
        ExtractedDocument extracted = extractor.extract(pdf);

        TextSanityGuard.Result sanity = sanityGuard.check(extracted.rawText());
        if (!sanity.usable()) {
            throw new DocumentNotProcessableException("Extraccion no fiable: " + sanity.reason());
        }

        ExtractedDocument cleaned = headerFooterStripper.strip(extracted).document();
        String normalized = normalizer.normalize(cleaned.rawText());

        List<Detection> seeds = detectionEngine.detect(normalized);
        List<Detection> withPropagated = mergePropagated(normalized, seeds);
        List<Detection> unified = entityResolver.resolve(withPropagated);

        return new Analysis(
                normalized, exclusions.filter(unified), extracted.sourceSha256(), extracted.pageCount());
    }

    /** Server-derived review decisions, separate from the non-overlapping substitution projection. */
    public record ReviewedSelection(List<Detection> effective, java.util.Set<String> rejectedIds) {
        public ReviewedSelection {
            effective = List.copyOf(effective);
            rejectedIds = java.util.Set.copyOf(rejectedIds);
        }
    }

    /** Pasos 8-9: sustituir, renderizar y pasar la puerta de salida. */
    public AnonymizationResult complete(Analysis analysis, List<Detection> accepted) {
        return complete(analysis, new ReviewedSelection(accepted, java.util.Set.of()));
    }

    public AnonymizationResult complete(Analysis analysis, ReviewedSelection reviewed) {
        List<Detection> accepted = reviewed.effective();
        Map<String, String> pseudonyms = pseudonymAssigner.assign(accepted);
        String substituted =
                anonymizer.apply(analysis.normalizedText(), accepted, pseudonyms);

        MarkdownRenderer.Metadata metadata = new MarkdownRenderer.Metadata(
                analysis.sourceSha256(),
                analysis.pageCount(),
                countByType(accepted),
                TOOL_VERSION,
                Instant.now(clock));
        String markdown = renderer.render(substituted, metadata);

        VerificationReport report = verifier.verify(markdown, accepted, pseudonyms, analysis.candidates(), reviewed.rejectedIds());

        return new AnonymizationResult(
                markdown, accepted, pseudonyms, report,
                analysis.sourceSha256(), analysis.pageCount());
    }

    /**
     * Vuelve a arbitrar solapes tras propagar. Es necesario porque una variante propagada
     * ("Garcia Perez") puede caer justo encima de una deteccion original mas larga, o
     * dentro de una direccion ya detectada, y solo puede sobrevivir una de las dos.
     */
    private List<Detection> mergePropagated(String normalized, List<Detection> seeds) {
        List<Detection> all = new ArrayList<>(seeds);
        all.addAll(propagator.propagate(normalized, seeds));
        return detectionEngine.resolveOverlaps(all);
    }

    private Map<String, Long> countByType(List<Detection> accepted) {
        return accepted.stream()
                .collect(Collectors.groupingBy(d -> d.type().label(), Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }
}
