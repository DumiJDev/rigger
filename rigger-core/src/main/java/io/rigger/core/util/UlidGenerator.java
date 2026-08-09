package io.rigger.core.util;

import java.time.Instant;
import java.util.UUID;

/**
 * Generates time-sortable unique identifiers for Rigger resources and audit entries.
 * Uses UUID v7 semantics: millisecond timestamp prefix + random suffix.
 * This ensures audit entries can be sorted chronologically by their ID.
 */
public final class UlidGenerator {

    private UlidGenerator() {}

    /**
     * Generates a time-sortable ID string.
     * Format: {@code <13-hex-millis>-<random-uuid-suffix>}
     * Example: {@code 018f3a2b4c1d-a1b2c3d4-e5f6-7890-abcd-ef1234567890}
     */
    public static String generate() {
        long millis = Instant.now().toEpochMilli();
        String timePart = String.format("%013x", millis);
        String randomPart = UUID.randomUUID().toString();
        return timePart + "-" + randomPart;
    }
}
