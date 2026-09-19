package com.docanonymizer.domain.service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Politica compartida sobre que trozos de un nombre cuentan como identificadores.
 *
 * <p>Existe para que el propagador y el verificador no puedan divergir. Si el
 * propagador ocultara los tokens de 4+ caracteres pero el verificador exigiera los de
 * 3+, la puerta de salida bloquearia siempre; al reves, dejaria pasar fugas. Son las
 * dos caras de la misma regla, asi que la regla vive en un solo sitio.
 */
public final class NamePolicy {

    /** Un token mas corto genera demasiados falsos positivos para propagarlo solo. */
    public static final int MIN_TOKEN_LENGTH = 4;

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
