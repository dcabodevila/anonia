package com.docanonymizer.domain.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deja el texto en un estado sobre el que las expresiones regulares tienen sentido.
 *
 * <p>Sin este paso la deteccion falla en silencio, que es el peor modo de fallo posible:
 * un NIF partido por un guion de fin de linea o un nombre con letter-spacing no lo
 * captura ningun patron, y el pipeline exporta creyendo que no habia nada que ocultar.
 *
 * <p>Detection uses normalized coordinates; mapped normalization projects detections
 * onto the retained extracted source before review and replacement.
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

    /** Normalized matching text with UTF-16 ranges in the original retained source. */
    public MappedText normalizeMapped(String source) {
        MappedText mapped = MappedText.identity(source);
        mapped = normalizeGraphemes(mapped);
        mapped = replace(mapped, Pattern.compile("\\r\\n|\\r"), part -> "\n");
        mapped = replace(mapped, Pattern.compile("[\\u00AD\\uFEFF\\u200B-\\u200D]"), part -> "");
        mapped = replace(mapped, Pattern.compile("[\\u00A0\\u2000-\\u200A\\u202F]"), part -> " ");
        mapped = replace(mapped, Pattern.compile("[\\u2010-\\u2015\\u2212]"), part -> "-");
        mapped = replace(mapped, LINE_BREAK_HYPHEN, part -> "" + part.charAt(0) + part.charAt(part.length() - 1));
        mapped = replace(mapped, Pattern.compile("[^\\n]*"),
                line -> isLetterSpaced(line) ? joinSpacedLetters(line) : line);
        mapped = replace(mapped, TRAILING_SPACES, part -> "\n");
        mapped = replace(mapped, HORIZONTAL_RUNS, part -> " ");
        mapped = replace(mapped, EXTRA_BLANK_LINES, part -> "\n\n");
        String text = mapped.text.strip();
        int from = mapped.text.indexOf(text);
        if (!text.equals(normalize(source))) {
            throw new IllegalStateException("Mapped normalization differs from detection normalization");
        }
        if (text.isEmpty()) return new MappedText("", new int[0], new int[0]);
        return mapped.slice(from, from + text.length());
    }

    private MappedText normalizeGraphemes(MappedText input) {
        java.text.BreakIterator iterator = java.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT);
        iterator.setText(input.text);
        StringBuilder output = new StringBuilder();
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        for (int from = iterator.first(), to = iterator.next(); to != java.text.BreakIterator.DONE;
                from = to, to = iterator.next()) {
            String cluster = Normalizer.normalize(input.text.substring(from, to), Normalizer.Form.NFC);
            output.append(cluster);
            for (int i = 0; i < cluster.length(); i++) {
                starts.add(input.starts[from]);
                ends.add(input.ends[to - 1]);
            }
        }
        return new MappedText(output.toString(), starts.stream().mapToInt(Integer::intValue).toArray(),
                ends.stream().mapToInt(Integer::intValue).toArray());
    }

    public static final class MappedText {
        private final String text;
        private final int[] starts;
        private final int[] ends;

        private MappedText(String text, int[] starts, int[] ends) {
            this.text = text;
            this.starts = starts;
            this.ends = ends;
        }

        public String text() { return text; }
        public int start(int index) { return starts[index]; }
        public int end(int index) { return ends[index - 1]; }

        private MappedText slice(int from, int to) {
            return new MappedText(text.substring(from, to),
                    java.util.Arrays.copyOfRange(starts, from, to),
                    java.util.Arrays.copyOfRange(ends, from, to));
        }

        private static MappedText identity(String source) {
            int[] starts = new int[source.length()];
            int[] ends = new int[source.length()];
            for (int i = 0; i < source.length(); i++) {
                starts[i] = i;
                ends[i] = i + 1;
            }
            return new MappedText(source, starts, ends);
        }
    }

    private MappedText replace(MappedText input, Pattern pattern, Function<String, String> transform) {
        Matcher matcher = pattern.matcher(input.text);
        StringBuilder output = new StringBuilder();
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        int cursor = 0;
        while (matcher.find()) {
            appendUnchanged(input, cursor, matcher.start(), output, starts, ends);
            String replacement = transform.apply(matcher.group());
            int sourceCursor = matcher.start();
            for (int i = 0; i < replacement.length(); i++) {
                char c = replacement.charAt(i);
                int match = input.text.indexOf(c, sourceCursor);
                if (match >= matcher.end()) match = -1;
                int at = match < 0 ? Math.min(sourceCursor, matcher.end() - 1) : match;
                output.append(c);
                starts.add(input.starts[at]);
                ends.add(input.ends[at]);
                if (match >= 0) sourceCursor = match + 1;
            }
            if (!replacement.isEmpty() && matcher.start() < matcher.end()) {
                int first = starts.size() - replacement.length();
                starts.set(first, input.starts[matcher.start()]);
                ends.set(ends.size() - 1, input.ends[matcher.end() - 1]);
            }
            cursor = matcher.end();
        }
        appendUnchanged(input, cursor, input.text.length(), output, starts, ends);
        return new MappedText(output.toString(), starts.stream().mapToInt(Integer::intValue).toArray(),
                ends.stream().mapToInt(Integer::intValue).toArray());
    }

    private void appendUnchanged(MappedText input, int from, int to, StringBuilder output,
            List<Integer> starts, List<Integer> ends) {
        output.append(input.text, from, to);
        for (int i = from; i < to; i++) {
            starts.add(input.starts[i]);
            ends.add(input.ends[i]);
        }
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
