package com.vitallink.readings.threshold;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * A copy of each patient's thresholds in Redis, so ingest normally doesn't query PostgreSQL for them. The cache only
 * speeds things up: if Redis fails, callers read PostgreSQL instead. Entries are deleted when the thresholds change
 * (see {@link ThresholdUpdates}); the expiry is only a safety net.
 */
@Component
class ThresholdCache {

    private static final Logger log = LoggerFactory.getLogger(ThresholdCache.class);

    private static final String KEY_PREFIX = "thresholds:patient:";

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Duration ttl;
    // Logged once when Redis starts failing and once when it recovers, not on every request.
    private final AtomicBoolean failing = new AtomicBoolean();

    ThresholdCache(StringRedisTemplate redis, JsonMapper json, @Value("${thresholds.cache.ttl}") Duration ttl) {
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
    }

    static String keyFor(String patientId) {
        return KEY_PREFIX + patientId;
    }

    /** Empty when the entry is missing or Redis can't be reached; the caller then reads PostgreSQL. */
    Optional<List<Threshold>> get(String patientId) {
        try {
            String value = redis.opsForValue().get(keyFor(patientId));
            recovered();
            return value == null ? Optional.empty() : Optional.of(List.of(json.readValue(value, Threshold[].class)));
        } catch (JacksonException e) {
            log.warn("Ignoring an unreadable cache entry for {}", keyFor(patientId));
            return Optional.empty();
        } catch (RuntimeException e) {
            failed(e);
            return Optional.empty();
        }
    }

    void put(String patientId, List<Threshold> thresholds) {
        try {
            redis.opsForValue().set(keyFor(patientId), json.writeValueAsString(thresholds), ttl);
        } catch (RuntimeException e) {
            failed(e);
        }
    }

    /** Throws if Redis can't be reached, so the listener that calls it is retried. */
    void evict(String patientId) {
        redis.delete(keyFor(patientId));
    }

    private void failed(RuntimeException e) {
        if (failing.compareAndSet(false, true)) {
            log.warn("Threshold cache unavailable; reading thresholds from PostgreSQL until it recovers", e);
        }
    }

    private void recovered() {
        if (failing.compareAndSet(true, false)) {
            log.info("Threshold cache works again");
        }
    }
}
