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
 * Base de los detectores basados en patron.
 *
 * <p>Cada subclase aporta patron, tipo y —si lo tiene— validacion de digito de control.
 * Ninguna mira lo que hacen las demas: los solapes los arbitra {@code DetectionEngine}.
 */
public abstract class RegexDetector implements DetectorPort {

    private final AtomicInteger sequence = new AtomicInteger();
    private final String name;
    private final Pattern pattern;
    private final DetectionType type;
    private final Provenance provenance;
    private final double confidence;

    protected RegexDetector(
            String name,
            Pattern pattern,
            DetectionType type,
            Provenance provenance,
            double confidence) {
        this.name = name;
        this.pattern = pattern;
        this.type = type;
        this.provenance = provenance;
        this.confidence = confidence;
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public List<Detection> detect(String normalizedText) {
        List<Detection> detections = new ArrayList<>();
        Matcher matcher = pattern.matcher(normalizedText);
        int group = captureGroup();

        while (matcher.find()) {
            String value = matcher.group(group);
            if (value == null || value.isBlank() || !accepts(value)) {
                continue;
            }
            detections.add(new Detection(
                    name + "-" + sequence.incrementAndGet(),
                    type,
                    matcher.start(group),
                    matcher.end(group),
                    value,
                    entityKey(value),
                    provenance,
                    confidence));
        }
        return detections;
    }

    /** Grupo de captura que delimita el valor. Por defecto la coincidencia entera. */
    protected int captureGroup() {
        return 0;
    }

    /** Validacion adicional: digito de control, rango, lista negra de falsos amigos. */
    protected boolean accepts(String value) {
        return true;
    }

    /**
     * Clave de agrupacion. Lleva el tipo por delante para que un telefono y un codigo
     * numerico que casualmente coincidan no acaben compartiendo seudonimo.
     */
    protected String entityKey(String value) {
        return type.name() + ":" + CanonicalForm.forCompare(value);
    }

    protected DetectionType type() {
        return type;
    }
}
