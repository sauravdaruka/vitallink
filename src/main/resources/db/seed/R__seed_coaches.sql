-- Local demo data (assumption A5). Production runs db/migration only, never db/seed.
-- patient-003 has no coach on purpose, to show the unassigned queue.
INSERT INTO patient_coaches (patient_id, program, coach_id) VALUES
    ('patient-001', 'HYPERTENSION', 'coach-asha'),
    ('patient-002', 'HYPERTENSION', 'coach-ben')
ON CONFLICT (patient_id, program) DO NOTHING;
