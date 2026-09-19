package com.docanonymizer.domain.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Validadores de identificadores con digito de control")
class IdentifierValidatorsTest {

    @Nested
    @DisplayName("DNI")
    class Dni {

        @ParameterizedTest
        @ValueSource(strings = {"12345678Z", "12345678-Z", "12345678 z", "00000000T"})
        @DisplayName("acepta la letra correcta con cualquier separador y caja")
        void acceptsValid(String dni) {
            assertTrue(IdentifierValidators.isValidDni(dni));
        }

        @ParameterizedTest
        @ValueSource(strings = {"12345678A", "87654321A", "1234567Z", "123456789Z", "ABCDEFGHZ"})
        @DisplayName("rechaza letra incorrecta y longitudes que no son ocho digitos")
        void rejectsInvalid(String dni) {
            assertFalse(IdentifierValidators.isValidDni(dni));
        }

        @org.junit.jupiter.api.Test
        @DisplayName("es lo que separa un DNI de un numero de expediente parecido")
        void rejectsLookalikeReference() {
            // Mismo aspecto que un DNI, letra de control que no cuadra: no es de nadie.
            assertFalse(IdentifierValidators.isValidDni("87654321A"));
            assertTrue(IdentifierValidators.isValidDni("87654321X"));
        }
    }

    @Nested
    @DisplayName("NIE")
    class Nie {

        @ParameterizedTest
        @ValueSource(strings = {"X1234567L", "X-1234567-L", "Y0000000Z", "Z0000000M"})
        @DisplayName("acepta los tres prefijos con su letra de control")
        void acceptsValid(String nie) {
            assertTrue(IdentifierValidators.isValidNie(nie));
        }

        @ParameterizedTest
        @ValueSource(strings = {"X1234567A", "W1234567L", "X123456L", "12345678Z"})
        @DisplayName("rechaza prefijo no valido y letra incorrecta")
        void rejectsInvalid(String nie) {
            assertFalse(IdentifierValidators.isValidNie(nie));
        }
    }

    @Nested
    @DisplayName("IBAN")
    class Iban {

        @ParameterizedTest
        @ValueSource(strings = {
                "ES9121000418450200051332",
                "ES91 2100 0418 4502 0005 1332",
                "es91 2100 0418 4502 0005 1332"})
        @DisplayName("acepta el mismo IBAN escrito de varias formas")
        void acceptsValid(String iban) {
            assertTrue(IdentifierValidators.isValidIban(iban));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "ES9121000418450200051333",
                "ES00 2100 0418 4502 0005 1332",
                "1234567890123456",
                "ES91"})
        @DisplayName("rechaza digitos de control alterados y longitudes imposibles")
        void rejectsInvalid(String iban) {
            assertFalse(IdentifierValidators.isValidIban(iban));
        }
    }

    @Nested
    @DisplayName("Codigo postal")
    class PostalCode {

        @ParameterizedTest
        @ValueSource(strings = {"28013", "01001", "52001"})
        @DisplayName("acepta provincias entre 01 y 52")
        void acceptsValid(String code) {
            assertTrue(IdentifierValidators.isValidPostalCode(code));
        }

        @ParameterizedTest
        @ValueSource(strings = {"00123", "53001", "99999", "2801"})
        @DisplayName("rechaza codigos de provincia inexistente")
        void rejectsInvalid(String code) {
            assertFalse(IdentifierValidators.isValidPostalCode(code));
        }
    }
}
