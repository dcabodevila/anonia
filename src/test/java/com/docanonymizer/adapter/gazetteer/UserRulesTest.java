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

    @Test void reportsMalformedLineAndConfiguredMissingFile() throws Exception {
        Path file = directory.resolve("rules.txt");
        Files.writeString(file, "# comment\norganization Banco\n");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> UserRules.load(file))
                .getMessage().contains(file + ":2"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> UserRules.load(directory.resolve("missing.txt"))).getMessage().contains("missing.txt"));
    }
}
