package com.vitallink.readings.threshold;

import com.vitallink.readings.outbox.Outbox;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.ErrorResponseException;

/**
 * Replaces a set of limits: the program defaults, or one patient's own limits. The cached copy is deleted twice:
 * right after the change is committed, so the next reading uses the new limits, and again through the outbox,
 * which is retried until it succeeds (assumption A14).
 */
@Service
class ThresholdAdmin {

    private static final Logger log = LoggerFactory.getLogger(ThresholdAdmin.class);

    /** Matches the database column (SMALLINT) and is far beyond any real blood pressure or pulse. */
    private static final int MAX_VALUE = 300;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Outbox outbox;
    private final Thresholds thresholds;
    private final ThresholdCache cache;
    private final Clock clock;

    ThresholdAdmin(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            Outbox outbox,
            Thresholds thresholds,
            ThresholdCache cache,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.outbox = outbox;
        this.thresholds = thresholds;
        this.cache = cache;
        this.clock = clock;
    }

    /** @param patientId whose own limits to replace, or null for the program defaults */
    List<Threshold> replace(String patientId, List<ThresholdLimit> limits) {
        validate(patientId, limits);
        transactions.executeWithoutResult(transaction -> {
            // Two admins changing the same limits at once take turns instead of failing on the unique constraint.
            jdbc.query(
                    "SELECT pg_advisory_xact_lock(hashtext(?))",
                    (ResultSetExtractor<Void>) rows -> null,
                    ThresholdCache.keyFor(patientId));
            if (patientId == null) {
                jdbc.update("DELETE FROM thresholds WHERE patient_id IS NULL");
            } else {
                jdbc.update("DELETE FROM thresholds WHERE patient_id = ?", patientId);
            }
            for (ThresholdLimit limit : limits) {
                jdbc.update(
                        "INSERT INTO thresholds (patient_id, metric, severity, below, above) VALUES (?, ?, ?, ?, ?)",
                        patientId,
                        limit.metric().name(),
                        limit.severity().name(),
                        limit.below(),
                        limit.above());
            }
            UUID eventId = UUID.randomUUID();
            outbox.add(eventId, ThresholdsChanged.TYPE, new ThresholdsChanged(eventId, patientId, clock.instant()));
        });
        // After the commit, never before: deleting earlier lets a request reload the old limits into the cache.
        cache.evictQuietly(patientId);
        // Only the scope is logged, not the patient ID: patient IDs are health data.
        log.info("Thresholds replaced for {}", patientId == null ? "the program defaults" : "one patient");
        return thresholds.load(patientId);
    }

    private static void validate(String patientId, List<ThresholdLimit> limits) {
        if (limits == null) {
            throw badRequest("thresholds is required");
        }
        if (patientId == null && limits.isEmpty()) {
            // A patient without their own limits would then get no alerts at all.
            throw badRequest("the program defaults can't be empty");
        }
        Set<String> seen = new HashSet<>();
        for (ThresholdLimit limit : limits) {
            if (limit == null || limit.metric() == null || limit.severity() == null) {
                throw badRequest("every threshold needs a metric and a severity");
            }
            String name = limit.metric() + " " + limit.severity();
            if (!seen.add(name)) {
                throw badRequest(name + " appears more than once");
            }
            if (limit.below() == null && limit.above() == null) {
                throw badRequest(name + " needs a below or an above limit");
            }
            if (outOfRange(limit.below()) || outOfRange(limit.above())) {
                throw badRequest(name + " limits must be between 0 and " + MAX_VALUE);
            }
            if (limit.below() != null && limit.above() != null && limit.below() >= limit.above()) {
                throw badRequest(name + " below must be lower than above");
            }
        }
    }

    private static boolean outOfRange(Integer value) {
        return value != null && (value < 0 || value > MAX_VALUE);
    }

    private static ErrorResponseException badRequest(String detail) {
        return new ErrorResponseException(
                HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), null);
    }
}
