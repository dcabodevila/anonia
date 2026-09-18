package com.docanonymizer.domain.port;

import com.docanonymizer.domain.model.Detection;
import java.util.List;

/**
 * Un detector propone candidatos sobre el texto normalizado.
 *
 * <p>Contrato: los offsets devueltos son absolutos sobre el texto recibido, y cada
 * detector es independiente. Los solapes entre detectores los resuelve
 * {@code DetectionEngine}, no el detector.
 */
public interface DetectorPort {

    String name();

    List<Detection> detect(String normalizedText);
}
