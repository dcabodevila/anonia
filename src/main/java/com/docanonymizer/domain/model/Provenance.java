package com.docanonymizer.domain.model;

/**
 * De donde viene una deteccion. Se muestra al revisor humano para que pueda
 * calibrar cuanto se fia de cada candidato; nunca se usa para descartar en silencio.
 */
public enum Provenance {
    /** Patron con algoritmo de control verificado (DNI, NIE, IBAN). */
    CHECKSUM_VALIDATED,
    /** Patron lexico sin digito de control (email, telefono, matricula). */
    REGEX,
    /** Pista estructural del dominio juridico: "D.", "Dna.", "con DNI". */
    STRUCTURAL_CUE,
    /** Nombre de pila encontrado en el diccionario local. */
    GAZETTEER,
    /** Aparicion adicional de una entidad ya detectada en otro punto del texto. */
    PROPAGATED,
    /** Area marcada a mano por el operador. */
    MANUAL
}
