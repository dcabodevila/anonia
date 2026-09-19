package com.docanonymizer.adapter.cli;

import com.docanonymizer.domain.model.AnonymizationResult;
import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.model.Finding;
import com.docanonymizer.domain.service.AnonymizationPipeline;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Informe tecnico de la ejecucion. Se escribe SIEMPRE, tambien cuando la verificacion
 * bloquea: es lo que permite diagnosticar por que no se entrego el documento.
 *
 * <p>Contiene recuentos, procedencias, controles y hashes. No contiene ni un solo valor
 * detectado, ni el nombre del fichero original, ni fragmentos del texto. La razon es
 * directa: un informe que enumerase lo que encontro seria un fichero de datos personales
 * mas, con la misma sensibilidad que el documento de partida y ninguna de sus
 * protecciones.
 */
public final class TechnicalReport {

    private TechnicalReport() {
    }

    public static String render(AnonymizationResult result, Instant generatedAt) {
        StringBuilder out = new StringBuilder();

        out.append("# Informe tecnico de desidentificacion\n\n");
        out.append("| Campo | Valor |\n|---|---|\n");
        out.append("| herramienta | doc-anonymizer ")
                .append(AnonymizationPipeline.TOOL_VERSION).append(" |\n");
        out.append("| generado | ").append(generatedAt).append(" |\n");
        out.append("| origen sha-256 | `").append(result.sourceSha256()).append("` |\n");
        out.append("| paginas | ").append(result.pageCount()).append(" |\n");
        out.append("| entidades distintas | ").append(result.pseudonyms().size()).append(" |\n");
        out.append("| sustituciones | ").append(result.accepted().size()).append(" |\n");
        out.append("| resultado | ")
                .append(result.deliverable() ? "**ENTREGABLE**" : "**BLOQUEADO**")
                .append(" |\n\n");

        appendCounts(out, "Sustituciones por tipo", countBy(result, d -> d.type().label()));
        appendCounts(out, "Candidatos por procedencia",
                countBy(result, d -> d.provenance().name()));

        out.append("## Controles de verificacion\n\n");
        out.append("| Control | Gravedad | Detalle |\n|---|---|---|\n");
        for (Finding finding : result.verification().findings()) {
            out.append("| ").append(finding.control())
                    .append(" | ").append(finding.severity())
                    .append(" | ").append(finding.detail())
                    .append(" |\n");
        }
        out.append('\n');

        out.append("## Limitaciones declaradas\n\n");
        out.append("- El resultado es un documento **desidentificado**, no anonimo. ");
        out.append("Las etiquetas mantienen distinguibles a las personas, que es lo que ");
        out.append("conserva el sentido del texto y a la vez lo que permite reidentificar ");
        out.append("por estructura.\n");
        out.append("- Fechas, importes, cargos, localidades pequenas y hechos singulares ");
        out.append("permanecen intactos y pueden bastar para identificar a alguien.\n");
        out.append("- La deteccion automatica no garantiza haber encontrado todo. ");
        out.append("Los falsos negativos no aparecen en este informe porque, por ");
        out.append("definicion, el sistema no sabe que existen.\n");
        out.append("- Salida en texto plano: mas facil de procesar en masa que un PDF, ");
        out.append("y por tanto mas facil de correlacionar. Tratar en consecuencia.\n");

        return out.toString();
    }

    private static void appendCounts(StringBuilder out, String title, Map<String, Long> counts) {
        out.append("## ").append(title).append("\n\n");
        if (counts.isEmpty()) {
            out.append("_Sin detecciones._\n\n");
            return;
        }
        out.append("| Clave | Recuento |\n|---|---|\n");
        counts.forEach((key, count) ->
                out.append("| ").append(key).append(" | ").append(count).append(" |\n"));
        out.append('\n');
    }

    private static Map<String, Long> countBy(
            AnonymizationResult result, java.util.function.Function<Detection, String> key) {
        Map<String, Long> counts = new TreeMap<>();
        for (Detection detection : result.accepted()) {
            counts.merge(key.apply(detection), 1L, Long::sum);
        }
        return new LinkedHashMap<>(counts);
    }
}
