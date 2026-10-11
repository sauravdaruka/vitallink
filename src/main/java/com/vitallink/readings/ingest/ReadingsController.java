package com.vitallink.readings.ingest;

import com.vitallink.readings.device.AuthenticatedDevice;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/readings")
class ReadingsController {

    private final ReadingIngestService ingestService;

    ReadingsController(ReadingIngestService ingestService) {
        this.ingestService = ingestService;
    }

    /**
     * Always 200 when the batch itself is acceptable: each reading has its own result. ACCEPTED means the reading
     * is stored in PostgreSQL, so the device can delete its copy.
     */
    @PostMapping
    IngestResponse ingest(@AuthenticationPrincipal AuthenticatedDevice device, @RequestBody IngestRequest request) {
        return new IngestResponse(ingestService.ingest(device, request));
    }
}
