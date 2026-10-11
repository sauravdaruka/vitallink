-- Local copy of device data owned by the device-management system (assumption A5).
CREATE TABLE devices (
    device_id   TEXT    PRIMARY KEY,
    -- The patient using the device now. Changes when a device is reassigned.
    patient_id  TEXT    NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT true
);

-- Only a SHA-256 hash of each key is stored, so a database leak does not expose usable keys.
-- To rotate a key: add the new one, let the device switch, then revoke the old one.
CREATE TABLE device_api_keys (
    key_hash    BYTEA       PRIMARY KEY,
    device_id   TEXT        NOT NULL REFERENCES devices (device_id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMPTZ
);

CREATE INDEX device_api_keys_device_idx ON device_api_keys (device_id);
