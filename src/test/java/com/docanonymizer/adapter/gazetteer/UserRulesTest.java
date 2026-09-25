package com.docanonymizer.adapter.gazetteer;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserRulesTest {
    @TempDir Path directory;

    @Test void parsesUtf8AndComments() throws Exception {
        Path file = directory.resolve("rules.txt");
        Files.writeString(file, "# local rules\n\nperson: Íñigo\norganization: Banco Santander\nterm: Oposición\n");
        UserRules rules = UserRules.load(file);
        assertEquals(java.util.List.of("Íñigo"), rules.people());
        assertEquals(java.util.List.of("Banco Santander"), rules.organizations());
        assertEquals(java.util.List.of("Oposición"), rules.terms());
    }

    @Test void parsesTypedExclusions() throws Exception {
        Path file = directory.resolve("rules.txt");
        Files.writeString(file, "# local exclusions\nexclude-person: María García Pérez\n"
                + "exclude-organization: Banco Pastor\nexclude-term: Oposición\n");
        UserRules rules = UserRules.load(file);
        assertEquals(java.util.List.of("María García Pérez"), rules.excludedPeople());
        assertEquals(java.util.List.of("Banco Pastor"), rules.excludedOrganizations());
        assertEquals(java.util.List.of("Oposición"), rules.excludedTerms());
    }

    @Test void defaultLocationDoesNotCopyLegacyRules() throws Exception {
        String previousHome = System.getProperty("user.home");
        String previousOverride = System.getProperty("doc.anonymizer.rules");
        Files.createDirectories(directory.resolve(".doc-anonymizer"));
        Files.writeString(directory.resolve(".doc-anonymizer/rules.txt"), "term: Legacy\n");
        try {
            System.setProperty("user.home", directory.toString());
            System.clearProperty("doc.anonymizer.rules");
            assertTrue(UserRules.loadDefault().terms().isEmpty());
            assertTrue(Files.notExists(directory.resolve(".anonimuse/rules.txt")));
            Files.createDirectories(directory.resolve(".anonimuse"));
            Files.writeString(directory.resolve(".anonimuse/rules.txt"), "term: Current\n");
            assertEquals(java.util.List.of("Current"), UserRules.loadDefault().terms());
        } finally {
            if (previousHome == null) System.clearProperty("user.home");
            else System.setProperty("user.home", previousHome);
            if (previousOverride == null) System.clearProperty("doc.anonymizer.rules");
            else System.setProperty("doc.anonymizer.rules", previousOverride);
        }
    }

    @Test void reportsMalformedLineAndConfiguredMissingFile() throws Exception {
        Path file = directory.resolve("rules.txt");
        Files.writeString(file, "# comment\norganization Banco\n");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> UserRules.load(file))
                .getMessage().contains(file + ":2"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> UserRules.load(directory.resolve("missing.txt"))).getMessage().contains("missing.txt"));
    }
}
