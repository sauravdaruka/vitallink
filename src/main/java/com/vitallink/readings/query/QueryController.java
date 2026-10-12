package com.vitallink.readings.query;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read APIs: a patient's latest readings, a patient's readings in a time range, and a coach's alert queue. */
@RestController
class QueryController {

    private static final Duration MAX_RANGE = Duration.ofDays(90);

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
        requireBetween("limit", limit, 1, 100);
        return new ReadingList(readings.since(patientId, clock.instant().minus(latestWindow), limit));
    }

    @GetMapping("/v1/patients/{patientId}/readings")
    ReadingList between(
            @PathVariable String patientId,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "100") int limit) {
        requireBetween("limit", limit, 1, 500);
        if (!from.isBefore(to)) {
            throw badRequest("from must be before to");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw badRequest("the range can be at most 90 days");
        }
        return new ReadingList(readings.between(patientId, from, to, limit));
    }

    @GetMapping("/v1/coaches/{coachId}/alerts")
    AlertList coachQueue(@PathVariable String coachId, @RequestParam(defaultValue = "50") int limit) {
        requireBetween("limit", limit, 1, 200);
        return new AlertList(alerts.forCoach(coachId, limit));
    }

    private static void requireBetween(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw badRequest("%s must be between %d and %d".formatted(name, min, max));
        }
    }

    private static ErrorResponseException badRequest(String detail) {
        return new ErrorResponseException(
                HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), null);
    }
}
