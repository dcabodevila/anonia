package com.docanonymizer.domain.service;

import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.model.Provenance;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Extiende cada persona detectada a todas sus demas apariciones en el documento.
 *
 * <p>Este es el mecanismo principal de recall del prototipo. Los detectores encuentran
 * a "Maria Garcia Perez" donde hay una pista fuerte ("D.na Maria Garcia Perez, con DNI...")
 * pero el resto del documento la llama "la Sra. Garcia Perez" o simplemente "Garcia".
 * Sin propagacion, el 90% de las menciones se escapa.
 *
 * <p>Todas las variantes heredan el {@code entityKey} de la deteccion original, que es
 * lo que hace que acaben bajo el mismo {@code [PERSONA_001]} en la salida.
 */
public final class EntityPropagator {

    private final AtomicInteger sequence = new AtomicInteger();

    /**
     * Bounded to one propagated span per text range: in an HR roster every shared given name or
     * surname used to be emitted once per person (1.5 million spans for 1000 employees). Among
     * identical PERSON ranges only the first can ever win {@code DetectionEngine} arbitration,
     * so later duplicates are dropped; their ids are still consumed so surviving ids are stable.
     * The text is folded once and each distinct variant searched once.
     */
    public List<Detection> propagate(String normalizedText, List<Detection> seeds) {
        List<Detection> extra = new ArrayList<>();
        TextFolding.Folded folded = TextFolding.Folded.of(normalizedText);
        Map<String, List<int[]>> occurrences = new HashMap<>();
        Set<Long> emitted = new HashSet<>();
        for (Detection seed : seeds) {
            if (seed.type() != DetectionType.PERSON) {
                continue;
            }
            for (String variant : variantsOf(seed.value())) {
                for (int[] span : occurrences.computeIfAbsent(variant,
                        v -> NamePolicy.findOccurrencesWithinCell(folded, v))) {
                    int id = sequence.incrementAndGet();
                    if (!emitted.add(((long) span[0] << 32) | span[1])) {
                        continue;
                    }
                    extra.add(new Detection(
                            "prop-" + id,
                            DetectionType.PERSON,
                            span[0],
                            span[1],
                            normalizedText.substring(span[0], span[1]),
                            seed.entityKey(),
                            Provenance.PROPAGATED,
                            0.70));
                }
            }
        }
        return extra;
    }

    /**
     * Variantes buscables de un nombre, de la mas especifica a la mas suelta.
     * El orden importa poco aqui porque {@code DetectionEngine} arbitra los solapes
     * quedandose con la coincidencia mas larga.
     */
    private Set<String> variantsOf(String fullName) {
        Set<String> variants = new LinkedHashSet<>();
        variants.add(fullName.strip());
        variants.addAll(NamePolicy.surnameGroup(fullName));
        variants.addAll(NamePolicy.significantTokens(fullName));
        return variants;
    }
}
