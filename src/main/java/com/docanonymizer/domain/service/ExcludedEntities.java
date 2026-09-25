package com.docanonymizer.domain.service;

import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Immutable, type-specific exclusion snapshot for pipeline candidates. */
public record ExcludedEntities(List<String> people, List<String> organizations, List<String> terms) {
    public ExcludedEntities {
        people = List.copyOf(people);
        organizations = List.copyOf(organizations);
        terms = List.copyOf(terms);
    }

    public static ExcludedEntities empty() {
        return new ExcludedEntities(List.of(), List.of(), List.of());
    }

    public List<Detection> filter(List<Detection> detections) {
        Set<String> excludedPeople = detections.stream()
                .filter(d -> d.type() == DetectionType.PERSON && matches(people, d.value()))
                .map(Detection::entityKey)
                .collect(Collectors.toSet());
        return detections.stream().filter(d -> switch (d.type()) {
            case PERSON -> !excludedPeople.contains(d.entityKey());
            case ORGANIZATION -> !matches(organizations, d.value());
            case TERM -> !matches(terms, d.value());
            default -> true;
        }).toList();
    }

    private static boolean matches(List<String> values, String candidate) {
        return values.stream().anyMatch(value ->
                CanonicalForm.forCompareKeepingWords(value).equals(CanonicalForm.forCompareKeepingWords(candidate)));
    }
}
