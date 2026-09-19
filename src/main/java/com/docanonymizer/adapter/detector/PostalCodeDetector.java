package com.docanonymizer.adapter.detector;

import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.model.Provenance;
import com.docanonymizer.domain.port.DetectorPort;
import com.docanonymizer.domain.service.CanonicalForm;
import com.docanonymizer.domain.service.IdentifierValidators;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Codigo postal espanol, aceptado SOLO con contexto.
 *
 * <p>Cinco digitos sueltos son, la mayoria de las veces, un importe, un numero de
 * expediente o una referencia. Detectarlos sin contexto llenaria el documento de
 * [CP_nnn] en mitad de las cifras y haria inservible la revision, que es el recurso mas
 * escaso del proceso. Por eso se exige una marca explicita ("C.P. 28013") o una
 * poblacion detras ("28013 Madrid").
 *
 * <p>No hereda de {@link RegexDetector} porque necesita dos alternativas con grupos de
 * captura distintos, y forzar eso en la clase base la complicaria para todos los demas.
 */
public final class PostalCodeDetector implements DetectorPort {

    private static final Pattern WITH_MARKER =
            Pattern.compile("(?:C\\.?\\s?P\\.?|[Cc][oó]digo\\s+[Pp]ostal)[\\s:]*(\\d{5})(?!\\d)");

    /**
     * "28013 Madrid" y tambien "15707 SANTIAGO DE COMPOSTELA". Exigir que la poblacion
     * fuera Capitalizada dejaba fuera los bloques de direccion en mayusculas, que es
     * justo como los imprime la mayoria de las facturas.
     */
    private static final Pattern WITH_CITY = Pattern.compile(
            "(?<!\\d)(\\d{5})(?=\\s+(?:[A-ZÁÉÍÓÚÑ][a-záéíóúñ]{2,}|[A-ZÁÉÍÓÚÑ]{3,}))");

    private final AtomicInteger sequence = new AtomicInteger();

    @Override
    public String name() {
        return "cp";
    }

    @Override
    public List<Detection> detect(String normalizedText) {
        List<Detection> detections = new ArrayList<>();
        collect(WITH_MARKER, normalizedText, detections);
        collect(WITH_CITY, normalizedText, detections);
        return detections;
    }

    private void collect(Pattern pattern, String text, List<Detection> out) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (!IdentifierValidators.isValidPostalCode(value)) {
                continue;
            }
            out.add(new Detection(
                    "cp-" + sequence.incrementAndGet(),
                    DetectionType.POSTAL_CODE,
                    matcher.start(1),
                    matcher.end(1),
                    value,
                    DetectionType.POSTAL_CODE.name() + ":" + CanonicalForm.forCompare(value),
                    Provenance.REGEX,
                    0.70));
        }
    }
}
