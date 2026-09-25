package com.docanonymizer.adapter.detector;

import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.model.Provenance;
import com.docanonymizer.domain.port.DetectorPort;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detects explicit organization phrases, without matching inside larger words. */
public final class LiteralOrganizationDetector implements DetectorPort {
    private final List<Pattern> phrases;

    public LiteralOrganizationDetector(Collection<String> phrases) {
        this.phrases = Objects.requireNonNull(phrases).stream().map(phrase -> {
            if (phrase == null || phrase.isBlank()) {
                throw new IllegalArgumentException("Organization phrase must not be empty");
            }
            String flexiblePhrase = java.util.Arrays.stream(phrase.split("\\s+"))
                    .map(Pattern::quote).collect(java.util.stream.Collectors.joining("\\s+"));
            return Pattern.compile("(?<![\\p{L}\\p{N}_])" + flexiblePhrase
                    + "(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        }).toList();
    }

    @Override
    public String name() {
        return "literal-organization";
    }

    @Override
    public List<Detection> detect(String normalizedText) {
        List<Detection> found = new ArrayList<>();
        for (Pattern phrase : phrases) {
            Matcher matcher = phrase.matcher(normalizedText);
            while (matcher.find()) {
                String value = matcher.group();
                found.add(new Detection("org-" + matcher.start() + "-" + matcher.end(),
                        DetectionType.ORGANIZATION, matcher.start(), matcher.end(), value,
                        "organization:" + value.replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT),
                        Provenance.REGEX, 0.95));
            }
        }
        return found;
    }
}
