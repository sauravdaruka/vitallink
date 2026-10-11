package com.vitallink.readings.ingest;

import java.util.List;

record IngestResponse(List<ReadingResult> results) {}
