package com.docanonymizer.domain.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Plegado insensible a mayusculas y acentos que CONSERVA la longitud del texto.
 *
 * <p>Es la diferencia con {@link CanonicalForm}: alli se descartan caracteres para
 * construir una clave de comparacion, aqui no se puede, porque el resultado se usa para
 * buscar posiciones que luego se aplican sobre el texto original. Un plegado con
 * {@code Normalizer.NFD} descompondria "a" en dos code units y desplazaria todos los
 * offsets posteriores, ocultando el trozo equivocado.
 */
public final class TextFolding {

    private TextFolding() {
    }

    /** Minuscula y sin acentos, con correspondencia 1:1 de caracteres. */
    public static String fold(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            out.append(foldChar(text.charAt(i)));
        }
        return out.toString();
    }

    private static char foldChar(char original) {
        char c = Character.toLowerCase(original);
        return switch (c) {
            case 'á', 'à', 'â', 'ä', 'ã', 'å' -> 'a';
            case 'é', 'è', 'ê', 'ë' -> 'e';
            case 'í', 'ì', 'î', 'ï' -> 'i';
            case 'ó', 'ò', 'ô', 'ö', 'õ' -> 'o';
            case 'ú', 'ù', 'û', 'ü' -> 'u';
            case 'ñ' -> 'n';
            case 'ç' -> 'c';
            case 'ý', 'ÿ' -> 'y';
            default -> c;
        };
    }

    /**
     * Todas las apariciones de {@code needle} como palabra completa, ignorando
     * mayusculas y acentos. Los espacios del patron admiten cualquier separador.
     */
    public static List<int[]> findWholeWordOccurrences(String haystack, String needle) {
        List<int[]> hits = new ArrayList<>();
        String foldedHaystack = fold(haystack);
        String foldedNeedle = fold(needle.strip());
        if (foldedNeedle.isEmpty()) {
            return hits;
        }

        if (foldedNeedle.indexOf(' ') >= 0) {
            return findMultiWord(foldedHaystack, foldedNeedle, hits);
        }

        int from = 0;
        while (true) {
            int index = foldedHaystack.indexOf(foldedNeedle, from);
            if (index < 0) {
                return hits;
            }
            int end = index + foldedNeedle.length();
            if (isWholeWord(foldedHaystack, index, end)) {
                hits.add(new int[] {index, end});
            }
            from = index + 1;
        }
    }

    /**
     * Variante multipalabra: el separador del patron puede ser cualquier secuencia de
     * espacios o saltos de linea en el texto, porque el nombre puede venir partido.
     */
    private static List<int[]> findMultiWord(
            String foldedHaystack, String foldedNeedle, List<int[]> hits) {
        String[] words = foldedNeedle.split(" +");
        for (int start = 0; start < foldedHaystack.length(); start++) {
            int cursor = start;
            boolean matched = true;
            for (int w = 0; w < words.length; w++) {
                if (w > 0) {
                    int afterSeparator = skipSeparators(foldedHaystack, cursor);
                    if (afterSeparator == cursor) {
                        matched = false;
                        break;
                    }
                    cursor = afterSeparator;
                }
                if (!foldedHaystack.startsWith(words[w], cursor)) {
                    matched = false;
                    break;
                }
                cursor += words[w].length();
            }
            if (matched && isWholeWord(foldedHaystack, start, cursor)) {
                hits.add(new int[] {start, cursor});
                start = cursor - 1;
            }
        }
        return hits;
    }

    private static int skipSeparators(String text, int from) {
        int i = from;
        while (i < text.length() && (text.charAt(i) == ' ' || text.charAt(i) == '\n'
                || text.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    private static boolean isWholeWord(String text, int start, int end) {
        boolean leftClear = start == 0 || !isWordChar(text.charAt(start - 1));
        boolean rightClear = end >= text.length() || !isWordChar(text.charAt(end));
        return leftClear && rightClear;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }
}
