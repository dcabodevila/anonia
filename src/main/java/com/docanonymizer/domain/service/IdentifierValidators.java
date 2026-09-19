package com.docanonymizer.domain.service;

import java.math.BigInteger;
import java.util.Locale;

/**
 * Validadores de los identificadores que tienen algoritmo de control oficial.
 *
 * <p>Son lo que separa un detector util de un generador de ruido. "12345678A" encaja en
 * el patron de un DNI, pero su letra de control no cuadra: casi siempre es un numero de
 * expediente, un importe o una referencia, no el documento de nadie. Comprobar el digito
 * de control convierte precision aproximada en precision practicamente exacta, y ese
 * salto es lo que permite fiarse de estos tipos sin modelo estadistico ninguno.
 */
public final class IdentifierValidators {

    private static final String DNI_LETTERS = "TRWAGMYFPDXBNJZSQVHLCKE";
    private static final int DNI_MODULUS = 23;
    private static final BigInteger IBAN_MODULUS = BigInteger.valueOf(97);

    private IdentifierValidators() {
    }

    /** DNI espanol: 8 digitos y una letra de control calculada como numero mod 23. */
    public static boolean isValidDni(String raw) {
        String clean = clean(raw);
        if (clean.length() != 9) {
            return false;
        }
        String digits = clean.substring(0, 8);
        char letter = clean.charAt(8);
        if (!digits.chars().allMatch(Character::isDigit) || !Character.isLetter(letter)) {
            return false;
        }
        return expectedLetter(Integer.parseInt(digits)) == letter;
    }

    /** NIE: la letra inicial X, Y o Z vale 0, 1 o 2, y despues se aplica el mismo mod 23. */
    public static boolean isValidNie(String raw) {
        String clean = clean(raw);
        if (clean.length() != 9) {
            return false;
        }
        char prefix = clean.charAt(0);
        int prefixValue = switch (prefix) {
            case 'X' -> 0;
            case 'Y' -> 1;
            case 'Z' -> 2;
            default -> -1;
        };
        if (prefixValue < 0) {
            return false;
        }
        String digits = clean.substring(1, 8);
        char letter = clean.charAt(8);
        if (!digits.chars().allMatch(Character::isDigit) || !Character.isLetter(letter)) {
            return false;
        }
        return expectedLetter(Integer.parseInt(prefixValue + digits)) == letter;
    }

    /**
     * IBAN segun ISO 13616: se mueven los cuatro primeros caracteres al final, cada letra
     * se sustituye por su posicion + 9, y el numero resultante debe dar 1 modulo 97.
     */
    public static boolean isValidIban(String raw) {
        String clean = clean(raw);
        if (clean.length() < 15 || clean.length() > 34) {
            return false;
        }
        if (!Character.isLetter(clean.charAt(0)) || !Character.isLetter(clean.charAt(1))) {
            return false;
        }
        String rearranged = clean.substring(4) + clean.substring(0, 4);
        StringBuilder numeric = new StringBuilder(rearranged.length() * 2);
        for (char c : rearranged.toCharArray()) {
            if (Character.isDigit(c)) {
                numeric.append(c);
            } else if (Character.isLetter(c)) {
                numeric.append(c - 'A' + 10);
            } else {
                return false;
            }
        }
        try {
            return new BigInteger(numeric.toString()).mod(IBAN_MODULUS).intValue() == 1;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Codigo postal espanol: las dos primeras cifras son una provincia entre 01 y 52. */
    public static boolean isValidPostalCode(String raw) {
        String clean = clean(raw);
        if (clean.length() != 5 || !clean.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int province = Integer.parseInt(clean.substring(0, 2));
        return province >= 1 && province <= 52;
    }

    private static char expectedLetter(int number) {
        return DNI_LETTERS.charAt(number % DNI_MODULUS);
    }

    private static String clean(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            }
        }
        return out.toString().toUpperCase(Locale.ROOT);
    }
}
