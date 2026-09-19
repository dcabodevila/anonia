package com.docanonymizer.domain.model;

import java.util.Objects;

/**
 * Un candidato a ocultar, situado sobre el texto YA NORMALIZADO.
 *
 * <p>{@code value} contiene datos personales en claro: no debe salir a logs,
 * informes de auditoria ni mensajes de error.
 *
 * <p>{@code entityKey} agrupa todas las apariciones de la misma persona o del
 * mismo identificador para que compartan un unico seudonimo. Es lo que hace que
 * "Maria Garcia Perez" y un "Garcia Perez" suelto mas abajo acaben ambos como
 * {@code [PERSONA_001]}.
 */
public record Detection(
        String id,
        DetectionType type,
        int start,
        int end,
        String value,
        String entityKey,
        Provenance provenance,
        double confidence) {

    public Detection {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(entityKey, "entityKey");
        Objects.requireNonNull(provenance, "provenance");
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("Rango invalido: [" + start + ", " + end + ")");
        }
    }

    public int length() {
        return end - start;
    }

    public boolean overlaps(Detection other) {
        return start < other.end && other.start < end;
    }
}
