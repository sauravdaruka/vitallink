package com.vitallink.readings.threshold;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * A copy of threshold rows in Redis, so ingest normally doesn't query PostgreSQL for them. One entry holds the
 * program defaults, and one entry per patient holds that patient's own limits (often none).
 *
 * <p>The cache only speeds things up. If Redis fails, callers read PostgreSQL instead, and Redis is skipped for a
 * few seconds so every request doesn't wait for it to time out. A change deletes the entry at once and again
 * through the outbox (see {@link ThresholdAdmin}), so a stale limit can't outlive a change.
 */
@Component
class ThresholdCache {

    private static final Logger log = LoggerFactory.getLogger(ThresholdCache.class);

    /** The version is part of the key, so a release that changes the stored format never reads the old one. */
    private static final String KEY_PREFIX = "thresholds:v1:";

    private static final Duration SKIP_AFTER_FAILURE = Duration.ofSeconds(5);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Clock clock;
    private final Duration ttl;
    private final Counter hits;
    private final Counter misses;
    private final Counter errors;
    private volatile Instant skipUntil = Instant.MIN;
    // Logged once when Redis starts failing and once when it recovers, not on every request.
    private final AtomicBoolean failing = new AtomicBoolean();

    ThresholdCache(
            StringRedisTemplate redis,
            JsonMapper json,
            Clock clock,
            MeterRegistry meters,
            @Value("${thresholds.cache.ttl}") Duration ttl) {
        this.redis = redis;
        this.json = json;
        this.clock = clock;
        this.ttl = ttl;
        this.hits = lookups(meters, "hit");
        this.misses = lookups(meters, "miss");
        this.errors = lookups(meters, "error");
    }

    /** The cache key for a patient's own limits, or for the program defaults when {@code patientId} is null. */
    static String keyFor(String patientId) {
        return KEY_PREFIX + (patientId == null ? "defaults" : "patient:" + patientId);
    }

    /** The cached rows for the given keys, in one round trip. Missing keys are left out; so is everything if Redis fails. */
    Map<String, List<Threshold>> getAll(List<String> keys) {
        if (skipping()) {
            return Map.of();
        }
        List<String> values;
        try {
            values = redis.opsForValue().multiGet(keys);
            recovered();
        } catch (RuntimeException e) {
            failed(e);
            return Map.of();
        }
        Map<String, List<Threshold>> found = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            String value = values == null ? null : values.get(i);
            if (value == null) {
                misses.increment();
                continue;
            }
            try {
                found.put(keys.get(i), List.of(json.readValue(value, Threshold[].class)));
                hits.increment();
            } catch (JacksonException e) {
                // Treated as a miss: the caller reads PostgreSQL and overwrites the bad entry.
                misses.increment();
                log.warn("Ignoring an unreadable cache entry {}", keys.get(i));
            }
        }
        return found;
    }

    void put(String key, List<Threshold> rows) {
        if (skipping()) {
            return;
        }
        try {
            redis.opsForValue().set(key, json.writeValueAsString(rows), ttl);
        } catch (RuntimeException e) {
            failed(e);
        }
    }

    /** Throws if Redis can't be reached, so a caller that must succeed (the outbox listener) is retried. */
    void evict(String patientId) {
        redis.delete(keyFor(patientId));
    }

    /** For callers that have a guaranteed delete behind them: a failure is logged, not thrown. */
    void evictQuietly(String patientId) {
        try {
            evict(patientId);
        } catch (RuntimeException e) {
            log.warn("Could not delete cached thresholds now; the outbox delete will retry", e);
        }
    }

    private boolean skipping() {
        return clock.instant().isBefore(skipUntil);
    }

    private void failed(RuntimeException e) {
        errors.increment();
        skipUntil = clock.instant().plus(SKIP_AFTER_FAILURE);
        if (failing.compareAndSet(false, true)) {
            log.warn("Threshold cache unavailable; reading thresholds from PostgreSQL until it recovers", e);
        }
    }

    private void recovered() {
        if (failing.compareAndSet(true, false)) {
            log.info("Threshold cache works again");
        }
    }

    private static Counter lookups(MeterRegistry meters, String result) {
        return Counter.builder("thresholds.cache.lookups")
                .description("Threshold cache lookups by result: hit, miss or error")
                .tag("result", result)
                .register(meters);
    }
}
