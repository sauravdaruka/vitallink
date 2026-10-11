package com.vitallink.readings.ingest;

import com.vitallink.readings.device.AuthenticatedDevice;
import com.vitallink.readings.ingest.ReadingResult.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.web.ErrorResponseException;

@Service
class ReadingIngestService {

    private static final Logger log = LoggerFactory.getLogger(ReadingIngestService.class);

    private final ReadingWriter writer;
    private final Clock clock;
    private final ReadingValidator validator;
    private final int maxBatchSize;
    private final Map<Status, Counter> resultCounters = new EnumMap<>(Status.class);
    private final Counter patientMismatches;

    ReadingIngestService(
            ReadingWriter writer,
            Clock clock,
            MeterRegistry meters,
            // The oldest reading we accept matches the partitions we keep ready, so the two can't drift apart.
            @Value("${readings.partitions.days-back}") int daysBack,
            @Value("${readings.ingest.max-clock-skew}") Duration maxClockSkew,
            @Value("${readings.ingest.max-batch-size}") int maxBatchSize) {
        this.writer = writer;
        this.clock = clock;
        this.validator = new ReadingValidator(Duration.ofDays(daysBack), maxClockSkew);
        this.maxBatchSize = maxBatchSize;
        for (Status status : Status.values()) {
            resultCounters.put(
                    status,
                    Counter.builder("readings.ingest.results")
                            .tag("status", status.name())
                            .register(meters));
        }
        this.patientMismatches = Counter.builder("readings.ingest.patient.mismatch")
                .description("Requests whose patientId did not match the device's patient")
                .register(meters);
    }

    List<ReadingResult> ingest(AuthenticatedDevice device, IngestRequest request) {
        // Permission first: a device that isn't allowed learns nothing about whether its request was valid.
        if (request.patientId() != null && !request.patientId().equals(device.patientId())) {
            patientMismatches.increment();
            // Only the device ID is logged: patient IDs are health data.
            log.warn("Device {} sent readings for a patient it is not assigned to", device.deviceId());
            throw problem(HttpStatus.FORBIDDEN, "this device is not assigned to that patient");
        }
        List<ReadingInput> readings = request.readings() == null ? List.of() : request.readings();
        if (readings.isEmpty()) {
            throw problem(HttpStatus.BAD_REQUEST, "readings must contain at least one reading");
        }
        if (readings.size() > maxBatchSize) {
            throw problem(HttpStatus.CONTENT_TOO_LARGE, "a batch can hold at most %d readings".formatted(maxBatchSize));
        }

        Instant now = clock.instant();
        ReadingResult[] results = new ReadingResult[readings.size()];
        Map<UUID, Integer> positionOfNewReading = new LinkedHashMap<>();
        List<ReadingInput> toStore = new ArrayList<>();
        for (int i = 0; i < readings.size(); i++) {
            ReadingInput reading = readings.get(i);
            Optional<String> problem = validator.problemWith(reading, now);
            if (problem.isPresent()) {
                results[i] = ReadingResult.rejected(reading.readingId(), problem.get());
            } else if (positionOfNewReading.containsKey(reading.readingId())) {
                results[i] = ReadingResult.duplicate(reading.readingId()); // the same reading twice in one batch
            } else {
                positionOfNewReading.put(reading.readingId(), i);
                toStore.add(reading);
            }
        }

        Set<UUID> stored = toStore.isEmpty() ? Set.of() : writer.insertNew(device, toStore);
        positionOfNewReading.forEach((readingId, i) -> results[i] =
                stored.contains(readingId) ? ReadingResult.accepted(readingId) : ReadingResult.duplicate(readingId));

        Arrays.stream(results)
                .forEach(result -> resultCounters.get(result.status()).increment());
        return List.of(results);
    }

    private static ErrorResponseException problem(HttpStatus status, String detail) {
        return new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), null);
    }
}
