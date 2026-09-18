package com.docanonymizer.domain.model;

/**
 * Hallazgo de un control de verificacion.
 *
 * <p>{@code detail} esta pensado para ser seguro en informes: describe QUE control
 * fallo y sobre que tipo de entidad, nunca el valor filtrado.
 */
public record Finding(String control, Severity severity, String detail) {

    public static Finding blocking(String control, String detail) {
        return new Finding(control, Severity.BLOCKING, detail);
    }

    public static Finding warning(String control, String detail) {
        return new Finding(control, Severity.WARNING, detail);
    }

    public static Finding info(String control, String detail) {
        return new Finding(control, Severity.INFO, detail);
    }
}
