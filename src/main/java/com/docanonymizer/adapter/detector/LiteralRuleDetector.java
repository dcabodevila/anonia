package com.docanonymizer.adapter.detector;

import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.model.Provenance;
import com.docanonymizer.domain.port.DetectorPort;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Matches explicitly selected terms, never interpreting user text as regex syntax. */
public final class LiteralRuleDetector implements DetectorPort {
    private final List<Pattern> patterns;

    public LiteralRuleDetector(Collection<String> terms) {
        patterns = terms.stream().map(term -> Pattern.compile(
                "(?<![\\p{L}\\p{N}_])" + java.util.Arrays.stream(term.split("\\s+"))
                        .map(Pattern::quote).collect(java.util.stream.Collectors.joining("\\s+"))
                        + "(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)).toList();
    }

    @Override public String name() { return "literal-term"; }

    @Override public List<Detection> detect(String text) {
        List<Detection> result = new ArrayList<>();
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String value = matcher.group();
                result.add(new Detection("term-" + matcher.start() + "-" + matcher.end(),
                        DetectionType.TERM, matcher.start(), matcher.end(), value,
                        "term:" + value.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT),
                        Provenance.REGEX, 0.95));
            }
        }
        return result;
    }
}
