package com.vitallink.readings.summary;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SummaryController {

    private final SummaryService summaries;

    SummaryController(SummaryService summaries) {
        this.summaries = summaries;
    }

    /** No login in this exercise, like the other read APIs (README A15). */
    @GetMapping("/v1/patients/{patientId}/summary")
    ReadingSummary summary(@PathVariable String patientId, @RequestParam(defaultValue = "7") int days) {
        if (days < 1 || days > 30) {
            throw new ErrorResponseException(
                    HttpStatus.BAD_REQUEST,
                    ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "days must be between 1 and 30"),
                    null);
        }
        return summaries.summarize(patientId, days);
    }
}
