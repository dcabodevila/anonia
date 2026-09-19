package com.docanonymizer.domain.service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Identificador opaco y estable dentro de una ejecucion, para poder referirse a una
 * entidad en el informe sin escribir su valor.
 *
 * <p>Usa HMAC con una clave aleatoria por ejecucion, no un hash a secas. La diferencia
 * importa: el espacio de busqueda de un DNI o de un nombre es diminuto, asi que un
 * SHA-256 desnudo del valor se revierte por fuerza bruta en segundos y el informe
 * "sin datos personales" se convierte en un fichero de datos personales. La clave vive
 * solo en memoria y muere con el proceso, de modo que los identificadores no son
 * correlacionables entre ejecuciones.
 */
public final class RunScopedIdentifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int HEX_LENGTH = 8;

    private final Mac mac;

    public RunScopedIdentifier() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        try {
            this.mac = Mac.getInstance(ALGORITHM);
            this.mac.init(new SecretKeySpec(key, ALGORITHM));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo inicializar " + ALGORITHM, e);
        }
    }

    public synchronized String of(String value) {
        byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(HEX_LENGTH);
        for (int i = 0; i < HEX_LENGTH / 2; i++) {
            out.append(String.format("%02x", digest[i]));
        }
        return out.toString();
    }
}
