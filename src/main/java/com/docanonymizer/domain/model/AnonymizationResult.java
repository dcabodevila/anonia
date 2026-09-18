package com.docanonymizer.domain.model;

import java.util.List;
import java.util.Map;

/**
 * Salida completa del pipeline.
 *
 * <p>{@code markdown} solo es entregable si {@code verification.passed()} es cierto.
 * El pipeline falla cerrado: ante cualquier hallazgo BLOCKING el CLI no escribe el fichero.
 */
public record AnonymizationResult(
        String markdown,
        List<Detection> accepted,
        Map<String, String> pseudonyms,
        VerificationReport verification,
        String sourceSha256,
        int pageCount) {

    public AnonymizationResult {
        accepted = List.copyOf(accepted);
        pseudonyms = Map.copyOf(pseudonyms);
    }

    public boolean deliverable() {
        return verification.passed();
    }
}
