package com.docanonymizer.domain.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Convierte el texto ya sustituido en Markdown de baja fidelidad.
 *
 * <p>Deliberadamente NO intenta reconstruir tablas ni columnas. Una tabla mal inferida
 * es peor que un parrafo de texto plano ordenado: mueve celdas de sitio y cambia lo que
 * el documento dice. El objetivo del prototipo es texto legible y fiel, no maquetacion.
 */
public final class MarkdownRenderer {

    /** Un encabezado de documento juridico va en mayusculas y es corto: "ANTECEDENTES DE HECHO". */
    private static final int MAX_HEADING_LENGTH = 80;
    private static final int MIN_HEADING_LETTERS = 3;

    /** Fraccion del ancho de bloque por debajo de la cual una linea parece cierre de parrafo. */
    private static final double SHORT_LINE_RATIO = 0.85;

    private static final Pattern LIST_MARKER = Pattern.compile(
            "^(?:[-*•·]\\s|\\d+[.)]\\s|[a-zA-Z][.)]\\s"
                    + "|(?:PRIMERO|SEGUNDO|TERCERO|CUARTO|QUINTO|SEXTO|SEPTIMO|OCTAVO|NOVENO|DECIMO)\\b)");

    private static final Pattern PSEUDONYM = Pattern.compile("\\[[A-Z_]+_\\d{3}\\]");
    private static final Pattern SENTENCE_END = Pattern.compile("[.:;!?]\\s*$");

    public record Metadata(
            String sourceSha256,
            int pageCount,
            Map<String, Long> countsByType,
            String toolVersion,
            Instant generatedAt) {
    }

    public String render(String anonymizedText, Metadata metadata) {
        return body(anonymizedText) + "\n";
    }

    private String body(String text) {
        List<String> out = new ArrayList<>();
        for (String block : text.split("\\n\\s*\\n")) {
            List<String> lines = block.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .toList();
            if (lines.isEmpty()) {
                continue;
            }
            out.addAll(renderBlock(lines));
        }
        return String.join("\n\n", out);
    }

    private List<String> renderBlock(List<String> lines) {
        int maxWidth = lines.stream().mapToInt(String::length).max().orElse(0);
        List<String> chunks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();

        for (String line : lines) {
            if (isHeading(line)) {
                flush(paragraph, chunks);
                chunks.add("## " + line);
                continue;
            }
            if (LIST_MARKER.matcher(line).find()) {
                flush(paragraph, chunks);
            }
            if (paragraph.length() > 0) {
                paragraph.append(' ');
            }
            paragraph.append(line);

            if (looksLikeParagraphEnd(line, maxWidth)) {
                flush(paragraph, chunks);
            }
        }
        flush(paragraph, chunks);
        return chunks;
    }

    private void flush(StringBuilder paragraph, List<String> chunks) {
        if (paragraph.length() > 0) {
            chunks.add(paragraph.toString().strip());
            paragraph.setLength(0);
        }
    }

    /**
     * Heuristica clasica del "renglon corto": dentro de un parrafo justificado todas las
     * lineas llegan al margen; la ultima no. Si ademas cierra con puntuacion, es final.
     */
    private boolean looksLikeParagraphEnd(String line, int maxWidth) {
        if (maxWidth == 0) {
            return true;
        }
        boolean short_ = line.length() < maxWidth * SHORT_LINE_RATIO;
        return short_ && SENTENCE_END.matcher(line).find();
    }

    private boolean isHeading(String line) {
        if (line.length() > MAX_HEADING_LENGTH) {
            return false;
        }
        // Una linea que es basicamente un seudonimo no es un titulo, solo lo parece.
        if (PSEUDONYM.matcher(line).find()) {
            return false;
        }
        int letters = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!Character.isLetter(c)) {
                continue;
            }
            if (Character.isLowerCase(c)) {
                return false;
            }
            letters++;
        }
        return letters >= MIN_HEADING_LETTERS;
    }
}
