package com.docanonymizer.domain.service;

/**
 * El documento no se puede procesar con garantias, asi que el pipeline se detiene.
 *
 * <p>El mensaje describe la CAUSA tecnica y nunca incluye contenido del documento:
 * esta excepcion acaba en consola y en informes.
 */
public class DocumentNotProcessableException extends RuntimeException {

    public DocumentNotProcessableException(String message) {
        super(message);
    }
}
