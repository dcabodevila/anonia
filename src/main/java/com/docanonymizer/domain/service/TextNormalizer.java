package com.docanonymizer.domain.service;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Deja el texto en un estado sobre el que las expresiones regulares tienen sentido.
 *
 * <p>Sin este paso la deteccion falla en silencio, que es el peor modo de fallo posible:
 * un NIF partido por un guion de fin de linea o un nombre con letter-spacing no lo
 * captura ningun patron, y el pipeline exporta creyendo que no habia nada que ocultar.
 *
 * <p>El texto normalizado es el unico sistema de coordenadas del prototipo. Como la
 * salida es Markdown y no un PDF, no hace falta mapear offsets de vuelta al origen:
 * esa es justamente la simplificacion que compro el cambio de alcance.
 */
public final class TextNormalizer {

    // Caracteres invisibles o tipograficos que rompen los patrones sin verse.
    private static final char SOFT_HYPHEN = 0x00AD;
    private static final char NO_BREAK_SPACE = 0x00A0;
    private static final char ZERO_WIDTH_SPACE = 0x200B;
    private static final char ZERO_WIDTH_JOINER = 0x200D;
    private static final char BOM = 0xFEFF;
    private static final char FIGURE_SPACE = 0x2007;
    private static final char NARROW_NO_BREAK_SPACE = 0x202F;
    private static final char EN_QUAD = 0x2000;
    private static final char HAIR_SPACE = 0x200A;
    private static final char HYPHEN_2010 = 0x2010;
    private static final char HORIZONTAL_BAR_2015 = 0x2015;
    private static final char MINUS_SIGN = 0x2212;

    /**
     * Une palabra partida al final de linea, pero solo minuscula-minuscula.
     * La restriccion protege guiones legitimos: "Vitoria-Gasteiz" o el apellido
     * compuesto "Garcia-Lopez" llevan mayuscula detras del guion y no se tocan.
     */
    private static final Pattern LINE_BREAK_HYPHEN =
            Pattern.compile("(\\p{Ll})-\\n(\\p{Ll})");

    /**
     * Minimo de letras sueltas y proporcion sobre la linea para considerarla espaciada.
     * Se decide por LINEA, no por racha suelta: el fenomeno real es un titulo entero
     * escrito "A C T A   D E   M A N I F E S T A C I O N E S", y arreglar solo las rachas
     * de 4+ letras dejaria el "D E" del medio sin juntar.
     */
    private static final int MIN_SPACED_LETTERS = 4;
    private static final double MIN_SPACED_RATIO = 0.6;

    private static final Pattern WORD_SEPARATOR = Pattern.compile(" {2,}");
    private static final Pattern ANY_WHITESPACE = Pattern.compile("\\s+");

    private static final Pattern TRAILING_SPACES = Pattern.compile("[ \\t]+\\n");
    private static final Pattern HORIZONTAL_RUNS = Pattern.compile("[ \\t]{2,}");
    private static final Pattern EXTRA_BLANK_LINES = Pattern.compile("\\n{3,}");

    public String normalize(String raw) {
        String text = Normalizer.normalize(raw, Normalizer.Form.NFC);
        text = unifyLineEndings(text);
        text = stripInvisibles(text);
        text = unifyDashes(text);
        text = LINE_BREAK_HYPHEN.matcher(text).replaceAll("$1$2");
        text = collapseLetterSpacing(text);
        text = TRAILING_SPACES.matcher(text).replaceAll("\n");
        text = HORIZONTAL_RUNS.matcher(text).replaceAll(" ");
        text = EXTRA_BLANK_LINES.matcher(text).replaceAll("\n\n");
        return text.strip();
    }

    private String unifyLineEndings(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * Quita caracteres que no se ven pero rompen los patrones, y convierte a espacio
     * normal las variantes tipograficas de espacio que los PDF usan a menudo.
     */
    private String stripInvisibles(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isZeroWidth(c)) {
                continue;
            }
            out.append(isExoticSpace(c) ? ' ' : c);
        }
        return out.toString();
    }

    private boolean isZeroWidth(char c) {
        return c == SOFT_HYPHEN
                || c == BOM
                || (c >= ZERO_WIDTH_SPACE && c <= ZERO_WIDTH_JOINER);
    }

    private boolean isExoticSpace(char c) {
        return c == NO_BREAK_SPACE
                || c == FIGURE_SPACE
                || c == NARROW_NO_BREAK_SPACE
                || (c >= EN_QUAD && c <= HAIR_SPACE);
    }

    private String unifyDashes(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean isDashVariant =
                    (c >= HYPHEN_2010 && c <= HORIZONTAL_BAR_2015) || c == MINUS_SIGN;
            out.append(isDashVariant ? '-' : c);
        }
        return out.toString();
    }

    /**
     * Junta los titulos escritos letra a letra. Trabaja linea a linea y solo actua si la
     * linea entera parece espaciada; dentro de ella, dos o mas espacios seguidos separan
     * palabras y un espacio simple separa letras de la misma palabra.
     */
    private String collapseLetterSpacing(String text) {
        StringBuilder out = new StringBuilder(text.length());
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(isLetterSpaced(lines[i]) ? joinSpacedLetters(lines[i]) : lines[i]);
        }
        return out.toString();
    }

    private boolean isLetterSpaced(String line) {
        String stripped = line.strip();
        if (stripped.isEmpty()) {
            return false;
        }
        String[] tokens = ANY_WHITESPACE.split(stripped);
        long singleLetters = 0;
        for (String token : tokens) {
            if (token.length() == 1 && Character.isUpperCase(token.charAt(0))) {
                singleLetters++;
            }
        }
        return singleLetters >= MIN_SPACED_LETTERS
                && singleLetters >= tokens.length * MIN_SPACED_RATIO;
    }

    private String joinSpacedLetters(String line) {
        String[] words = WORD_SEPARATOR.split(line.strip());
        StringBuilder joined = new StringBuilder();
        for (String word : words) {
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(ANY_WHITESPACE.matcher(word).replaceAll(""));
        }
        return joined.toString();
    }
}
