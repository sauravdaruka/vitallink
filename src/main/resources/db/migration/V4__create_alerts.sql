-- Local copy of which coach looks after which patient, owned by the care-team system (assumptions A5, A13).
CREATE TABLE patient_coaches (
    patient_id  TEXT PRIMARY KEY,
    coach_id    TEXT NOT NULL
);

-- One alert per reading that broke a threshold (assumption A12).
CREATE TABLE alerts (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- The reading that broke the limits. No foreign key: readings are another domain, and old reading partitions
    -- must be detachable.
    device_id    TEXT        NOT NULL,
    reading_id   UUID        NOT NULL,
    measured_at  TIMESTAMPTZ NOT NULL,
    patient_id   TEXT        NOT NULL,
    systolic     SMALLINT,
    diastolic    SMALLINT,
    pulse        SMALLINT,
    -- Includes the limits in force at the time, so later threshold changes don't rewrite history.
    reason       TEXT        NOT NULL,
    -- The patient's coach when the alert was raised; empty if the patient has no coach.
    coach_id     TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- A reading raises at most one alert, even when its event is delivered more than once.
    CONSTRAINT alerts_reading_unique UNIQUE (device_id, reading_id, measured_at)
);

-- A coach's alert queue, newest first.
CREATE INDEX alerts_coach_idx ON alerts (coach_id, created_at);

-- Who was told about which alert, and when. Stands in for a real push or SMS.
CREATE TABLE notifications (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alert_id    BIGINT      NOT NULL REFERENCES alerts (id),
    coach_id    TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
