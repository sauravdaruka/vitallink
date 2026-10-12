-- Local demo data (assumption A5). Production runs db/migration only, never db/seed.
-- patient-003 has no coach on purpose: its alerts are stored, but nobody is notified.
INSERT INTO patient_coaches (patient_id, coach_id) VALUES
    ('patient-001', 'coach-asha'),
    ('patient-002', 'coach-ben')
ON CONFLICT (patient_id) DO NOTHING;
