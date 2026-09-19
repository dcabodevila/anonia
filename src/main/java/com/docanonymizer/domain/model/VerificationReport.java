package com.docanonymizer.domain.model;

import java.util.List;

/** Resultado de la bateria de verificacion sobre el Markdown final. */
public record VerificationReport(List<Finding> findings) {

    public VerificationReport {
        findings = List.copyOf(findings);
    }

    public boolean passed() {
        return findings.stream().noneMatch(f -> f.severity() == Severity.BLOCKING);
    }

    public List<Finding> blocking() {
        return findings.stream().filter(f -> f.severity() == Severity.BLOCKING).toList();
    }
}
