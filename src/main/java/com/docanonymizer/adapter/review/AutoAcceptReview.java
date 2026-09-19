package com.docanonymizer.adapter.review;

import com.docanonymizer.domain.model.Detection;
import com.docanonymizer.domain.port.ReviewPort;
import java.util.List;

/**
 * Adaptador de revision del prototipo: acepta todos los candidatos.
 *
 * <p>ESTO NO ES UN SUSTITUTO DE LA REVISION HUMANA, y conviene no disimularlo. Todo el
 * argumento de seguridad de este producto descansa en que una persona mire lo que se va
 * a ocultar y, sobre todo, lo que NO se ha detectado. Un detector no puede encontrar lo
 * que no sabe buscar, y los falsos negativos son invisibles en cualquier interfaz que
 * solo muestre lo detectado.
 *
 * <p>Aceptar todo automaticamente sirve para dos cosas legitimas: medir el
 * comportamiento del pipeline sobre un corpus, y hacer las pruebas deterministas. Para
 * cualquier documento real, el sustituto es una implementacion interactiva de
 * {@link ReviewPort}.
 */
public final class AutoAcceptReview implements ReviewPort {

    @Override
    public List<Detection> review(String normalizedText, List<Detection> candidates) {
        return candidates;
    }
}
