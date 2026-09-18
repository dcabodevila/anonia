package com.docanonymizer.domain.service;

import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.DetectionType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unifica las entidades de persona que son la misma persona escrita de forma mas corta.
 *
 * <p>Sin este paso, "D. Juan Perez Lopez" y "el Sr. Perez Lopez" producen DOS entidades
 * y por tanto dos etiquetas distintas para el mismo ser humano. No es una fuga —ambas se
 * ocultan— pero destruye lo unico que justifica usar seudonimos en lugar de tachones:
 * que el lector siga pudiendo saber quien hizo que.
 *
 * <p>Regla de fusion: una entidad se absorbe en otra si sus tokens significativos son un
 * subconjunto propio de los de la otra. La fusion se aplica SOLO cuando el candidato
 * tiene exactamente un superconjunto. Con dos o mas —"Perez Lopez" conviviendo con
 * "Juan Perez Lopez" y "Ana Perez Lopez"— la mencion corta es genuinamente ambigua, y
 * resolverla a la brava inventaria un hecho que el documento no dice. Se dejan separadas
 * y la decision es de la revision humana.
 */
public final class PersonEntityResolver {

    public List<Detection> resolve(List<Detection> detections) {
        Map<String, String> canonicalNameByEntity = longestNamePerEntity(detections);
        if (canonicalNameByEntity.size() < 2) {
            return detections;
        }

        Map<String, Set<String>> tokensByEntity = new LinkedHashMap<>();
        canonicalNameByEntity.forEach((entityKey, name) ->
                tokensByEntity.put(entityKey, canonicalTokens(name)));

        Map<String, String> redirect = buildRedirects(tokensByEntity);
        if (redirect.isEmpty()) {
            return detections;
        }

        List<Detection> merged = new ArrayList<>(detections.size());
        for (Detection detection : detections) {
            String target = redirect.get(detection.entityKey());
            merged.add(target == null ? detection : withEntityKey(detection, target));
        }
        return List.copyOf(merged);
    }

    private Map<String, String> buildRedirects(Map<String, Set<String>> tokensByEntity) {
        Map<String, String> redirect = new HashMap<>();

        for (Map.Entry<String, Set<String>> candidate : tokensByEntity.entrySet()) {
            Set<String> candidateTokens = candidate.getValue();
            if (candidateTokens.isEmpty()) {
                continue;
            }
            List<String> supersets = new ArrayList<>();
            for (Map.Entry<String, Set<String>> other : tokensByEntity.entrySet()) {
                if (other.getKey().equals(candidate.getKey())) {
                    continue;
                }
                Set<String> otherTokens = other.getValue();
                if (otherTokens.size() > candidateTokens.size()
                        && otherTokens.containsAll(candidateTokens)) {
                    supersets.add(other.getKey());
                }
            }
            if (supersets.size() == 1) {
                redirect.put(candidate.getKey(), supersets.get(0));
            }
        }
        return collapseChains(redirect);
    }

    /**
     * Sigue las redirecciones hasta el final para que A -> B -> C acabe siendo A -> C.
     * El limite de saltos evita un bucle infinito si dos entidades se apuntan entre si.
     */
    private Map<String, String> collapseChains(Map<String, String> redirect) {
        Map<String, String> collapsed = new HashMap<>();
        for (String source : redirect.keySet()) {
            String target = redirect.get(source);
            Set<String> visited = new HashSet<>();
            visited.add(source);
            while (redirect.containsKey(target) && visited.add(target)) {
                target = redirect.get(target);
            }
            if (!target.equals(source)) {
                collapsed.put(source, target);
            }
        }
        return collapsed;
    }

    private Map<String, String> longestNamePerEntity(List<Detection> detections) {
        Map<String, String> longest = new LinkedHashMap<>();
        for (Detection detection : detections) {
            if (detection.type() != DetectionType.PERSON) {
                continue;
            }
            longest.merge(detection.entityKey(), detection.value(),
                    (a, b) -> a.length() >= b.length() ? a : b);
        }
        return longest;
    }

    private Set<String> canonicalTokens(String name) {
        Set<String> tokens = new HashSet<>();
        for (String token : NamePolicy.significantTokens(name)) {
            tokens.add(CanonicalForm.forCompare(token));
        }
        return tokens;
    }

    private Detection withEntityKey(Detection detection, String entityKey) {
        return new Detection(
                detection.id(),
                detection.type(),
                detection.start(),
                detection.end(),
                detection.value(),
                entityKey,
                detection.provenance(),
                detection.confidence());
    }
}
