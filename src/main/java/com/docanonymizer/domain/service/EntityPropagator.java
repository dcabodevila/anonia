package com.docanonymizer.domain.service;

import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.model.Provenance;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

    public List<Detection> propagate(String normalizedText, List<Detection> seeds) {
        List<Detection> extra = new ArrayList<>();
        for (Detection seed : seeds) {
            if (seed.type() != DetectionType.PERSON) {
                continue;
            }
            for (String variant : variantsOf(seed.value())) {
                for (int[] span : TextFolding.findWholeWordOccurrences(normalizedText, variant)) {
                    extra.add(new Detection(
                            "prop-" + sequence.incrementAndGet(),
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
