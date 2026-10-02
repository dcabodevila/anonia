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
 * Encuentra personas por el nombre de pila o un apellido, usando diccionarios locales.
 *
 * <p>Complementa al detector estructural: cubre las menciones sin ceremonia ("segun
 * declara Ana Ruiz Molina...") que ninguna pista sintactica ancla.
 *
 * <p>La estrategia es buscar candidatos a nombre completo y quedarse solo con aquellos
 * cuya primera palabra es un nombre de pila, o contienen un apellido posterior
 * sin empezar por una palabra excluida. Al reves —recorrer el diccionario
 * buscandolo en el texto— seria mucho mas lento y daria los mismos resultados.
 */
public final class GazetteerPersonDetector implements DetectorPort {

    private static final Pattern CANDIDATE = Pattern.compile(SpanishNamePatterns.FULL_NAME);
    private static final Pattern PARTICLE = Pattern.compile(SpanishNamePatterns.PARTICLE,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

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
            if (precededByPostalCode(normalizedText, matcher.start())) {
                continue;
            }
            int end = NameSpanRefiner.refine(normalizedText, matcher.start(), matcher.end());
            if (end < 0) {
                continue;
            }
            String value = normalizedText.substring(matcher.start(), end);
            String[] words = value.split("\\s+");
            boolean givenName = gazetteer.isGivenName(words[0]);
            if (!givenName && (gazetteer.isExcludedWord(words[0]) || !hasLaterSurname(words))) {
                continue;
            }
            detections.add(new Detection(
                    "pers-dic-" + sequence.incrementAndGet(),
                    DetectionType.PERSON,
                    matcher.start(),
                    end,
                    value,
                    StructuralPersonDetector.entityKey(value),
                    Provenance.GAZETTEER,
                    givenName ? 0.85 : 0.80));
        }
        return detections;
    }

    private boolean hasLaterSurname(String[] words) {
        for (int i = 1; i < words.length; i++) {
            if (!gazetteer.isExcludedWord(words[i])
                    && !PARTICLE.matcher(words[i]).matches()
                    && gazetteer.isSurname(words[i])) {
                return true;
            }
        }
        return false;
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
