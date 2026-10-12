package com.vitallink.readings.threshold;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.readings.TestcontainersConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ThresholdCacheTest {

    @Autowired
    private Thresholds thresholds;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper json;

    @Test
    void aLookupFillsTheCacheForADayAndLaterLookupsAreServedFromIt() {
        String patientId = "patient-" + UUID.randomUUID();

        assertThat(thresholds.forPatient(patientId)).isNotEmpty().noneMatch(Threshold::patientSpecific);

        String key = ThresholdCache.keyFor(patientId);
        assertThat(redis.opsForValue().get(key)).isEqualTo("[]"); // "no limits of their own" is cached too
        assertThat(redis.getExpire(key)).isGreaterThan(Duration.ofHours(23).toSeconds());

        // Written straight to the database, so nothing deletes the cached copy. The cache keeps answering, which is
        // why limits must only be changed through the admin API.
        jdbc.update(
                "INSERT INTO thresholds (patient_id, metric, severity, above) VALUES (?, 'SYSTOLIC', 'WARNING', 140)",
                patientId);
        assertThat(thresholds.forPatient(patientId)).noneMatch(Threshold::patientSpecific);
    }

    @Test
    void whenRedisIsDownThresholdsComeFromPostgres() throws IOException {
        LettuceConnectionFactory unreachable = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", unusedPort()),
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(200))
                        .build());
        unreachable.afterPropertiesSet();
        unreachable.start();
        try {
            ThresholdCache cache = new ThresholdCache(
                    new StringRedisTemplate(unreachable),
                    json,
                    Clock.systemUTC(),
                    new SimpleMeterRegistry(),
                    Duration.ofHours(24));
            Thresholds withoutRedis = new Thresholds(jdbc, cache);

            List<Threshold> limits = withoutRedis.forPatient("patient-002");

            // patient-002's own systolic limits plus the defaults, as seeded.
            assertThat(limits).anyMatch(t -> t.patientSpecific() && t.metric() == Threshold.Metric.SYSTOLIC);
            assertThat(limits).anyMatch(t -> !t.patientSpecific() && t.metric() == Threshold.Metric.DIASTOLIC);
        } finally {
            unreachable.destroy();
        }
    }

    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
