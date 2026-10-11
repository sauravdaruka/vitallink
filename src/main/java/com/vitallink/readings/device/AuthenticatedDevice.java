package com.vitallink.readings.device;

/** The device behind a request, and the patient it belongs to now. Found from the device's API key. */
public record AuthenticatedDevice(String deviceId, String patientId) {}
