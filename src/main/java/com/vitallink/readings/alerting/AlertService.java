package com.vitallink.readings.alerting;

import com.vitallink.readings.ingest.ReadingBreached;
import java.sql.Timestamp;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a breach into an alert and notifies the patient's coach (assumptions A12, A13). One alert per breaching
 * reading: the alert's unique key is the reading's key, so an event delivered twice raises one alert and one
 * notification.
 */
@Service
class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    private final JdbcTemplate jdbc;

    AlertService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The alert and its notification are saved together, or not at all; a failure is retried from scratch. */
    @Transactional
    void handle(ReadingBreached event) {
        String coachId = coachOf(event.patientId());
        List<Long> created = jdbc.queryForList(
                """
                INSERT INTO alerts (device_id, reading_id, measured_at, patient_id, systolic, diastolic, pulse,
                                    reason, coach_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (device_id, reading_id, measured_at) DO NOTHING
                RETURNING id
                """,
                Long.class,
                event.deviceId(),
                event.readingId(),
                Timestamp.from(event.measuredAt()),
                event.patientId(),
                event.systolic(),
                event.diastolic(),
                event.pulse(),
                event.reason(),
                coachId);
        if (created.isEmpty()) {
            log.debug("Alert for event {} already exists; skipping the repeat", event.eventId());
            return;
        }
        long alertId = created.getFirst();
        if (coachId == null) {
            // Never dropped: the alert is stored, and the log shows a patient needs a coach.
            log.warn("Alert {} has no coach to notify: the patient has no coach assigned", alertId);
            return;
        }
        // Recording the notification stands in for a real push or SMS, which this exercise doesn't need.
        jdbc.update("INSERT INTO notifications (alert_id, coach_id) VALUES (?, ?)", alertId, coachId);
        log.info("Notified {} about alert {}", coachId, alertId);
    }

    private String coachOf(String patientId) {
        List<String> coaches =
                jdbc.queryForList("SELECT coach_id FROM patient_coaches WHERE patient_id = ?", String.class, patientId);
        return coaches.isEmpty() ? null : coaches.getFirst();
    }
}
