package com.vitallink.readings.query;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class AlertQueries {

    private final JdbcTemplate jdbc;

    AlertQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The coach's alerts, newest first, found through the {@code (coach_id, created_at)} index. */
    List<AlertView> forCoach(String coachId, int limit) {
        return jdbc.query(
                """
                SELECT id, patient_id, systolic, diastolic, pulse, reason, measured_at, created_at
                FROM alerts
                WHERE coach_id = ?
                ORDER BY created_at DESC
                LIMIT ?
                """,
                (row, i) -> new AlertView(
                        row.getLong("id"),
                        row.getString("patient_id"),
                        row.getObject("systolic", Integer.class),
                        row.getObject("diastolic", Integer.class),
                        row.getObject("pulse", Integer.class),
                        row.getString("reason"),
                        row.getTimestamp("measured_at").toInstant(),
                        row.getTimestamp("created_at").toInstant()),
                coachId,
                limit);
    }
}
