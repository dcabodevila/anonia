package com.docanonymizer.domain.port;

/**
 * Diccionario local de nombres de pila.
 *
 * <p>En el prototipo se sirve desde un recurso empaquetado. En produccion se
 * sustituye por el listado completo del INE, cargado desde un fichero aprobado y
 * versionado: mismo puerto, sin tocar el dominio.
 */
public interface GazetteerPort {

    /** Comparacion insensible a mayusculas y a acentos. */
    boolean isGivenName(String token);

    int size();
}
