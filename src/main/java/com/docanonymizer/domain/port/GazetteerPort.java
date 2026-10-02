package com.docanonymizer.domain.port;

/**
 * Diccionarios locales de nombres de pila y apellidos.
 *
 * <p>Los recursos aprobados y versionados se cargan offline. Los metodos opcionales
 * conservan la compatibilidad con implementaciones que solo conocen nombres de pila.
 */
public interface GazetteerPort {

    /** Comparacion insensible a mayusculas y a acentos. */
    boolean isGivenName(String token);

    /** Segundo indicio opcional para candidatos de varias palabras. */
    default boolean isSurname(String token) { return false; }

    /** Palabras que no pueden iniciar un candidato basado solo en apellidos. */
    default boolean isExcludedWord(String token) { return false; }

    /** Numero de nombres de pila; no incluye apellidos. */
    int size();
}
