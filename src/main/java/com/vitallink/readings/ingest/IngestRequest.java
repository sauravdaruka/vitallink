package com.vitallink.readings.ingest;

import java.util.List;

/**
 * A batch of readings from one device. A single reading is a batch of one.
 *
 * @param patientId optional; if sent, it must be the patient the device is assigned to
 */
record IngestRequest(String patientId, List<ReadingInput> readings) {}
