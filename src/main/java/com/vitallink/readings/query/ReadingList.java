package com.vitallink.readings.query;

import java.util.List;

/** Readings, newest first. */
record ReadingList(List<ReadingView> readings) {}
