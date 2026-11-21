package com.docanonymizer.domain.service;

import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.port.DetectorPort;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ejecuta todos los detectores y resuelve los solapes.
 *
 * <p>Los detectores son deliberadamente ingenuos e independientes: cada uno propone lo
 * suyo sin mirar a los demas. Los conflictos se arbitran aqui, en un unico sitio, con
 * una regla explicita. Eso evita el acoplamiento en cadena en el que cada detector
 * tiene que conocer a los otros.
 *
 * <p>Regla de arbitraje, en orden: gana la deteccion de mayor prioridad de tipo; a
 * igualdad, la mas larga; a igualdad, la que empieza antes. Asi un IBAN validado por
 * mod-97 se impone sobre el telefono que veria sus digitos centrales.
 */
public final class DetectionEngine {

    private final List<DetectorPort> detectors;

    public DetectionEngine(List<DetectorPort> detectors) {
        this.detectors = List.copyOf(detectors);
    }

    public List<Detection> detect(String normalizedText) {
        List<Detection> all = new ArrayList<>();
        for (DetectorPort detector : detectors) {
            all.addAll(detector.detect(normalizedText));
        }
        return resolveOverlaps(all);
    }

    /**
     * Selecciona un subconjunto sin solapes. Enfoque voraz sobre la lista ordenada por
     * calidad decreciente: se acepta un candidato solo si no pisa a ninguno ya aceptado.
     */
    List<Detection> resolveOverlaps(List<Detection> candidates) {
        List<Detection> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator
                .comparingInt((Detection d) -> d.type().priority()).reversed()
                .thenComparing(Comparator.comparingInt(Detection::length).reversed())
                .thenComparingInt(Detection::start));

        // Accepted spans never overlap each other, so ordered by start they are also ordered by
        // end: if any accepted span overlaps a candidate, the one with the greatest start below
        // the candidate's end does. That makes each check a tree lookup instead of a full scan.
        List<Detection> accepted = new ArrayList<>();
        java.util.TreeMap<Integer, Detection> acceptedByStart = new java.util.TreeMap<>();
        List<Detection> acceptedEmpty = new ArrayList<>();
        for (Detection candidate : ranked) {
            var previous = acceptedByStart.lowerEntry(candidate.end());
            boolean collides = previous != null && candidate.overlaps(previous.getValue())
                    || acceptedEmpty.stream().anyMatch(candidate::overlaps);
            if (!collides) {
                accepted.add(candidate);
                // Zero-length spans can share a start with a real one: keep them apart.
                if (candidate.length() == 0) acceptedEmpty.add(candidate);
                else acceptedByStart.put(candidate.start(), candidate);
            }
        }
        accepted.sort(Comparator.comparingInt(Detection::start));
        return List.copyOf(accepted);
    }
}
