package com.docanonymizer.adapter.gazetteer;

import com.docanonymizer.domain.port.GazetteerPort;
import com.docanonymizer.domain.service.CanonicalForm;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Diccionario de nombres de pila servido desde un recurso empaquetado.
 *
 * <p>Combina la lista espanola con nombres internacionales de fuentes abiertas.
 * Los recursos se generan durante el desarrollo; la carga es siempre offline.
 * La lista predeterminada es inmutable y se comparte entre pipelines.
 * Vease docs/gazetteer-sources.md para fuentes y regeneracion.
 */
public final class ResourceGazetteer implements GazetteerPort {

    private static final String DEFAULT_RESOURCE = "/gazetteer/nombres-es.txt";
    private static final String INTERNATIONAL_RESOURCE = "/gazetteer/nombres-intl.txt.gz";

    private static final class DefaultNames {
        private static final Set<String> NAMES = load(DEFAULT_RESOURCE, INTERNATIONAL_RESOURCE);
    }

    private final Set<String> givenNames;

    public ResourceGazetteer() {
        this.givenNames = DefaultNames.NAMES;
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

    private static Set<String> load(String... resourcePaths) {
        Set<String> names = new HashSet<>();
        for (String resourcePath : resourcePaths) {
            loadInto(names, resourcePath);
        }
        return Collections.unmodifiableSet(names);
    }

    private static void loadInto(Set<String> names, String resourcePath) {
        try (InputStream in = ResourceGazetteer.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Recurso no encontrado: " + resourcePath);
            }
            try (InputStream decoded = resourcePath.endsWith(".gz") ? new GZIPInputStream(in) : in;
                    BufferedReader reader =
                            new BufferedReader(new InputStreamReader(decoded, StandardCharsets.UTF_8))) {
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
    }
}
