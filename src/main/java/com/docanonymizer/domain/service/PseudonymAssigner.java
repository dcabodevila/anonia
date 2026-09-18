package com.docanonymizer.domain.service;

import com.docanonymizer.domain.model.Detection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Asigna una etiqueta estable a cada entidad: {@code [PERSONA_001]}, {@code [DNI_002]}.
 *
 * <p>La numeracion sigue el orden de aparicion en el documento, asi que dos ejecuciones
 * sobre el mismo texto producen el mismo resultado. Eso hace la salida diffeable y las
 * pruebas deterministas.
 *
 * <p>Esto es SEUDONIMIZACION, no anonimizacion. Mantener distinguibles a las personas es
 * justamente lo que conserva el sentido del documento ("quien demando a quien"), y a la
 * vez lo que permite reidentificar por estructura y contexto. El prototipo no guarda
 * tabla de correspondencias, de modo que la sustitucion es irreversible desde la salida,
 * pero el resultado sigue siendo un documento DESIDENTIFICADO, no anonimo.
 */
public final class PseudonymAssigner {

    /** entityKey -> etiqueta. */
    public Map<String, String> assign(List<Detection> detectionsInDocumentOrder) {
        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, Integer> countersByType = new HashMap<>();

        for (Detection detection : detectionsInDocumentOrder) {
            if (labels.containsKey(detection.entityKey())) {
                continue;
            }
            String typeLabel = detection.type().label();
            int next = countersByType.merge(typeLabel, 1, Integer::sum);
            labels.put(detection.entityKey(), String.format("[%s_%03d]", typeLabel, next));
        }
        return labels;
    }
}
