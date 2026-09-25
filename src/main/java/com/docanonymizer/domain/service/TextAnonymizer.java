package com.docanonymizer.domain.service;

import com.docanonymizer.domain.model.Detection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Sustituye cada deteccion aceptada por su seudonimo. */
public final class TextAnonymizer {

    /**
     * Aplica las sustituciones de derecha a izquierda.
     *
     * <p>Recorrer al reves no es un detalle de estilo: sustituir de izquierda a derecha
     * invalida los offsets de todas las detecciones siguientes en cuanto la etiqueta
     * tiene distinta longitud que el valor original, y el pipeline empezaria a cortar
     * por sitios equivocados sin que nada lo delate.
     */
    public String apply(String text, List<Detection> accepted, Map<String, String> pseudonyms) {
        List<Detection> reverse = new ArrayList<>(accepted);
        reverse.sort(Comparator.comparingInt(Detection::start).reversed());

        StringBuilder buffer = new StringBuilder(text);
        for (Detection detection : reverse) {
            String label = pseudonyms.get(detection.entityKey());
            if (label == null) {
                throw new IllegalStateException(
                        "Deteccion sin seudonimo asignado: " + detection.id());
            }
            String span = text.substring(detection.start(), detection.end());
            StringBuilder replacement = new StringBuilder(label);
            for (int i = 0; i < span.length(); i++) {
                char c = span.charAt(i);
                if (c == '\r' || c == '\n') replacement.append(c);
            }
            buffer.replace(detection.start(), detection.end(), replacement.toString());
        }
        return buffer.toString();
    }
}
