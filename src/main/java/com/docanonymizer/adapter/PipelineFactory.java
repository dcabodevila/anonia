package com.docanonymizer.adapter;

import com.docanonymizer.adapter.detector.AddressDetector;
import com.docanonymizer.adapter.detector.CustomerReferenceDetector;
import com.docanonymizer.adapter.detector.DeterministicDetectors;
import com.docanonymizer.adapter.detector.GazetteerPersonDetector;
import com.docanonymizer.adapter.detector.LiteralOrganizationDetector;
import com.docanonymizer.adapter.detector.PostalCodeDetector;
import com.docanonymizer.adapter.detector.StructuralPersonDetector;
import com.docanonymizer.adapter.gazetteer.ResourceGazetteer;
import com.docanonymizer.adapter.ocr.LocalDocumentTextExtractor;
import com.docanonymizer.adapter.review.AutoAcceptReview;
import com.docanonymizer.domain.port.DetectorPort;
import com.docanonymizer.domain.port.ReviewPort;
import com.docanonymizer.domain.service.AnonymizationPipeline;
import com.docanonymizer.domain.service.DetectionEngine;
import com.docanonymizer.domain.service.RunScopedIdentifier;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Raiz de composicion: el unico punto del programa que conoce a la vez PDFBox, las
 * expresiones regulares y el diccionario. El dominio solo ve puertos.
 */
public final class PipelineFactory {

    private PipelineFactory() {
    }

    public static AnonymizationPipeline standard() {
        return withReview(new AutoAcceptReview(), Clock.systemUTC());
    }

    public static AnonymizationPipeline withReview(ReviewPort review, Clock clock) {
        return new AnonymizationPipeline(
                new LocalDocumentTextExtractor(),
                new DetectionEngine(defaultDetectors()),
                review,
                new RunScopedIdentifier(),
                clock);
    }

    /**
     * Orden irrelevante para el resultado: los solapes los arbitra {@code DetectionEngine}
     * por prioridad de tipo y longitud, no por el orden en que se registran los detectores.
     */
    public static List<DetectorPort> defaultDetectors() {
        List<DetectorPort> detectors = new ArrayList<>(DeterministicDetectors.all());
        detectors.add(new CustomerReferenceDetector());
        detectors.add(new PostalCodeDetector());
        detectors.add(new AddressDetector());
        detectors.add(new StructuralPersonDetector());
        detectors.add(new GazetteerPersonDetector(new ResourceGazetteer()));
        detectors.add(new LiteralOrganizationDetector(List.of("Banco Pastor", "Banco Popular")));
        return List.copyOf(detectors);
    }
}
