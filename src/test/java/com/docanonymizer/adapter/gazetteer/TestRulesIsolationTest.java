package com.docanonymizer.adapter.gazetteer;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The suite must never depend on the developer's personal ~/.anonimuse/rules.txt. */
class TestRulesIsolationTest {
    @Test void defaultRulesResolveToAnEmptyTestFileOutsideTheUserHome() {
        String override = System.getProperty("doc.anonymizer.rules");
        assertNotNull(override, "the build must point doc.anonymizer.rules at an isolated test file");
        Path rules = Path.of(override).toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("user.home"), ".anonimuse").toAbsolutePath().normalize();
        assertFalse(rules.startsWith(home), "tests must not read the personal rules file");

        UserRules loaded = UserRules.loadDefault();
        assertTrue(loaded.people().isEmpty());
        assertTrue(loaded.organizations().isEmpty());
        assertTrue(loaded.terms().isEmpty());
        assertTrue(loaded.excludedPeople().isEmpty());
        assertTrue(loaded.excludedOrganizations().isEmpty());
        assertTrue(loaded.excludedTerms().isEmpty());
    }
}
