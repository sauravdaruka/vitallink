package com.vitallink.readings.common;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * No database connection was free within the pool's timeout, so the database is the bottleneck. Telling clients
 * to back off and retry later is better than letting requests pile up and making things worse.
 */
@RestControllerAdvice
class DatabaseBusyHandler {

    @ExceptionHandler(CannotGetJdbcConnectionException.class)
    ResponseEntity<ProblemDetail> databaseBusy() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "2")
                .body(ProblemDetail.forStatusAndDetail(
                        HttpStatus.SERVICE_UNAVAILABLE, "The service is busy. Retry after a short, random delay."));
    }
}
