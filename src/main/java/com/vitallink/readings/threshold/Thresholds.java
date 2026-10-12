package com.vitallink.readings.threshold;

import com.vitallink.readings.threshold.Threshold.Metric;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class Thresholds {

    private final JdbcTemplate jdbc;
    private final ThresholdCache cache;

    Thresholds(JdbcTemplate jdbc, ThresholdCache cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    /** The patient's limits, from the cache when they are there (cache-aside). */
    public List<Threshold> forPatient(String patientId) {
        return cache.get(patientId).orElseGet(() -> {
            List<Threshold> thresholds = load(patientId);
            cache.put(patientId, thresholds);
            return thresholds;
        });
    }

    List<Threshold> load(String patientId) {
        return jdbc.query(
                "SELECT metric, below, above FROM thresholds WHERE patient_id = ? ORDER BY metric",
                (row, i) -> new Threshold(
                        Metric.valueOf(row.getString("metric")),
                        row.getObject("below", Integer.class),
                        row.getObject("above", Integer.class)),
                patientId);
    }
}
