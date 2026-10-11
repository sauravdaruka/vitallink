-- Local demo data (assumption A5). Production runs db/migration only, never db/seed.
-- The plain keys are listed in docs/runbook.md for local testing; only their hashes are stored.
-- Safe to run again: existing rows are left alone.
INSERT INTO devices (device_id, patient_id) VALUES
    ('bp-001', 'patient-001'),
    ('bp-002', 'patient-002'),
    ('bp-003', 'patient-003')
ON CONFLICT (device_id) DO NOTHING;

INSERT INTO device_api_keys (key_hash, device_id, revoked_at) VALUES
    (sha256('local-key-bp-001'::bytea), 'bp-001', NULL),
    (sha256('local-key-bp-002'::bytea), 'bp-002', NULL),
    (sha256('local-key-bp-003'::bytea), 'bp-003', NULL),
    -- A revoked key, to show that it is rejected.
    (sha256('local-key-bp-003-old'::bytea), 'bp-003', now())
ON CONFLICT (key_hash) DO NOTHING;
