package com.docanonymizer.adapter.detector;

import com.docanonymizer.domain.service.CanonicalForm;
import java.util.List;
import java.util.Set;

/**
 * Recorta un nombre que se ha comido texto de la linea siguiente.
 *
 * <p>Nace de un fallo real sobre una factura. El bloque de direccion venia asi:
 *
 * <pre>
 *   Nombre Apellido
 *   Rua da Muina 62 2B
 * </pre>
 *
 * <p>El patron de nombre admite un salto de linea —porque en un escrito juridico
 * "Juan Perez\nLopez" es un nombre partido por el margen— y se llevo por delante el
 * "Rua" de la linea siguiente. El efecto es peor que no detectar nada: el tipo de via
 * desaparecia dentro de la etiqueta de persona, con lo que el detector de direcciones ya
 * no podia encontrar la calle, y la salida quedaba como "[PERSONA_001] da Muina 62 2B".
 * Una direccion filtrada con aspecto de estar redactada.
 *
 * <p>Dos senales distinguen un nombre partido por el margen de dos campos distintos:
 * la palabra siguiente es un tipo de via o un elemento de direccion, o cambia el estilo
 * de caja ("SANTIAGO DE COMPOSTELA" seguido de "Contactanos").
 */
public final class NameSpanRefiner {

    /** Se descarta el candidato entero si al recortar queda con menos palabras. */
    private static final int MIN_NAME_WORDS = 2;

    private static final Set<String> ADDRESS_WORDS = Set.of(
            // Castellano
            "calle", "avenida", "avda", "av", "plaza", "plza", "pza", "paseo", "camino",
            "carretera", "ctra", "travesia", "ronda", "glorieta", "poligono", "urbanizacion",
            "urb", "bloque", "portal", "escalera", "esc", "piso", "puerta", "apartado",
            // Gallego, catalan y euskera: en una factura real aparecen tal cual.
            // Ojo: "camino" ya esta arriba y es la forma canonica de "camino" gallego,
            // y "placa" lo es de "placa" catalana. Set.of revienta con duplicados.
            "rua", "carrer", "avinguda", "praza", "placa", "passeig", "travessera",
            "kalea", "etorbidea", "errepidea");

    private NameSpanRefiner() {
    }

    /**
     * Devuelve el nuevo final del candidato, o {@code -1} si hay que descartarlo entero
     * porque lo que queda ya no parece un nombre.
     */
    public static int refine(String text, int start, int end) {
        List<int[]> words = wordSpans(text, start, end);
        if (words.isEmpty()) {
            return -1;
        }

        boolean firstIsUpperCase = isAllCaps(text, words.get(0));
        int keptWords = 1;
        int keptEnd = words.get(0)[1];

        for (int i = 1; i < words.size(); i++) {
            int[] word = words.get(i);
            boolean afterLineBreak = containsNewline(text, words.get(i - 1)[1], word[0]);

            if (afterLineBreak && startsNewField(text, word, firstIsUpperCase)) {
                break;
            }
            keptWords++;
            keptEnd = word[1];
        }

        if (keptWords < MIN_NAME_WORDS) {
            return -1;
        }
        return keptEnd == end ? end : keptEnd;
    }

    private static boolean startsNewField(String text, int[] word, boolean firstIsUpperCase) {
        String value = text.substring(word[0], word[1]);
        if (ADDRESS_WORDS.contains(CanonicalForm.forCompare(value))) {
            return true;
        }
        // Cambio de estilo de caja: dos elementos distintos del documento, no un
        // nombre que el margen partio en dos.
        return isAllCaps(text, word) != firstIsUpperCase;
    }

    private static boolean isAllCaps(String text, int[] word) {
        String value = text.substring(word[0], word[1]);
        if (value.length() < 2) {
            return false;
        }
        boolean hasLower = value.chars().anyMatch(Character::isLowerCase);
        return !hasLower;
    }

    private static boolean containsNewline(String text, int from, int to) {
        return text.substring(from, to).indexOf('\n') >= 0;
    }

    private static List<int[]> wordSpans(String text, int start, int end) {
        List<int[]> spans = new java.util.ArrayList<>();
        int i = start;
        while (i < end) {
            while (i < end && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            int wordStart = i;
            while (i < end && !Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i > wordStart) {
                spans.add(new int[] {wordStart, i});
            }
        }
        return spans;
    }
}
