package com.vitallink.readings.threshold;

import java.util.List;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Replaces limits. Each call sends the full set for its scope; anything not sent is removed. Sending an empty set
 * for a patient puts them back on the program defaults.
 */
@RestController
@RequestMapping("/v1/admin/thresholds")
class ThresholdAdminController {

    private final ThresholdAdmin admin;

    ThresholdAdminController(ThresholdAdmin admin) {
        this.admin = admin;
    }

    @PutMapping("/defaults")
    ThresholdSet replaceDefaults(@RequestBody ThresholdSet request) {
        return toResponse(admin.replace(null, request.thresholds()));
    }

    @PutMapping("/patients/{patientId}")
    ThresholdSet replaceForPatient(@PathVariable String patientId, @RequestBody ThresholdSet request) {
        return toResponse(admin.replace(patientId, request.thresholds()));
    }

    private static ThresholdSet toResponse(List<Threshold> saved) {
        return new ThresholdSet(saved.stream().map(ThresholdLimit::of).toList());
    }
}
