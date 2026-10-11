package com.vitallink.readings.ingest;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

/** The outcome for one reading. Results are returned in the same order as the readings in the request. */
@JsonInclude(JsonInclude.Include.NON_NULL)
record ReadingResult(UUID readingId, Status status, String reason) {

    enum Status {
        /** Stored now. */
        ACCEPTED,
        /** Already stored earlier, or sent twice in this batch. Safe for the device to delete. */
        DUPLICATE,
        /** Invalid and not stored. Retrying the same reading will not help. */
        REJECTED
    }

    static ReadingResult accepted(UUID readingId) {
        return new ReadingResult(readingId, Status.ACCEPTED, null);
    }

    static ReadingResult duplicate(UUID readingId) {
        return new ReadingResult(readingId, Status.DUPLICATE, null);
    }

    static ReadingResult rejected(UUID readingId, String reason) {
        return new ReadingResult(readingId, Status.REJECTED, reason);
    }
}
