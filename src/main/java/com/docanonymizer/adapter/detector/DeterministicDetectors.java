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
                new PassportDetector(),
                new IbanDetector(),
                new EmailDetector(),
                new PhoneDetector(),
                new PlateDetector(),
                new DocumentShapedCodeDetector(),
                new PassportShapedCodeDetector(),
                new CodeDetector());
    }

    /** Maximal runs of at least five ASCII digits, including inside alphanumeric text. */
    public static final class CodeDetector extends RegexDetector {
        private static final Pattern PATTERN = Pattern.compile("(?<![0-9])[0-9]{5,}(?![0-9])");

        public CodeDetector() {
            super("codigo", PATTERN, DetectionType.CODIGO, Provenance.REGEX, 0.80);
        }
    }

    /** Letra final opcional; un decimal o un grupo mas de cifras cierra la puerta al valor. */
    private static final String LETTER_SUFFIX = "(?:\\s?-?\\s?[A-Za-z])?(?![0-9A-Za-z]|[.,]\\d)";

    /**
     * Se queda con la letra final si va pegada o tras guion. Separada solo por espacio,
     * una minuscula que no cuadra con el control es casi siempre la palabra siguiente:
     * "expedientes 12345678 y 87654321".
     */
    private static String trimLetter(String matched, boolean checksumValid) {
        char last = matched.charAt(matched.length() - 1);
        if (!Character.isLetter(last)) {
            return matched;
        }
        int bodyEnd = matched.length() - 1;
        while (bodyEnd > 0 && !Character.isDigit(matched.charAt(bodyEnd - 1))) {
            bodyEnd--;
        }
        String separator = matched.substring(bodyEnd, matched.length() - 1);
        boolean spaceOnly = !separator.isEmpty() && separator.isBlank();
        if (spaceOnly && Character.isLowerCase(last) && !checksumValid) {
            return matched.substring(0, bodyEnd);
        }
        return matched;
    }

    private static final String DNI_BODY = "(?:\\d{8}|\\d{2}\\.\\d{3}\\.\\d{3})";
    private static final String NIE_BODY =
            "(?:[XYZ][\\s.-]?|[xyz]-?)(?:\\d{7}|\\d\\.\\d{3}\\.\\d{3})";

    private static boolean validDocument(String value) {
        return IdentifierValidators.isValidDni(value) || IdentifierValidators.isValidNie(value);
    }

    /**
     * DNI: 8 digitos, seguidos o agrupados con puntos ("35.933.864K"), y una letra de
     * control que cuadra. Sin letra, o con una que no cuadra, es un CODIGO.
     */
    public static final class DniDetector extends RegexDetector {
        private static final Pattern PATTERN =
                Pattern.compile("(?<![0-9A-Za-z])" + DNI_BODY + LETTER_SUFFIX);

        public DniDetector() {
            super("dni", PATTERN, DetectionType.DNI, Provenance.CHECKSUM_VALIDATED, 0.99);
        }

        @Override
        protected String trim(String matched) {
            return trimLetter(matched, IdentifierValidators.isValidDni(matched));
        }

        @Override
        protected boolean accepts(String value) {
            return IdentifierValidators.isValidDni(value);
        }
    }

    /** NIE: X, Y o Z, 7 digitos seguidos o agrupados con puntos, y letra de control valida. */
    public static final class NieDetector extends RegexDetector {
        private static final Pattern PATTERN =
                Pattern.compile("(?<![0-9A-Za-z])" + NIE_BODY + LETTER_SUFFIX);

        public NieDetector() {
            super("nie", PATTERN, DetectionType.NIE, Provenance.CHECKSUM_VALIDATED, 0.99);
        }

        @Override
        protected String trim(String matched) {
            return trimLetter(matched, IdentifierValidators.isValidNie(matched));
        }

        @Override
        protected boolean accepts(String value) {
            return IdentifierValidators.isValidNie(value);
        }
    }

    /**
     * Forma de DNI o NIE sin letra o con letra que no cuadra: un CODIGO que abarca el
     * numero entero, puntos y letra incluidos, para no dejar fragmentos a la vista.
     */
    public static final class DocumentShapedCodeDetector extends RegexDetector {
        private static final Pattern PATTERN = Pattern.compile(
                "(?<![0-9A-Za-z])(?:" + DNI_BODY + "|" + NIE_BODY + ")" + LETTER_SUFFIX);

        public DocumentShapedCodeDetector() {
            super("codigo-documento", PATTERN, DetectionType.CODIGO, Provenance.REGEX, 0.80);
        }

        @Override
        protected String trim(String matched) {
            return trimLetter(matched, validDocument(matched));
        }

        @Override
        protected boolean accepts(String value) {
            return !validDocument(value);
        }
    }

    /** Valor que sigue a "pasaporte" o "passport", con "n.º", "numero", "number"... opcionales. */
    private static final Pattern PASSPORT_CONTEXT = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:pasaporte|passport)"
                    + "(?:\\s+(?:n\\.?\\s?[º°o]\\.?|n[uú]m(?:ero|\\.)?|number|nr\\.?))?"
                    + "\\s*[:#]?\\s*([A-Za-z0-9]{6,9})(?![\\p{L}\\p{N}])");
    private static final Pattern SPANISH_PASSPORT = Pattern.compile("[A-Z]{3}\\d{6}");

    /**
     * Pasaporte espanol: tres letras mayusculas y seis cifras ("PAA123456"), solo tras
     * la palabra pasaporte. No tiene digito de control; el contexto es la garantia.
     */
    public static final class PassportDetector extends RegexDetector {
        public PassportDetector() {
            super("pasaporte", PASSPORT_CONTEXT, DetectionType.PASSPORT, Provenance.REGEX, 0.90);
        }

        @Override
        protected int captureGroup() {
            return 1;
        }

        @Override
        protected boolean accepts(String value) {
            return SPANISH_PASSPORT.matcher(value).matches();
        }
    }

    /** Cualquier otro numero tras la palabra pasaporte, con al menos una cifra, es un CODIGO. */
    public static final class PassportShapedCodeDetector extends RegexDetector {
        public PassportShapedCodeDetector() {
            super("codigo-pasaporte", PASSPORT_CONTEXT, DetectionType.CODIGO, Provenance.REGEX, 0.80);
        }

        @Override
        protected int captureGroup() {
            return 1;
        }

        @Override
        protected boolean accepts(String value) {
            return value.chars().anyMatch(Character::isDigit) && !SPANISH_PASSPORT.matcher(value).matches();
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
