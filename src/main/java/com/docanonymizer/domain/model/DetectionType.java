package com.docanonymizer.domain.model;

/**
 * Categorias de dato personal que el prototipo sabe detectar.
 * El {@code label} es el prefijo del seudonimo que aparece en el Markdown de salida.
 * La {@code priority} resuelve solapes: gana el valor mas alto.
 */
public enum DetectionType {
    DNI("DNI", 100),
    NIE("NIE", 100),
    IBAN("IBAN", 100),
    EMAIL("EMAIL", 90),
    /** Referencia que identifica al interesado en el sistema del emisor. */
    CUSTOMER_ID("CLIENTE", 85),
    PLATE("MATRICULA", 80),
    PHONE("TELEFONO", 70),
    ADDRESS("DIRECCION", 60),
    POSTAL_CODE("CP", 50),
    ORGANIZATION("ORGANIZACION", 41),
    PERSON("PERSONA", 40),
    /** Codigo asignado manualmente durante la revision; ningun detector lo emite. */
    CODIGO("CODIGO", 40);

    private final String label;
    private final int priority;

    DetectionType(String label, int priority) {
        this.label = label;
        this.priority = priority;
    }

    public String label() {
        return label;
    }

    public int priority() {
        return priority;
    }
}
