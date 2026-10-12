package com.vitallink.readings.alerting;

import com.vitallink.readings.ingest.ReadingBreached;
import com.vitallink.readings.threshold.Threshold.Severity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a breach into an alert. One unresolved alert per patient and metric: the first breach opens it, later ones
 * update it, and every breach is kept as history. The coach is notified only when the alert opens or gets more
 * severe (assumption A12).
 */
@Service
class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);

    /** Which care program a metric belongs to, to find the right coach (assumption A13). */
    private static final Map<String, String> PROGRAM_FOR_METRIC = Map.of("BLOOD_PRESSURE", "HYPERTENSION");

    private enum Notify {
        OPENED,
        ESCALATED
    }

    private record OpenAlert(long id, Severity severity, String coachId) {}

    private final JdbcTemplate jdbc;
    private final Counter duplicateEvents;
    private final Counter unassignedAlerts;

    AlertService(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.duplicateEvents = Counter.builder("alerts.events.duplicate")
                .description("Events delivered more than once and skipped")
                .register(meters);
        this.unassignedAlerts = Counter.builder("alerts.unassigned")
                .description("Alerts that opened or escalated with no coach to notify")
                .register(meters);
    }

    /**
     * Everything here is one transaction, including recording the event as processed. If anything fails, it all
     * rolls back and the retry starts clean.
     */
    @Transactional
    void handle(ReadingBreached event) {
        if (alreadyProcessed(event)) {
            duplicateEvents.increment();
            return;
        }
        OpenAlert alert = findUnresolvedForUpdate(event.patientId(), event.metricType());
        Notify notify = null;
        if (alert == null) {
            // If another worker opens one for this patient at the same moment, the unique index rejects this
            // insert, the transaction rolls back, and the retry finds and updates that alert instead.
            alert = open(event);
            notify = Notify.OPENED;
        } else {
            boolean worse = event.severity().compareTo(alert.severity()) > 0;
            update(alert, event, worse);
            if (worse) {
                notify = Notify.ESCALATED;
                alert = new OpenAlert(alert.id(), event.severity(), alert.coachId());
            }
        }
        recordBreach(alert.id(), event);
        if (notify != null) {
            notifyCoach(alert, notify);
        }
    }

    private boolean alreadyProcessed(ReadingBreached event) {
        return jdbc.update("INSERT INTO processed_events (event_id) VALUES (?) ON CONFLICT DO NOTHING", event.eventId())
                == 0;
    }

    /** FOR UPDATE: two breaches for the same patient are applied one after the other, never interleaved. */
    private OpenAlert findUnresolvedForUpdate(String patientId, String metricType) {
        List<OpenAlert> found = jdbc.query(
                """
                SELECT id, severity, assigned_coach_id
                FROM alerts
                WHERE patient_id = ? AND metric_type = ? AND status <> 'RESOLVED'
                FOR UPDATE
                """,
                (row, i) -> new OpenAlert(
                        row.getLong("id"),
                        Severity.valueOf(row.getString("severity")),
                        row.getString("assigned_coach_id")),
                patientId,
                metricType);
        return found.isEmpty() ? null : found.getFirst();
    }

    private OpenAlert open(ReadingBreached event) {
        String coachId = currentCoach(event.patientId(), event.metricType());
        Long id = jdbc.queryForObject(
                """
                INSERT INTO alerts (patient_id, metric_type, severity, assigned_coach_id, latest_reason, latest_measured_at)
                VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                event.patientId(),
                event.metricType(),
                event.severity().name(),
                coachId,
                event.reason(),
                Timestamp.from(event.measuredAt()));
        return new OpenAlert(id, event.severity(), coachId);
    }

    /**
     * Severity only goes up while the alert is open; a calmer reading doesn't close it, a coach does. Readings can
     * arrive out of order (a device uploading late), so "latest" only moves forward in time.
     */
    private void update(OpenAlert alert, ReadingBreached event, boolean worse) {
        Timestamp measuredAt = Timestamp.from(event.measuredAt());
        jdbc.update("""
                UPDATE alerts
                SET breach_count = breach_count + 1,
                    severity = CASE WHEN ? THEN ? ELSE severity END,
                    latest_reason = CASE WHEN ? > latest_measured_at THEN ? ELSE latest_reason END,
                    latest_measured_at = GREATEST(latest_measured_at, ?),
                    updated_at = now()
                WHERE id = ?
                """, worse, event.severity().name(), measuredAt, event.reason(), measuredAt, alert.id());
    }

    private void recordBreach(long alertId, ReadingBreached event) {
        jdbc.update(
                """
                INSERT INTO alert_breaches
                    (alert_id, device_id, reading_id, measured_at, systolic, diastolic, pulse, severity, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                alertId,
                event.deviceId(),
                event.readingId(),
                Timestamp.from(event.measuredAt()),
                event.systolic(),
                event.diastolic(),
                event.pulse(),
                event.severity().name(),
                event.reason());
    }

    /** Looked up when the alert opens, so it goes to whoever is the patient's coach now. */
    private String currentCoach(String patientId, String metricType) {
        List<String> coaches = jdbc.queryForList(
                "SELECT coach_id FROM patient_coaches WHERE patient_id = ? AND program = ?",
                String.class,
                patientId,
                PROGRAM_FOR_METRIC.getOrDefault(metricType, metricType));
        return coaches.isEmpty() ? null : coaches.getFirst();
    }

    /** Recording the notification stands in for a real push or SMS, which this exercise doesn't need. */
    private void notifyCoach(OpenAlert alert, Notify kind) {
        if (alert.coachId() == null) {
            unassignedAlerts.increment();
            log.warn("Alert {} ({}) has no coach; it is waiting in the unassigned queue", alert.id(), kind);
            return;
        }
        jdbc.update(
                "INSERT INTO notifications (alert_id, coach_id, kind, severity) VALUES (?, ?, ?, ?)",
                alert.id(),
                alert.coachId(),
                kind.name(),
                alert.severity().name());
        log.info("Notified {}: alert {} {} ({})", alert.coachId(), alert.id(), kind, alert.severity());
    }
}
