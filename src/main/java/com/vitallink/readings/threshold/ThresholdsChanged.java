package com.vitallink.readings.threshold;

import java.time.Instant;
import java.util.UUID;

/**
 * Saved to the outbox with every threshold change, so the cached copy is deleted even if the app crashes or Redis
 * is down at the moment of the change.
 *
 * @param patientId whose own limits changed, or null when the program defaults changed
 */
record ThresholdsChanged(UUID eventId, String patientId, Instant changedAt) {

    static final String TYPE = "ThresholdsChanged";
}
