-- Local copy of each patient's alert thresholds, owned by the care-plan system (assumptions A5, A11). It is kept up
-- to date from that system's ThresholdsChanged events.
CREATE TABLE thresholds (
    patient_id  TEXT     NOT NULL,
    metric      TEXT     NOT NULL,
    -- A breach is a value below `below` or above `above`. Either can be empty.
    below       SMALLINT,
    above       SMALLINT,
    PRIMARY KEY (patient_id, metric),
    CONSTRAINT thresholds_metric_check CHECK (metric IN ('SYSTOLIC', 'DIASTOLIC', 'PULSE')),
    CONSTRAINT thresholds_limit_check CHECK (below IS NOT NULL OR above IS NOT NULL)
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
