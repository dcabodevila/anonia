package com.docanonymizer.adapter.detector;

import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.model.Provenance;
import com.docanonymizer.domain.port.DetectorPort;
import com.docanonymizer.domain.service.CanonicalForm;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encuentra personas por la estructura del documento, no por el nombre en si.
 *
 * <p>Es el detector de mayor precision del prototipo, y la razon es que el castellano
 * juridico-administrativo es extremadamente regular: quien aparece detras de "D.",
 * "Dña." o delante de "con DNI" es una persona, casi sin excepcion. No hace falta ningun
 * modelo para saberlo.
 *
 * <p>Su recall por si solo es bajo —solo ve las menciones ceremoniosas—, y ahi es donde
 * entra {@code EntityPropagator}: basta anclar el nombre una vez con una pista fuerte
 * para poder perseguir todas sus demas apariciones por el documento.
 */
public final class StructuralPersonDetector implements DetectorPort {

    private static final String SPACE = SpanishNamePatterns.SOFT_SPACE;
    private static final String BOUNDARY = SpanishNamePatterns.NOT_AFTER_LETTER;

    /** "D. Juan Perez Lopez", "Dna. Maria Garcia". El tratamiento no entra en la captura. */
    private static final Pattern AFTER_HONORIFIC = Pattern.compile(
            SpanishNamePatterns.HONORIFIC + SPACE + "(" + SpanishNamePatterns.FULL_NAME + ")",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** "Maria Garcia Perez, con DNI 12345678Z" y variantes del giro. */
    private static final Pattern BEFORE_ID_DOCUMENT = Pattern.compile(
            "(" + SpanishNamePatterns.FULL_NAME + ")"
                    + "[ \\t]*,?" + SPACE + "(?:mayor de edad[ \\t]*,?" + SPACE + ")?"
                    + BOUNDARY + "(?:con|provisto de|provista de|titular del|titular de)"
                    + SPACE + "(?:D\\.?N\\.?I\\.?|N\\.?I\\.?F\\.?|N\\.?I\\.?E\\.?|documento)"
                    + "(?![\\p{L}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** "representado por Luis Martin Saez", "en nombre de ...". */
    private static final Pattern AFTER_ROLE = Pattern.compile(
            BOUNDARY
                    + "(?:representad[oa] por|en nombre de|a favor de|demandante|demandado)"
                    + SPACE + "(?:" + SpanishNamePatterns.HONORIFIC + SPACE + ")?"
                    + "(" + SpanishNamePatterns.FULL_NAME + ")",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final AtomicInteger sequence = new AtomicInteger();

    @Override
    public String name() {
        return "persona-estructural";
    }

    @Override
    public List<Detection> detect(String normalizedText) {
        List<Detection> detections = new ArrayList<>();
        collect(AFTER_HONORIFIC, normalizedText, 0.95, detections);
        collect(BEFORE_ID_DOCUMENT, normalizedText, 0.97, detections);
        collect(AFTER_ROLE, normalizedText, 0.90, detections);
        return detections;
    }

    private void collect(
            Pattern pattern, String text, double confidence, List<Detection> out) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (matcher.group(1) == null || matcher.group(1).isBlank()) {
                continue;
            }
            int end = NameSpanRefiner.refine(text, matcher.start(1), matcher.end(1));
            if (end < 0) {
                continue;
            }
            String value = text.substring(matcher.start(1), end);
            out.add(new Detection(
                    "pers-est-" + sequence.incrementAndGet(),
                    DetectionType.PERSON,
                    matcher.start(1),
                    end,
                    value,
                    entityKey(value),
                    Provenance.STRUCTURAL_CUE,
                    confidence));
        }
    }

    public static String entityKey(String name) {
        return DetectionType.PERSON.name() + ":" + CanonicalForm.forCompareKeepingWords(name);
    }
}
