package com.vitallink.readings.threshold;

import com.vitallink.readings.threshold.Threshold.Metric;
import com.vitallink.readings.threshold.Threshold.Severity;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class Thresholds {

    private final JdbcTemplate jdbc;

    Thresholds(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The patient's own limits plus the program defaults. {@link ThresholdEvaluator} decides which apply. */
    public List<Threshold> forPatient(String patientId) {
        return jdbc.query(
                """
                SELECT metric, severity, below, above, patient_id IS NOT NULL AS patient_specific
                FROM thresholds
                WHERE patient_id = ? OR patient_id IS NULL
                """,
                (row, i) -> new Threshold(
                        Metric.valueOf(row.getString("metric")),
                        Severity.valueOf(row.getString("severity")),
                        row.getObject("below", Integer.class),
                        row.getObject("above", Integer.class),
                        row.getBoolean("patient_specific")),
                patientId);
    }
}
