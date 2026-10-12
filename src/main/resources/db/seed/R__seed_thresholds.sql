-- Local demo data (assumption A5). Production runs db/migration only, never db/seed.
-- Safe to run again: existing rows are left alone.
-- Every patient has their own limits; patient-002's doctor set a stricter systolic limit (170 instead of 180).
INSERT INTO thresholds (patient_id, metric, below, above) VALUES
    ('patient-001', 'SYSTOLIC',  90, 180),
    ('patient-001', 'DIASTOLIC', 50, 110),
    ('patient-001', 'PULSE',     40, 130),
    ('patient-002', 'SYSTOLIC',  90, 170),
    ('patient-002', 'DIASTOLIC', 50, 110),
    ('patient-002', 'PULSE',     40, 130),
    ('patient-003', 'SYSTOLIC',  90, 180),
    ('patient-003', 'DIASTOLIC', 50, 110),
    ('patient-003', 'PULSE',     40, 130)
ON CONFLICT (patient_id, metric) DO NOTHING;
