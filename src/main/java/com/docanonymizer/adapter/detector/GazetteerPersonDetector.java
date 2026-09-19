package com.docanonymizer.adapter.detector;

import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.model.Provenance;
import com.docanonymizer.domain.port.DetectorPort;
import com.docanonymizer.domain.port.GazetteerPort;
import com.docanonymizer.domain.service.IdentifierValidators;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encuentra personas por el nombre de pila, usando un diccionario local.
 *
 * <p>Complementa al detector estructural: cubre las menciones sin ceremonia ("segun
 * declara Ana Ruiz Molina...") que ninguna pista sintactica ancla.
 *
 * <p>La estrategia es buscar candidatos a nombre completo y quedarse solo con aquellos
 * cuya primera palabra esta en el diccionario. Al reves —recorrer el diccionario
 * buscandolo en el texto— seria mucho mas lento y daria los mismos resultados.
 */
public final class GazetteerPersonDetector implements DetectorPort {

    private static final Pattern CANDIDATE = Pattern.compile(SpanishNamePatterns.FULL_NAME);

    private final GazetteerPort gazetteer;
    private final AtomicInteger sequence = new AtomicInteger();

    public GazetteerPersonDetector(GazetteerPort gazetteer) {
        this.gazetteer = gazetteer;
    }

    @Override
    public String name() {
        return "persona-diccionario";
    }

    @Override
    public List<Detection> detect(String normalizedText) {
        List<Detection> detections = new ArrayList<>();
        Matcher matcher = CANDIDATE.matcher(normalizedText);

        while (matcher.find()) {
            String firstWord = matcher.group().split("\\s+")[0];
            if (!gazetteer.isGivenName(firstWord)) {
                continue;
            }
            if (precededByPostalCode(normalizedText, matcher.start())) {
                continue;
            }
            int end = NameSpanRefiner.refine(normalizedText, matcher.start(), matcher.end());
            if (end < 0) {
                continue;
            }
            String value = normalizedText.substring(matcher.start(), end);
            detections.add(new Detection(
                    "pers-dic-" + sequence.incrementAndGet(),
                    DetectionType.PERSON,
                    matcher.start(),
                    end,
                    value,
                    StructuralPersonDetector.entityKey(value),
                    Provenance.GAZETTEER,
                    0.85));
        }
        return detections;
    }

    /**
     * Un codigo postal justo delante convierte lo que sigue en una poblacion, no en una
     * persona. Sin esta comprobacion, "15707 SANTIAGO DE COMPOSTELA" se detecta como
     * persona: "Santiago" esta en el diccionario de nombres de pila, y lo esta con razon.
     */
    private boolean precededByPostalCode(String text, int start) {
        int i = start - 1;
        while (i >= 0 && (text.charAt(i) == ' ' || text.charAt(i) == '\n'
                || text.charAt(i) == '\t')) {
            i--;
        }
        if (i < 4) {
            return false;
        }
        String candidate = text.substring(i - 4, i + 1);
        return candidate.chars().allMatch(Character::isDigit)
                && IdentifierValidators.isValidPostalCode(candidate);
    }
}
