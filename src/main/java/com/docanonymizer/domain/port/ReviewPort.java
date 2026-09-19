package com.docanonymizer.domain.port;

import com.docanonymizer.domain.model.Detection;
import java.util.List;

/**
 * Punto donde entra la decision humana.
 *
 * <p>En el prototipo el adaptador acepta todo automaticamente, pero el paso existe como
 * puerto de primera clase y no como una bandera enterrada en el pipeline. Es una decision
 * de diseno consciente: la revision humana es lo que sostiene el argumento de seguridad
 * de este producto, asi que tiene que ser un punto de extension visible, no un detalle
 * que haya que recordar anadir despues.
 *
 * <p>La implementacion real recibe los candidatos, deja al operador aceptar, rechazar,
 * cambiar de tipo o anadir areas, y devuelve solo lo aprobado.
 */
public interface ReviewPort {

    List<Detection> review(String normalizedText, List<Detection> candidates);
}
