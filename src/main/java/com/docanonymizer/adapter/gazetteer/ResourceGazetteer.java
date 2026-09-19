package com.docanonymizer.adapter.gazetteer;

import com.docanonymizer.domain.port.GazetteerPort;
import com.docanonymizer.domain.service.CanonicalForm;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Diccionario de nombres de pila servido desde un recurso empaquetado.
 *
 * <p>La lista incluida es CORTA a proposito: es la de un prototipo, suficiente para
 * demostrar el mecanismo y para escribir pruebas deterministas, no para produccion. El
 * sustituto natural es el listado de nombres del INE, que es publico, descargable y
 * offline, y por tanto compatible con el requisito de no salir a la red.
 *
 * <p>Cambiarlo no toca el dominio: es otra implementacion de {@link GazetteerPort}.
 */
public final class ResourceGazetteer implements GazetteerPort {

    private static final String DEFAULT_RESOURCE = "/gazetteer/nombres-es.txt";

    private final Set<String> givenNames;

    public ResourceGazetteer() {
        this(DEFAULT_RESOURCE);
    }

    public ResourceGazetteer(String resourcePath) {
        this.givenNames = load(resourcePath);
    }

    @Override
    public boolean isGivenName(String token) {
        return givenNames.contains(CanonicalForm.forCompare(token));
    }

    @Override
    public int size() {
        return givenNames.size();
    }

    private Set<String> load(String resourcePath) {
        Set<String> names = new HashSet<>();
        try (InputStream in = ResourceGazetteer.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Recurso no encontrado: " + resourcePath);
            }
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.strip();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    names.add(CanonicalForm.forCompare(trimmed));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer el diccionario de nombres", e);
        }
        return Set.copyOf(names);
    }
}
