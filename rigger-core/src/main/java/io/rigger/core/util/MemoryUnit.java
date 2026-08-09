package io.rigger.core.util;

/**
 * Utility for parsing human-readable memory strings (e.g. "512Mi", "1Gi", "256m")
 * into bytes for Docker API calls.
 */
public final class MemoryUnit {

    private MemoryUnit() {}

    /** Parses a memory string into bytes. Supports Ki, Mi, Gi, Ti, k, m, g, t suffixes. */
    public static long toBytes(String value) {
        if (value == null || value.isBlank()) return 0L;
        value = value.trim();
        if (value.endsWith("Ki")) return Long.parseLong(value.replace("Ki", "")) * 1024L;
        if (value.endsWith("Mi")) return Long.parseLong(value.replace("Mi", "")) * 1024L * 1024L;
        if (value.endsWith("Gi")) return Long.parseLong(value.replace("Gi", "")) * 1024L * 1024L * 1024L;
        if (value.endsWith("Ti")) return Long.parseLong(value.replace("Ti", "")) * 1024L * 1024L * 1024L * 1024L;
        if (value.endsWith("m"))  return Long.parseLong(value.replace("m", "")) * 1024L * 1024L;
        if (value.endsWith("g"))  return Long.parseLong(value.replace("g", "")) * 1024L * 1024L * 1024L;
        return Long.parseLong(value);
    }
}
