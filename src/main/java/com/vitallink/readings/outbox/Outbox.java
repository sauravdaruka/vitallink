package com.vitallink.readings.outbox;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Saves events to send to the message broker. A relay sends them later and retries until the broker confirms. */
@Repository
public class Outbox {

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    Outbox(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Must run inside the caller's transaction, together with the data the event describes: then the event exists
     * if and only if that data was saved.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(UUID eventId, String eventType, Object payload) {
        jdbc.update(
                "INSERT INTO outbox (event_id, event_type, payload) VALUES (?, ?, ?::jsonb)",
                eventId,
                eventType,
                json.writeValueAsString(payload));
    }
}
