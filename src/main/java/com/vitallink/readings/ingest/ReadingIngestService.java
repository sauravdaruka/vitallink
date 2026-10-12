package com.vitallink.readings.ingest;

import com.vitallink.readings.device.AuthenticatedDevice;
import com.vitallink.readings.outbox.Outbox;
import com.vitallink.readings.threshold.Threshold;
import com.vitallink.readings.threshold.ThresholdEvaluator;
import com.vitallink.readings.threshold.Thresholds;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.ErrorResponseException;

@Service
class ReadingIngestService {

    private static final Logger log = LoggerFactory.getLogger(ReadingIngestService.class);

    private final ReadingWriter writer;
    private final Thresholds thresholds;
    private final Outbox outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ReadingValidator validator;
    private final int maxBatchSize;

    ReadingIngestService(
            ReadingWriter writer,
            Thresholds thresholds,
            Outbox outbox,
            TransactionTemplate transactions,
            Clock clock,
            // The oldest reading we accept matches the partitions we keep ready, so the two can't drift apart.
            @Value("${readings.partitions.days-back}") int daysBack,
            @Value("${readings.ingest.max-batch-size}") int maxBatchSize) {
        this.writer = writer;
        this.thresholds = thresholds;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
        this.validator = new ReadingValidator(Duration.ofDays(daysBack));
        this.maxBatchSize = maxBatchSize;
    }

    List<ReadingResult> ingest(AuthenticatedDevice device, IngestRequest request) {
        // Permission first: a device that isn't allowed learns nothing about whether its request was valid.
        if (request.patientId() != null && !request.patientId().equals(device.patientId())) {
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

        Set<UUID> stored = toStore.isEmpty() ? Set.of() : storeAndRaiseEvents(device, toStore);
        positionOfNewReading.forEach((readingId, i) -> results[i] =
                stored.contains(readingId) ? ReadingResult.accepted(readingId) : ReadingResult.duplicate(readingId));

        return List.of(results);
    }

    /**
     * Stores the new readings and, in the same transaction, an outbox event for each new one that breaks a
     * threshold. A duplicate is never stored again, so a retry can never raise a second event.
     */
    private Set<UUID> storeAndRaiseEvents(AuthenticatedDevice device, List<ReadingInput> readings) {
        // Looked up before the transaction starts, so the transaction stays short.
        List<Threshold> limits = thresholds.forPatient(device.patientId());
        return transactions.execute(transaction -> {
            Set<UUID> stored = writer.insertNew(device, readings);
            for (ReadingInput reading : readings) {
                if (!stored.contains(reading.readingId())) {
                    continue;
                }
                ThresholdEvaluator.evaluate(reading.systolic(), reading.diastolic(), reading.pulse(), limits)
                        .ifPresent(reason -> {
                            UUID eventId = UUID.randomUUID();
                            outbox.add(
                                    eventId,
                                    ReadingBreached.TYPE,
                                    new ReadingBreached(
                                            eventId,
                                            device.deviceId(),
                                            reading.readingId(),
                                            device.patientId(),
                                            reading.metricType(),
                                            reading.measuredAt(),
                                            reading.systolic(),
                                            reading.diastolic(),
                                            reading.pulse(),
                                            reason));
                        });
            }
            return stored;
        });
    }

    private static ErrorResponseException problem(HttpStatus status, String detail) {
        return new ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), null);
    }
}
