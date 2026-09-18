package com.docanonymizer.domain.service;

/**
 * Guarda de cordura sobre el texto extraido. Falla cerrado.
 *
 * <p>Existe por un fallo silencioso concreto: hay PDF que se renderizan perfectamente
 * pero extraen basura, porque su mapa /ToUnicode es incorrecto o falta. Sin esta
 * comprobacion el pipeline no detectaria nada, la verificacion pasaria (no hay valores
 * que buscar) y entregaria un Markdown ilegible que el usuario creeria anonimizado.
 *
 * <p>Tambien captura por el mismo camino los PDF escaneados sin capa de texto, que
 * estan fuera del alcance de este prototipo.
 */
public final class TextSanityGuard {

    private static final int MIN_MEANINGFUL_CHARS = 40;
    private static final double MAX_SUSPICIOUS_RATIO = 0.05;
    private static final double MIN_LETTER_RATIO = 0.45;

    public record Result(boolean usable, String reason, Stats stats) {
        public static Result ok(Stats stats) {
            return new Result(true, "texto utilizable", stats);
        }

        public static Result rejected(String reason, Stats stats) {
            return new Result(false, reason, stats);
        }
    }

    public record Stats(int meaningful, int letters, int suspicious) {
        public double letterRatio() {
            return meaningful == 0 ? 0.0 : (double) letters / meaningful;
        }

        public double suspiciousRatio() {
            return meaningful == 0 ? 0.0 : (double) suspicious / meaningful;
        }
    }

    public Result check(String rawText) {
        Stats stats = measure(rawText);

        if (stats.meaningful() < MIN_MEANINGFUL_CHARS) {
            return Result.rejected(
                    "el documento no tiene capa de texto utilizable (" + stats.meaningful()
                            + " caracteres). Un PDF escaneado esta fuera del alcance de este prototipo.",
                    stats);
        }
        if (stats.suspiciousRatio() > MAX_SUSPICIOUS_RATIO) {
            return Result.rejected(
                    String.format(
                            "%.1f%% de caracteres no mapeables: el /ToUnicode del PDF es incorrecto "
                                    + "y el texto extraido no es fiable.",
                            stats.suspiciousRatio() * 100),
                    stats);
        }
        if (stats.letterRatio() < MIN_LETTER_RATIO) {
            return Result.rejected(
                    String.format(
                            "solo el %.1f%% del contenido son letras: el texto extraido no parece "
                                    + "lenguaje natural.",
                            stats.letterRatio() * 100),
                    stats);
        }
        return Result.ok(stats);
    }

    private Stats measure(String text) {
        int meaningful = 0;
        int letters = 0;
        int suspicious = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            meaningful++;
            if (Character.isLetter(c)) {
                letters++;
            }
            if (isSuspicious(c)) {
                suspicious++;
            }
        }
        return new Stats(meaningful, letters, suspicious);
    }

    private boolean isSuspicious(char c) {
        if (c == '\uFFFD') {
            return true;
        }
        if (c >= '\uE000' && c <= '\uF8FF') {
            return true;
        }
        return Character.isISOControl(c);
    }
}
