-- Local copy of care-team assignments owned by the care-team system (assumption A5).
-- One coach per program for each patient (assumption A13).
CREATE TABLE patient_coaches (
    patient_id  TEXT NOT NULL,
    program     TEXT NOT NULL,
    coach_id    TEXT NOT NULL,
    PRIMARY KEY (patient_id, program)
);

-- An alert is a work item: one row per problem, kept up to date while it is unresolved.
CREATE TABLE alerts (
    id                  BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    patient_id          TEXT        NOT NULL,
    metric_type         TEXT        NOT NULL,
    severity            TEXT        NOT NULL,
    status              TEXT        NOT NULL DEFAULT 'OPEN',
    -- Who should act on it now. Empty means it waits in the unassigned queue (assumption A13).
    assigned_coach_id   TEXT,
    breach_count        INT         NOT NULL DEFAULT 1,
    latest_reason       TEXT        NOT NULL,
    latest_measured_at  TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_by         TEXT,
    resolved_at         TIMESTAMPTZ,
    CONSTRAINT alerts_severity_check CHECK (severity IN ('WARNING', 'CRITICAL')),
    CONSTRAINT alerts_status_check CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED'))
);

-- At most one unresolved alert per patient and metric (assumption A12). New breaches update it instead.
-- If two workers try to open one at the same moment, the database lets only one succeed.
CREATE UNIQUE INDEX alerts_one_unresolved_idx ON alerts (patient_id, metric_type) WHERE status <> 'RESOLVED';

-- Every breach, never updated: how an alert developed over time (a gradual rise or a sudden spike).
CREATE TABLE alert_breaches (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alert_id     BIGINT      NOT NULL REFERENCES alerts (id),
    -- The reading that broke the limits. No foreign key: readings are another domain.
    device_id    TEXT        NOT NULL,
    reading_id   UUID        NOT NULL,
    measured_at  TIMESTAMPTZ NOT NULL,
    systolic     SMALLINT    NOT NULL,
    diastolic    SMALLINT    NOT NULL,
    pulse        SMALLINT,
    severity     TEXT        NOT NULL,
    -- Includes the limits in force at the time, so later threshold changes don't rewrite history.
    reason       TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX alert_breaches_alert_idx ON alert_breaches (alert_id, measured_at);

-- Who was told about which alert, and when. Never updated.
CREATE TABLE notifications (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    alert_id    BIGINT      NOT NULL REFERENCES alerts (id),
    coach_id    TEXT        NOT NULL,
    kind        TEXT        NOT NULL,
    severity    TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT notifications_kind_check CHECK (kind IN ('OPENED', 'ESCALATED'))
);

-- Events the alert worker has already handled. The broker can deliver an event more than once.
CREATE TABLE processed_events (
    event_id      UUID        PRIMARY KEY,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
