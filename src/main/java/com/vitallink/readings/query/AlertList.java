package com.vitallink.readings.query;

import java.util.List;

/** A coach's alerts, newest first. */
record AlertList(List<AlertView> alerts) {}
