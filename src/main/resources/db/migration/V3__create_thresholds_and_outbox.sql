-- Local copy of alert thresholds owned by the care-plan system (assumption A5).
-- A row with no patient_id is a program-wide default. A patient's own rows for a metric replace the defaults for
-- that metric (assumption A11).
CREATE TABLE thresholds (
    patient_id   TEXT,
    metric       TEXT     NOT NULL,
    severity     TEXT     NOT NULL,
    -- A breach is a value below `below` or above `above`. Either can be empty.
    below        SMALLINT,
    above        SMALLINT,
    CONSTRAINT thresholds_metric_check CHECK (metric IN ('SYSTOLIC', 'DIASTOLIC', 'PULSE')),
    CONSTRAINT thresholds_severity_check CHECK (severity IN ('WARNING', 'CRITICAL')),
    CONSTRAINT thresholds_limit_check CHECK (below IS NOT NULL OR above IS NOT NULL),
    -- NULLS NOT DISTINCT: at most one default row per metric and severity, too.
    CONSTRAINT thresholds_unique UNIQUE NULLS NOT DISTINCT (patient_id, metric, severity)
);

-- Events waiting to be sent to the message broker. Written in the same transaction as the readings they
-- describe, so an event exists if and only if its reading was stored.
CREATE TABLE outbox (
    id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id    UUID        NOT NULL UNIQUE,
    event_type  TEXT        NOT NULL,
    payload     JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at     TIMESTAMPTZ
);

-- The relay only ever looks for unsent events, oldest first. A partial index keeps that lookup small.
CREATE INDEX outbox_unsent_idx ON outbox (id) WHERE sent_at IS NULL;
