package com.vitallink.readings.query;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Read APIs: a patient's latest readings, a patient's readings in a time range, and a coach's alert queue. */
@RestController
class QueryController {

    // Page sizes and range are part of the API contract (README), so they live here rather than in config.
    private static final int LATEST_MAX_LIMIT = 100;
    private static final int RANGE_MAX_LIMIT = 500;
    private static final Duration RANGE_MAX_LENGTH = Duration.ofDays(90);
    private static final int COACH_ALERTS_MAX_LIMIT = 200;

    private final ReadingQueries readings;
    private final AlertQueries alerts;
    private final Clock clock;
    private final Duration latestWindow;

    QueryController(
            ReadingQueries readings,
            AlertQueries alerts,
            Clock clock,
            // "Latest" looks only as far back as ingest accepts readings, so it skips the older daily partitions.
            @Value("${readings.partitions.days-back}") int daysBack) {
        this.readings = readings;
        this.alerts = alerts;
        this.clock = clock;
        this.latestWindow = Duration.ofDays(daysBack);
    }

    @GetMapping("/v1/patients/{patientId}/readings/latest")
    ReadingList latest(@PathVariable String patientId, @RequestParam(defaultValue = "20") int limit) {
        requireLimit(limit, LATEST_MAX_LIMIT);
        return new ReadingList(readings.since(patientId, clock.instant().minus(latestWindow), limit));
    }

    @GetMapping("/v1/patients/{patientId}/readings")
    ReadingList between(
            @PathVariable String patientId,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "100") int limit) {
        requireLimit(limit, RANGE_MAX_LIMIT);
        if (!from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
        }
        if (Duration.between(from, to).compareTo(RANGE_MAX_LENGTH) > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "the range can be at most %d days".formatted(RANGE_MAX_LENGTH.toDays()));
        }
        return new ReadingList(readings.between(patientId, from, to, limit));
    }

    @GetMapping("/v1/coaches/{coachId}/alerts")
    AlertList coachQueue(@PathVariable String coachId, @RequestParam(defaultValue = "50") int limit) {
        requireLimit(limit, COACH_ALERTS_MAX_LIMIT);
        return new AlertList(alerts.forCoach(coachId, limit));
    }

    private static void requireLimit(int limit, int max) {
        if (limit < 1 || limit > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and %d".formatted(max));
        }
    }
}
