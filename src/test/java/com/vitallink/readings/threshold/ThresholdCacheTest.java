package com.vitallink.readings.threshold;

import static org.assertj.core.api.Assertions.assertThat;

import com.vitallink.readings.TestcontainersConfiguration;
import java.io.IOException;
import java.net.ServerSocket;
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
        jdbc.update("INSERT INTO thresholds (patient_id, metric, above) VALUES (?, 'SYSTOLIC', 180)", patientId);

        assertThat(thresholds.forPatient(patientId))
                .extracting(Threshold::above)
                .containsExactly(180);

        String key = ThresholdCache.keyFor(patientId);
        assertThat(redis.getExpire(key)).isGreaterThan(Duration.ofHours(23).toSeconds());

        // Changed straight in the database, so nothing deletes the cached copy: the cache keeps answering. That's
        // why changes arrive as ThresholdsChanged events, which delete it (see ThresholdUpdatesTest).
        jdbc.update("UPDATE thresholds SET above = 150 WHERE patient_id = ?", patientId);
        assertThat(thresholds.forPatient(patientId))
                .extracting(Threshold::above)
                .containsExactly(180);
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
            Thresholds withoutRedis = new Thresholds(
                    jdbc, new ThresholdCache(new StringRedisTemplate(unreachable), json, Duration.ofHours(24)));

            List<Threshold> limits = withoutRedis.forPatient("patient-002");

            assertThat(limits).contains(new Threshold(Threshold.Metric.SYSTOLIC, 90, 170)); // as seeded
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
