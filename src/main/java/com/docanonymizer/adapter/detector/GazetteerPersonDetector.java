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

    // Conectores de prosa nunca forman parte de un nombre, con o sin acentos.
    private static final String FUNCTION_WORD =
            "(?iu:seg[uú]n|conforme|ante|tras|desde|hasta|mediante|durante|contra|sobre|entre)(?!\\p{L})";
    private static final String GUARDED_WORD = "(?!" + FUNCTION_WORD + ")" + SpanishNamePatterns.NAME_WORD;
    private static final String GUARDED_NAME =
            SpanishNamePatterns.FULL_NAME.replace(SpanishNamePatterns.NAME_WORD, GUARDED_WORD);
    private static final Pattern SINGLE_WORD = Pattern.compile(SpanishNamePatterns.NAME_WORD);
    private static final Pattern CANDIDATE = Pattern.compile(
            GUARDED_NAME
                    + "|" + SpanishNamePatterns.NOT_AFTER_LETTER + FUNCTION_WORD
                    + SpanishNamePatterns.SOFT_SPACE + "(?:"
                    + GUARDED_NAME + "|" + GUARDED_WORD + "(?!\\p{L}))");
    private static final Pattern LEADING_FUNCTION = Pattern.compile(
            FUNCTION_WORD + SpanishNamePatterns.SOFT_SPACE);
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
            int start = matcher.start();
            Matcher connector = LEADING_FUNCTION.matcher(normalizedText).region(start, matcher.end());
            boolean afterFunction = connector.lookingAt();
            if (afterFunction) {
                start = connector.end();
            }
            boolean singleAfterFunction = afterFunction && SINGLE_WORD
                    .matcher(normalizedText.substring(start, matcher.end())).matches();
            int end = singleAfterFunction ? matcher.end()
                    : NameSpanRefiner.refine(normalizedText, start, matcher.end());
            if (end < 0) {
                continue;
            }
            String value = normalizedText.substring(start, end);
            String[] words = value.split("\\s+");
            boolean givenName = gazetteer.isGivenName(words[0]);
            if (gazetteer.isExcludedWord(words[0]) || (!givenName
                    && !hasLaterSurname(words) && !(afterFunction && gazetteer.isSurname(words[0])))) {
                continue;
            }
            detections.add(new Detection(
                    "pers-dic-" + sequence.incrementAndGet(),
                    DetectionType.PERSON,
                    start,
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
