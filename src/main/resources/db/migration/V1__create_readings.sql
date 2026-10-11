-- One row per reading from a device. Partitioned by day on measured_at.
-- The app creates the daily partitions; see README, Scale assumptions.
CREATE TABLE readings (
    device_id    TEXT        NOT NULL,
    reading_id   UUID        NOT NULL,
    patient_id   TEXT        NOT NULL,
    metric_type  TEXT        NOT NULL,
    systolic     SMALLINT,
    diastolic    SMALLINT,
    pulse        SMALLINT,
    measured_at  TIMESTAMPTZ NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Also the duplicate check: a retry has the same device, reading ID and time.
    PRIMARY KEY (device_id, reading_id, measured_at),
    -- Every rule is named, so an error says which rule was broken.
    -- Ranges are wide on purpose: they stop impossible values from bugs, never a real extreme reading.
    CONSTRAINT readings_metric_type_check CHECK (metric_type IN ('BLOOD_PRESSURE')),
    CONSTRAINT readings_systolic_range_check CHECK (systolic BETWEEN 40 AND 300),
    CONSTRAINT readings_diastolic_range_check CHECK (diastolic BETWEEN 20 AND 200),
    CONSTRAINT readings_pulse_range_check CHECK (pulse BETWEEN 20 AND 250),
    -- Blood pressure needs both numbers, and systolic is always the higher one.
    CONSTRAINT readings_bp_values_check CHECK (
        metric_type <> 'BLOOD_PRESSURE'
        OR (systolic IS NOT NULL AND diastolic IS NOT NULL AND systolic > diastolic))
) PARTITION BY RANGE (measured_at);

-- "A patient's readings between two dates" and "latest readings".
CREATE INDEX readings_patient_measured_idx ON readings (patient_id, measured_at);

-- Safety net: catches readings for a day with no partition. Should stay empty.
CREATE TABLE readings_default PARTITION OF readings DEFAULT;
