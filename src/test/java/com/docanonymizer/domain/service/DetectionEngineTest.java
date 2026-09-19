package com.docanonymizer.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.model.Provenance;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Arbitraje de solapes entre detectores")
class DetectionEngineTest {

    private final DetectionEngine engine = new DetectionEngine(List.of());

    private static Detection at(DetectionType type, int start, int end) {
        return new Detection(type + "-" + start, type, start, end, "x".repeat(end - start),
                type + ":" + start, Provenance.REGEX, 0.8);
    }

    @Test
    @DisplayName("gana el tipo de mayor prioridad cuando dos detecciones se pisan")
    void higherPriorityTypeWins() {
        // Los digitos centrales de un IBAN tienen forma de telefono. Debe ganar el IBAN,
        // que ademas esta validado por mod-97.
        List<Detection> resolved = engine.resolveOverlaps(List.of(
                at(DetectionType.PHONE, 5, 14),
                at(DetectionType.IBAN, 0, 24)));

        assertEquals(1, resolved.size());
        assertEquals(DetectionType.IBAN, resolved.get(0).type());
    }

    @Test
    @DisplayName("a igual prioridad gana la coincidencia mas larga")
    void longerMatchWinsOnEqualPriority() {
        List<Detection> resolved = engine.resolveOverlaps(List.of(
                at(DetectionType.PERSON, 0, 5),
                at(DetectionType.PERSON, 0, 16)));

        assertEquals(1, resolved.size());
        assertEquals(16, resolved.get(0).end());
    }

    @Test
    @DisplayName("conserva las detecciones que no se solapan")
    void keepsDisjointDetections() {
        List<Detection> resolved = engine.resolveOverlaps(List.of(
                at(DetectionType.PERSON, 0, 10),
                at(DetectionType.DNI, 20, 29),
                at(DetectionType.EMAIL, 40, 60)));

        assertEquals(3, resolved.size());
    }

    @Test
    @DisplayName("devuelve el resultado ordenado por posicion en el documento")
    void resultIsSortedByPosition() {
        List<Detection> resolved = engine.resolveOverlaps(List.of(
                at(DetectionType.EMAIL, 40, 60),
                at(DetectionType.PERSON, 0, 10),
                at(DetectionType.DNI, 20, 29)));

        for (int i = 1; i < resolved.size(); i++) {
            assertTrue(resolved.get(i - 1).start() < resolved.get(i).start(),
                    "la sustitucion posterior depende de este orden");
        }
    }
}
