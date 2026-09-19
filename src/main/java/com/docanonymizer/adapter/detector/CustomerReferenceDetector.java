package com.docanonymizer.adapter.detector;

import com.docanonymizer.domain.model.DetectionType;
import com.docanonymizer.domain.model.Provenance;
import java.util.regex.Pattern;

/**
 * Referencias que identifican al interesado en el sistema de quien emite el documento:
 * numero de cliente, de pedido, de expediente, de abonado o de poliza.
 *
 * <p>Son datos personales aunque no lo parezcan. Un numero de cliente no dice como se
 * llama nadie, pero senala a una persona concreta dentro de un fichero, y quien tenga
 * acceso a ese fichero la reidentifica de inmediato. Dejarlo intacto en un documento
 * "desidentificado" es precisamente el error que el propio briefing advertia: confundir
 * "no aparece el nombre" con "no se puede identificar a la persona".
 *
 * <p>Solo se detecta con la etiqueta delante. Una cifra larga suelta en una factura es
 * casi siempre un importe o una referencia de articulo, y detectarla llenaria de ruido la
 * revision, que es el recurso mas escaso del proceso.
 */
public final class CustomerReferenceDetector extends RegexDetector {

    private static final String LABELS =
            "(?:n[uú]mero|n[uú]m\\.?|n[.ºo°]{1,2})\\s+de\\s+"
                    + "(?:cliente|pedido|abonado|p[oó]liza|expediente|contrato|socio)";

    private static final Pattern PATTERN = Pattern.compile(
            "(?i:" + LABELS + ")\\s*[:.]?\\s*([A-Z]{0,3}\\d{5,20})(?![\\dA-Za-z])");

    public CustomerReferenceDetector() {
        super("cliente", PATTERN, DetectionType.CUSTOMER_ID, Provenance.REGEX, 0.90);
    }

    @Override
    protected int captureGroup() {
        return 1;
    }
}
