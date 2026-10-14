package com.vitallink.readings.device;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class DeviceApiKeys {

    private final JdbcTemplate jdbc;

    DeviceApiKeys(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The device for this key, if the key is known, not revoked, and the device is active. */
    Optional<AuthenticatedDevice> findDevice(String apiKey) {
        return jdbc
                .query(
                        """
                        SELECT d.device_id, d.patient_id
                        FROM device_api_keys k
                        JOIN devices d ON d.device_id = k.device_id
                        WHERE k.key_hash = ? AND k.revoked_at IS NULL AND d.active
                        """,
                        (row, _) -> new AuthenticatedDevice(row.getString("device_id"), row.getString("patient_id")),
                        (Object) sha256(apiKey))
                .stream()
                .findFirst();
    }

    // A fast hash is enough: keys are long random strings, so they cannot be guessed.
    // A slow hash like bcrypt protects guessable passwords and would add ~100 ms to every request.
    private static byte[] sha256(String apiKey) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available in Java", e);
        }
    }
}
