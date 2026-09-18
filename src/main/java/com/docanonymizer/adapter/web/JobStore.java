package com.docanonymizer.adapter.web;

import com.docanonymizer.domain.service.AnonymizationPipeline;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Almacen de analisis en curso, SOLO en memoria y con caducidad.
 *
 * <p>El analisis contiene el texto completo del documento en claro: es el artefacto mas
 * sensible del sistema, porque tiene todos los datos personales y ninguna de las
 * protecciones del PDF de origen. Por eso no toca el disco, no se serializa y muere con
 * el proceso.
 *
 * <p>El identificador es aleatorio de 128 bits: quien no lo tenga no puede pedir el
 * analisis de otro, que es el aislamiento minimo exigible incluso en un prototipo
 * monousuario.
 */
public final class JobStore {

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int MAX_JOBS = 32;

    private record Entry(AnonymizationPipeline.Analysis analysis, Instant expiresAt) {
        boolean isExpired(Instant now) {
            return now.isAfter(expiresAt);
        }
    }

    private final Map<String, Entry> jobs = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public String put(AnonymizationPipeline.Analysis analysis) {
        purgeExpired();
        if (jobs.size() >= MAX_JOBS) {
            evictOldest();
        }
        byte[] id = new byte[16];
        random.nextBytes(id);
        String jobId = HexFormat.of().formatHex(id);
        jobs.put(jobId, new Entry(analysis, Instant.now().plus(TTL)));
        return jobId;
    }

    public Optional<AnonymizationPipeline.Analysis> get(String jobId) {
        purgeExpired();
        Entry entry = jobs.get(jobId);
        return entry == null ? Optional.empty() : Optional.of(entry.analysis());
    }

    public void remove(String jobId) {
        jobs.remove(jobId);
    }

    public int size() {
        return jobs.size();
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        jobs.entrySet().removeIf(e -> e.getValue().isExpired(now));
    }

    private void evictOldest() {
        jobs.entrySet().stream()
                .min(Map.Entry.comparingByValue(
                        (a, b) -> a.expiresAt().compareTo(b.expiresAt())))
                .map(Map.Entry::getKey)
                .ifPresent(jobs::remove);
    }
}
