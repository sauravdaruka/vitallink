-- Local demo data (assumption A5). Production runs db/migration only, never db/seed.
-- Safe to run again: existing rows are left alone.

-- Program-wide defaults for blood pressure (the systolic critical limits are the brief's example).
INSERT INTO thresholds (patient_id, metric, severity, below, above) VALUES
    (NULL, 'SYSTOLIC',  'CRITICAL', 90,  180),
    (NULL, 'SYSTOLIC',  'WARNING',  100, 160),
    (NULL, 'DIASTOLIC', 'CRITICAL', 50,  110),
    (NULL, 'DIASTOLIC', 'WARNING',  60,  100),
    (NULL, 'PULSE',     'CRITICAL', 40,  130)
ON CONFLICT ON CONSTRAINT thresholds_unique DO NOTHING;

-- patient-002 has a stricter systolic plan set by their doctor; it replaces the systolic defaults only.
INSERT INTO thresholds (patient_id, metric, severity, below, above) VALUES
    ('patient-002', 'SYSTOLIC', 'CRITICAL', 90,  170),
    ('patient-002', 'SYSTOLIC', 'WARNING',  100, 150)
ON CONFLICT ON CONSTRAINT thresholds_unique DO NOTHING;
