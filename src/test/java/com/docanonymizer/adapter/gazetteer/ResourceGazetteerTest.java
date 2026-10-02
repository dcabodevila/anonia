package com.docanonymizer.adapter.gazetteer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.docanonymizer.domain.service.CanonicalForm;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResourceGazetteerTest {
    private static final ResourceGazetteer GAZETTEER = new ResourceGazetteer();

    @ParameterizedTest
    @ValueSource(strings = {"Oleksandr", "Ionut", "Emily", "Siobhan", "Wei", "Thiago", "Mohamed", "Grace", "Paz",
            "Rosa", "Victoria", "Mercedes", "Pilar", "Domingo", "Abril", "Mayo"})
    void knowsForeignGivenNames(String name) {
        assertTrue(GAZETTEER.isGivenName(name), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"García", "PÉREZ", "López", "Ruiz", "Kowalski"})
    void knowsResidentSurnames(String surname) {
        assertTrue(GAZETTEER.isSurname(surname), surname);
    }

    @Test
    void surnameResourceHasBroadUniqueCoverage() throws Exception {
        var keys = new HashSet<String>();
        try (var stream = ResourceGazetteerTest.class.getResourceAsStream("/gazetteer/apellidos.txt.gz")) {
            assertNotNull(stream);
            try (var reader = new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(stream), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    assertTrue(keys.add(CanonicalForm.forCompare(line)), "duplicado: " + line);
                    assertTrue(GAZETTEER.isSurname(line), line);
                }
            }
        }
        assertTrue(keys.size() > 10000, "size=" + keys.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"de", "la", "Banco", "Calle", "Avenida", "Juzgado", "Hospital", "Comunidad"})
    void surnameExclusionsAreAvailableAtRuntime(String word) {
        assertFalse(GAZETTEER.isSurname(word), word);
        assertTrue(GAZETTEER.isExcludedWord(word), word);
    }

    @Test
    void packagedExclusionsMatchBuildInput() throws Exception {
        try (var stream = ResourceGazetteerTest.class.getResourceAsStream("/gazetteer/exclusions.txt")) {
            assertNotNull(stream);
            assertArrayEquals(java.nio.file.Files.readAllBytes(
                    java.nio.file.Path.of("scripts/gazetteer/exclusions.txt")), stream.readAllBytes());
        }
    }

    @Test
    void hasBroadCoverage() {
        assertTrue(GAZETTEER.size() > 100000, "size=" + GAZETTEER.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"de", "la", "Juzgado", "Tribunal", "Real", "Decreto",
            "Sala", "General", "Caja", "Ley", "Firma", "Código", "Enero", "Lunes"})
    void excludesNonPersonWords(String word) {
        assertFalse(GAZETTEER.isGivenName(word), word);
    }

    @Test
    void preservesLegacyNamesAndCanonicalComparison() {
        assertTrue(GAZETTEER.isGivenName("Mª"));
        assertTrue(GAZETTEER.isGivenName("JOSÉ"));
        assertTrue(GAZETTEER.isGivenName("Pilar"));
        assertTrue(GAZETTEER.isGivenName("Mercedes"));
    }

    @Test
    void compressedEntriesAreUniqueCanonicalForms() throws Exception {
        var keys = new HashSet<String>();
        try (var stream = ResourceGazetteerTest.class.getResourceAsStream("/gazetteer/nombres-intl.txt.gz")) {
            assertNotNull(stream);
            try (var reader = new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(stream), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    assertTrue(keys.add(CanonicalForm.forCompare(line)), "duplicado: " + line);
                }
            }
        }
        assertTrue(keys.size() > 100000);
    }

    @Test
    void customPlainResourceKeepsItsOriginalContract() {
        var custom = new ResourceGazetteer("/gazetteer/nombres-es.txt");
        assertEquals(132, custom.size());
        assertFalse(custom.isSurname("García"));
        assertFalse(custom.isExcludedWord("Banco"));
    }

    @Test
    void reportsColdLoadCost() {
        Runtime runtime = Runtime.getRuntime();
        System.gc();
        long before = runtime.totalMemory() - runtime.freeMemory();
        long start = System.nanoTime();
        ResourceGazetteer loaded = new ResourceGazetteer("/gazetteer/nombres-intl.txt.gz");
        long elapsed = System.nanoTime() - start;
        long allocated = runtime.totalMemory() - runtime.freeMemory() - before;
        System.gc();
        long retained = runtime.totalMemory() - runtime.freeMemory() - before;
        System.out.printf("Gazetteer: %,d international / %,d default names, %.1f ms, "
                        + "heap delta %,d bytes, retained estimate %,d bytes (GC-sensitive)%n",
                loaded.size(), GAZETTEER.size(), elapsed / 1_000_000.0, allocated, retained);
        assertTrue(loaded.size() > 100000);
    }
}
