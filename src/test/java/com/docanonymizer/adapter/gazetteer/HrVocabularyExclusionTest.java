package com.docanonymizer.adapter.gazetteer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docanonymizer.adapter.detector.GazetteerPersonDetector;
import com.docanonymizer.domain.model.Detection;
import java.util.List;
import org.junit.jupiter.api.Test;

/** HR header/job-title vocabulary never justifies a PERSON; real names around it still do. */
class HrVocabularyExclusionTest {

    private final GazetteerPersonDetector detector = new GazetteerPersonDetector(new ResourceGazetteer());

    private List<String> persons(String text) {
        return detector.detect(text).stream().map(Detection::value).toList();
    }

    @Test void headerAndJobTitleCellsAreNotPersons() {
        // Root causes: "ID" is a listed given name, "Senior" a listed surname.
        for (String cell : List.of("ID Empleado", "Empleado", "Técnico", "Técnico Senior", "Administrativo",
                "Responsable", "Analista", "Operario", "Coordinador", "Auxiliar", "Jefe de Equipo", "Consultor")) {
            assertEquals(List.of(), persons(cell), cell);
        }
        assertEquals(List.of(), persons("ID Empleado\tNombre\tTécnico Senior\tAdministrativo"));
    }

    @Test void vocabularyIsExcludedWords() {
        ResourceGazetteer gazetteer = new ResourceGazetteer();
        for (String word : List.of("ID", "Empleado", "Técnico", "Senior", "Jefe", "Equipo")) {
            assertTrue(gazetteer.isExcludedWord(word), word);
        }
        assertFalse(gazetteer.isExcludedWord("María"));
    }

    @Test void realNamesStayDetectedEvenWithAVocabularyWordInside() {
        assertEquals(List.of("María López Ruiz"), persons("María López Ruiz"));
        assertEquals(List.of("Carmen Sánchez Gómez"), persons("Carmen Sánchez Gómez\tTécnico Senior"));
        assertEquals(List.of("Pablo Responsable Martín"), persons("Pablo Responsable Martín"));
        assertEquals(List.of("Ana Senior"), persons("Ana Senior"));
    }
}
