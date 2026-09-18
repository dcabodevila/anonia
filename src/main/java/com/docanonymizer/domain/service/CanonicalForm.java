package com.docanonymizer.domain.service;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Forma canonica para comparar dos textos "iguales pero escritos distinto".
 *
 * <p>Se usa en dos sitios criticos: agrupar apariciones de la misma entidad bajo un
 * unico seudonimo, y comparar en la verificacion final. Que el verificador use esta
 * misma forma es lo que permite cazar fugas escritas con otro acento, otra caja u
 * otro espaciado ("GARCIA PEREZ" frente a "Garcia  Perez").
 */
public final class CanonicalForm {

    private CanonicalForm() {
    }

    /** Minusculas, sin acentos y sin nada que no sea letra o digito. */
    public static String forCompare(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) {
                continue;
            }
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /** Como {@link #forCompare} pero conservando un espacio entre palabras. */
    public static String forCompareKeepingWords(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder out = new StringBuilder(decomposed.length());
        boolean lastWasSpace = true;
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) {
                continue;
            }
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
                lastWasSpace = false;
            } else if (!lastWasSpace) {
                out.append(' ');
                lastWasSpace = true;
            }
        }
        return out.toString().strip();
    }
}
