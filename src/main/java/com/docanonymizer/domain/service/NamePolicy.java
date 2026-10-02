package com.docanonymizer.domain.service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Politica compartida sobre que trozos de un nombre cuentan como identificadores.
 *
 * <p>Existe para que el propagador y el verificador no puedan divergir. Si el
 * propagador y verificador usaran distinta longitud o caja, la puerta de salida
 * bloquearia texto legitimo o dejaria pasar fugas. Son las
 * dos caras de la misma regla, asi que la regla vive en un solo sitio.
 */
public final class NamePolicy {

    /** Los tokens de 2-3 letras necesitan caja de nombre; los de 4+ no. */
    public static final int MIN_TOKEN_LENGTH = 2;
    private static final int ANY_CASE_TOKEN_LENGTH = 4;

    private static final Set<String> PARTICLES = Set.of(
            "de", "del", "la", "las", "los", "el", "y", "e", "da", "do", "dos", "van", "von",
            "san", "santa", "don", "dona", "sr", "sra", "srta", "sres");

    private NamePolicy() {
    }

    /**
     * Tokens de un nombre que deben desaparecer del resultado por si solos.
     *
     * <p>Incluye tambien el nombre de pila, no solo los apellidos. Es deliberado: la
     * alternativa deja "Maria" suelta en el texto cuando se oculto "Maria Garcia Perez",
     * y prefiero sobre-ocultar en un prototipo. El coste es que un nombre de pila
     * frecuente puede llevarse por delante contexto legitimo ("Virgen del Carmen"), y
     * ese es exactamente el tipo de decision que la revision humana debe poder revertir.
     */
    public static List<String> significantTokens(String fullName) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : fullName.split("\\s+")) {
            String canonical = CanonicalForm.forCompare(raw);
            if (canonical.length() < MIN_TOKEN_LENGTH || PARTICLES.contains(canonical)) {
                continue;
            }
            tokens.add(raw.strip());
        }
        return List.copyOf(tokens);
    }

    /** Busqueda compartida: caja y acentos libres salvo los tokens cortos sueltos. */
    public static List<int[]> findOccurrences(String text, String variant) {
        String word = variant.strip();
        var hits = TextFolding.findWholeWordOccurrences(text, word);
        if (word.matches(".*\\s+.*") || CanonicalForm.forCompare(word).length() >= ANY_CASE_TOKEN_LENGTH) {
            return hits;
        }
        if (significantTokens(word).isEmpty()) {
            return List.of();
        }
        return hits.stream().filter(hit -> {
            String occurrence = text.substring(hit[0], hit[1]);
            return occurrence.matches("\\p{Lu}\\p{Ll}+") || occurrence.matches("\\p{Lu}+");
        }).toList();
    }

    /** Trata "Garcia Perez" (los apellidos juntos) como una variante propia a buscar. */
    public static List<String> surnameGroup(String fullName) {
        List<String> words = Arrays.stream(fullName.split("\\s+"))
                .map(String::strip)
                .filter(w -> !w.isEmpty())
                .toList();
        if (words.size() < 3) {
            return List.of();
        }
        List<String> tail = words.subList(1, words.size());
        return List.of(String.join(" ", tail));
    }

    public static boolean isParticle(String token) {
        return PARTICLES.contains(CanonicalForm.forCompare(token));
    }
}
