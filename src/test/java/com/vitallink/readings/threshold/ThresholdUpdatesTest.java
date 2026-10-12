package com.vitallink.readings.threshold;

import static org.awaitility.Awaitility.await;

import com.vitallink.readings.TestcontainersConfiguration;
import com.vitallink.readings.threshold.Threshold.Metric;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Plays the care-plan system: publishes a ThresholdsChanged event and checks the copy and the cache follow. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ThresholdUpdatesTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    private Thresholds thresholds;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JsonMapper json;

    private String patientId;

    @BeforeEach
    void aPatientWithCachedLimits() {
        patientId = "patient-" + UUID.randomUUID();
        jdbc.update("INSERT INTO thresholds (patient_id, metric, above) VALUES (?, 'SYSTOLIC', 180)", patientId);
        thresholds.forPatient(patientId); // now cached
    }

    @Test
    void anUpdateReplacesTheLocalCopyAndTheNextLookupSeesIt() {
        publish(new ThresholdsChanged(patientId, List.of(new Threshold(Metric.SYSTOLIC, 90, 150))));

        await().atMost(WAIT)
                .until(() -> thresholds.forPatient(patientId).equals(List.of(new Threshold(Metric.SYSTOLIC, 90, 150))));
    }

    @Test
    void anOldCopyWrittenJustAfterTheUpdateIsRemovedByTheSecondDelete() {
        publish(new ThresholdsChanged(patientId, List.of(new Threshold(Metric.SYSTOLIC, 90, 150))));
        String key = ThresholdCache.keyFor(patientId);
        await().atMost(WAIT).until(() -> !redis.hasKey(key)); // the first delete happened

        // A slow request that read the limits before the update writes them back after the first delete.
        redis.opsForValue().set(key, "[{\"metric\":\"SYSTOLIC\",\"below\":null,\"above\":180}]");

        await().atMost(WAIT).until(() -> !redis.hasKey(key));
    }

    private void publish(ThresholdsChanged event) {
        rabbit.send(
                ThresholdUpdatesQueueConfig.CAREPLAN_EXCHANGE,
                ThresholdsChanged.TYPE,
                MessageBuilder.withBody(json.writeValueAsBytes(event))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .build());
    }
}
