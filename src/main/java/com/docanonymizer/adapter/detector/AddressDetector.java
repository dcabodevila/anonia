package com.docanonymizer.adapter.detector;

import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.model.Provenance;
import java.util.regex.Pattern;

/**
 * Direcciones postales ancladas en el tipo de via.
 *
 * <p>La direccion es el tipo mas dificil de los que cubre el prototipo: no tiene digito
 * de control, ni longitud fija, ni vocabulario cerrado. Anclarla en el tipo de via
 * ("Calle", "Avda.", "Plaza") da precision alta a cambio de perder las direcciones
 * escritas sin el, que quedan para la revision humana.
 *
 * <p>La captura se detiene en el numero de portal. Extenderla hasta el codigo postal y
 * la poblacion arrastraria demasiado texto legitimo cuando el patron falla; el CP y la
 * poblacion los cubre {@link PostalCodeDetector} por su cuenta.
 */
public final class AddressDetector extends RegexDetector {

    /**
     * Palabra del nombre de la via: propia, en mayusculas, o una particula.
     * Las particulas van al final y con su limite de palabra, para que "del" no se parta
     * en "de" + "l" y arrastre la mitad del nombre fuera de la deteccion.
     */
    private static final String STREET_WORD =
            "(?:[A-ZÁÉÍÓÚÜÑ][a-záéíóúüñ]+|[A-ZÁÉÍÓÚÜÑ]{2,}|" + SpanishNamePatterns.PARTICLE + ")";

    /**
     * Numero de portal y, detras, piso y puerta: "62 2B", "15 3º", "8 1 izq".
     *
     * <p>Cada bloque adicional debe empezar por digito. Sin esa restriccion, el patron se
     * comia la "E" de "E-15707" que viene despues de la direccion y partia el codigo
     * postal por la mitad.
     */
    private static final String NUMBER =
            "(?:\\s*,?\\s*(?:n[ºo°]\\.?\\s*)?\\d{1,4}[ºª]?"
                    + "(?:\\s*[-,]?\\s*\\d{1,3}[ºª]?[A-Za-z]?)*)?";

    private static final Pattern PATTERN = Pattern.compile(
            SpanishNamePatterns.STREET_TYPE + "\\s+"
                    + STREET_WORD + "(?:\\s+" + STREET_WORD + "){0,5}"
                    + NUMBER);

    public AddressDetector() {
        super("direccion", PATTERN, DetectionType.ADDRESS, Provenance.REGEX, 0.75);
    }
}
