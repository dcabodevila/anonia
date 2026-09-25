package com.docanonymizer.adapter.detector;

import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.model.Provenance;
import com.docanonymizer.domain.service.IdentifierValidators;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Detectores deterministas: los tipos que se resuelven con patron y, cuando existe,
 * digito de control. Cubren la mayor parte de los identificadores directos sin
 * necesidad de ningun modelo estadistico.
 */
public final class DeterministicDetectors {

    private DeterministicDetectors() {
    }

    public static List<RegexDetector> all() {
        return List.of(
                new DniDetector(),
                new NieDetector(),
                new IbanDetector(),
                new EmailDetector(),
                new PhoneDetector(),
                new PlateDetector(),
                new CodeDetector());
    }

    /** Maximal runs of at least five ASCII digits, including inside alphanumeric text. */
    public static final class CodeDetector extends RegexDetector {
        private static final Pattern PATTERN = Pattern.compile("(?<![0-9])[0-9]{5,}(?![0-9])");

        public CodeDetector() {
            super("codigo", PATTERN, DetectionType.CODIGO, Provenance.REGEX, 0.80);
        }
    }

    /** DNI: 8 digitos y letra, con separador opcional. La letra se valida. */
    public static final class DniDetector extends RegexDetector {
        private static final Pattern PATTERN =
                Pattern.compile("(?<![0-9A-Za-z])\\d{8}\\s?-?\\s?[A-Za-z](?![0-9A-Za-z])");

        public DniDetector() {
            super("dni", PATTERN, DetectionType.DNI, Provenance.CHECKSUM_VALIDATED, 0.99);
        }

        @Override
        protected boolean accepts(String value) {
            return IdentifierValidators.isValidDni(value);
        }
    }

    /** NIE: X, Y o Z, 7 digitos y letra de control. */
    public static final class NieDetector extends RegexDetector {
        private static final Pattern PATTERN =
                Pattern.compile("(?<![0-9A-Za-z])[XYZxyz]\\s?-?\\s?\\d{7}\\s?-?\\s?[A-Za-z]"
                        + "(?![0-9A-Za-z])");

        public NieDetector() {
            super("nie", PATTERN, DetectionType.NIE, Provenance.CHECKSUM_VALIDATED, 0.99);
        }

        @Override
        protected boolean accepts(String value) {
            return IdentifierValidators.isValidNie(value);
        }
    }

    /** IBAN espanol o extranjero, con o sin espacios, validado por mod-97. */
    public static final class IbanDetector extends RegexDetector {
        private static final Pattern PATTERN =
                Pattern.compile("(?<![0-9A-Za-z])[A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]{4}){2,7}"
                        + "\\s?[A-Z0-9]{0,4}(?![0-9A-Za-z])");

        public IbanDetector() {
            super("iban", PATTERN, DetectionType.IBAN, Provenance.CHECKSUM_VALIDATED, 0.99);
        }

        @Override
        protected boolean accepts(String value) {
            return IdentifierValidators.isValidIban(value);
        }
    }

    /**
     * Correo electronico. Patron conservador, no RFC 5322 completo.
     *
     * <p>El dominio se exige como secuencia de etiquetas separadas por punto y el TLD
     * como letras finales. Asi la direccion sobrevive al punto que cierra la frase:
     * "...correo juan.perez@example.com." no debe perder la deteccion por ese punto.
     */
    public static final class EmailDetector extends RegexDetector {
        private static final Pattern PATTERN = Pattern.compile(
                "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,}"
                        + "(?![A-Za-z0-9-])");

        public EmailDetector() {
            super("email", PATTERN, DetectionType.EMAIL, Provenance.REGEX, 0.95);
        }
    }

    /**
     * Telefono espanol: 9 digitos que empiezan por 6, 7, 8 o 9, con prefijo +34 opcional.
     *
     * <p>Los separadores se admiten en cualquier posicion porque en la practica conviven
     * "612345678", "612 345 678" y "612 34 56 78". Fijar una unica agrupacion deja fuera
     * las demas en silencio, que es como se pierden detecciones sin enterarse.
     *
     * <p>Sin digito de control que validar, la confianza es menor que la de un DNI y la
     * revision humana pesa mas.
     */
    public static final class PhoneDetector extends RegexDetector {
        private static final Pattern PATTERN = Pattern.compile(
                "(?<!\\d)(?:(?:\\+|00)[ \\t]?34[\\s.-]?)?[6789](?:[ \\t.-]?\\d){8}(?!\\d)");

        public PhoneDetector() {
            super("telefono", PATTERN, DetectionType.PHONE, Provenance.REGEX, 0.80);
        }
    }

    /**
     * Matricula espanola moderna: 4 digitos y 3 consonantes. El alfabeto excluye vocales,
     * y tambien Q y N con virgulilla, para no confundirse con siglas corrientes.
     */
    public static final class PlateDetector extends RegexDetector {
        private static final Pattern PATTERN = Pattern.compile(
                "(?<![0-9A-Za-z])\\d{4}\\s?-?\\s?[BCDFGHJKLMNPRSTVWXYZ]{3}(?![0-9A-Za-z])");

        public PlateDetector() {
            super("matricula", PATTERN, DetectionType.PLATE, Provenance.REGEX, 0.85);
        }
    }

}
