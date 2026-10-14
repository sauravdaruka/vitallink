package com.vitallink.readings.summary;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class SummaryController {

    private static final int MAX_DAYS = 30;

    private final SummaryService summaries;

    SummaryController(SummaryService summaries) {
        this.summaries = summaries;
    }

    /** No login in this exercise, like the other read APIs (README A15). */
    @GetMapping("/v1/patients/{patientId}/summary")
    ReadingSummary summary(@PathVariable String patientId, @RequestParam(defaultValue = "7") int days) {
        if (days < 1 || days > MAX_DAYS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "days must be between 1 and %d".formatted(MAX_DAYS));
        }
        return summaries.summarize(patientId, days);
    }
}
