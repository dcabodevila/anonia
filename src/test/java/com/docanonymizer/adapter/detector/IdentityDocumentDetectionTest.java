package com.docanonymizer.adapter.detector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.docanonymizer.adapter.PipelineFactory;
import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.Provenance;
import com.docanonymizer.domain.service.DetectionEngine;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdentityDocumentDetectionTest {
    private final DetectionEngine engine = new DetectionEngine(PipelineFactory.defaultDetectors());

    private List<Detection> ofLabel(String source, String label) {
        return engine.detect(source).stream()
                .filter(detection -> detection.type().label().equals(label)).toList();
    }

    private boolean hasLabel(String source, String label) {
        return !ofLabel(source, label).isEmpty();
    }

    private void assertSingle(String source, String label, String value) {
        List<Detection> found = ofLabel(source, label);
        assertEquals(List.of(value), found.stream().map(Detection::value).toList(), source);
        assertEquals(source.indexOf(value), found.get(0).start(), source);
        assertEquals(source.indexOf(value) + value.length(), found.get(0).end(), source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"35.933.864K", "45.411.121-J", "34.544.121 F", "12345678Z", "12345678-Z",
            "12345678 z"})
    void dottedOrPlainDniWithValidLetterIsDni(String value) {
        String source = "con DNI " + value + ", vecino de Madrid";
        assertSingle(source, "DNI", value);
        assertEquals(Provenance.CHECKSUM_VALIDATED, ofLabel(source, "DNI").get(0).provenance());
        assertFalse(hasLabel(source, "CODIGO"), source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"35.933.864", "33123456", "34.544.121P", "45.411.121-V", "33123456-V",
            "12345678 A"})
    void eightDigitsWithoutLetterOrWithWrongLetterAreOneCode(String value) {
        String source = "con DNI " + value + ", vecino de Madrid";
        assertSingle(source, "CODIGO", value);
        assertFalse(hasLabel(source, "DNI"), source);
    }

    @Test
    void gluedWrongLetterStaysInsideTheCodeInRunningText() {
        String source = "El numero de referencia interna\n87654321A no identifica a ninguna persona.";
        assertSingle(source, "CODIGO", "87654321A");
        assertFalse(hasLabel(source, "DNI"), source);
    }

    @Test
    void lowercaseWordAfterASpaceIsNotTakenAsTheLetter() {
        String source = "expedientes 12345678 y 87654321 a revisar";
        assertEquals(List.of("12345678", "87654321"),
                ofLabel(source, "CODIGO").stream().map(Detection::value).toList());
        assertFalse(hasLabel(source, "DNI"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"DNI12345678Z", "Teléfono: 612.345.678", "importe 12.345.678,90 €",
            "12.345.678.901"})
    void nonDniNumbersStayOutOfDni(String source) {
        assertFalse(hasLabel(source, "DNI"), source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"importe de 12.345.678 €", "12.345.678€ de principal", "12.345.678 euros",
            "12.345.678 EUR"})
    void dottedAmountsWithCurrencyAreNotCodes(String source) {
        assertFalse(hasLabel(source, "CODIGO"), source);
        assertFalse(hasLabel(source, "DNI"), source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"87654321A €", "34.544.121P euros", "12.345.678 Europa"})
    void currencyOnlySparesLetterlessNumbers(String tail) {
        String value = tail.substring(0, tail.lastIndexOf(' '));
        assertSingle("pago " + tail, "CODIGO", value);
    }

    @Test
    void conjunctionBeforeSevenDigitsIsNotAnNie() {
        assertFalse(hasLabel("entre 5 y 1234567 registros", "NIE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"X1234567L", "X-1.234.567-L", "Y 1.234.567 X", "Z.7654321H", "x1234567l",
            "X - 1234567L", "x 1234567L", "x - 1234567-L"})
    void dottedOrPlainNieWithValidLetterIsNie(String value) {
        String source = "con NIE " + value + ", residente";
        assertSingle(source, "NIE", value);
        assertFalse(hasLabel(source, "CODIGO"), source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"X1234567", "X-1.234.567", "X1234567A", "Y 1.234.567-L"})
    void nieWithoutLetterOrWithWrongLetterIsOneCode(String value) {
        String source = "con NIE " + value + ", residente";
        assertSingle(source, "CODIGO", value);
        assertFalse(hasLabel(source, "NIE"), source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pasaporte PAA123456", "Pasaporte nº: XDA654321", "número de pasaporte ABC123456",
            "PASSPORT No. AAB987654"})
    void spanishPassportFormatAfterPassportContextIsPassport(String source) {
        String value = source.substring(source.lastIndexOf(' ') + 1);
        assertSingle(source + " expedido en 2020", "PASAPORTE", value);
        assertFalse(hasLabel(source, "CODIGO"), source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pasaporte C01X00T47", "Pasaporte n.º 123456", "pasaporte AB1234567",
            "passport number PAA12345A"})
    void otherPassportNumbersAreOneCode(String source) {
        String value = source.substring(source.lastIndexOf(' ') + 1);
        assertSingle(source + " expedido en 2020", "CODIGO", value);
        assertFalse(hasLabel(source, "PASAPORTE"), source);
    }

    @ParameterizedTest
    @ValueSource(strings = {"referencia PAA123456", "pasaporte caducado", "pasaporte ABCDEFGH"})
    void passportNeedsContextAndSpanishFormat(String source) {
        assertFalse(hasLabel(source, "PASAPORTE"), source);
    }
}
