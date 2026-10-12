package com.vitallink.readings.threshold;

import com.vitallink.readings.threshold.Threshold.Metric;
import com.vitallink.readings.threshold.Threshold.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class Thresholds {

    private final JdbcTemplate jdbc;
    private final ThresholdCache cache;

    Thresholds(JdbcTemplate jdbc, ThresholdCache cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    /**
     * The patient's own limits plus the program defaults. {@link ThresholdEvaluator} decides which apply. Both
     * come from the cache when they are there, so ingest usually doesn't touch PostgreSQL for this.
     */
    public List<Threshold> forPatient(String patientId) {
        Map<String, List<Threshold>> cached =
                cache.getAll(List.of(ThresholdCache.keyFor(null), ThresholdCache.keyFor(patientId)));
        List<Threshold> all = new ArrayList<>(cachedOrLoad(cached, null));
        all.addAll(cachedOrLoad(cached, patientId));
        return all;
    }

    /** One set of rows from PostgreSQL: a patient's own limits, or the program defaults when patientId is null. */
    List<Threshold> load(String patientId) {
        RowMapper<Threshold> toThreshold = (row, i) -> new Threshold(
                Metric.valueOf(row.getString("metric")),
                Severity.valueOf(row.getString("severity")),
                row.getObject("below", Integer.class),
                row.getObject("above", Integer.class),
                patientId != null);
        String columns = "SELECT metric, severity, below, above FROM thresholds";
        return patientId == null
                ? jdbc.query(columns + " WHERE patient_id IS NULL ORDER BY metric, severity", toThreshold)
                : jdbc.query(columns + " WHERE patient_id = ? ORDER BY metric, severity", toThreshold, patientId);
    }

    private List<Threshold> cachedOrLoad(Map<String, List<Threshold>> cached, String patientId) {
        String key = ThresholdCache.keyFor(patientId);
        List<Threshold> rows = cached.get(key);
        if (rows == null) {
            // An empty list is cached too: "this patient has no limits of their own" is worth remembering.
            rows = load(patientId);
            cache.put(key, rows);
        }
        return rows;
    }
}
