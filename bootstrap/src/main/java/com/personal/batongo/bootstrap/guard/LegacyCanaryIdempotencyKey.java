package com.personal.batongo.bootstrap.guard;

import java.util.UUID;

record LegacyCanaryIdempotencyKey(String value) {

    static LegacyCanaryIdempotencyKey parse(String rawValue) {
        if (rawValue == null) {
            throw invalidCanary();
        }

        try {
            UUID parsed = UUID.fromString(rawValue);
            String canonicalValue = parsed.toString();
            if (!canonicalValue.equalsIgnoreCase(rawValue)) {
                throw invalidCanary();
            }
            return new LegacyCanaryIdempotencyKey(canonicalValue);
        } catch (IllegalArgumentException exception) {
            throw invalidCanary();
        }
    }

    private static IllegalArgumentException invalidCanary() {
        return new IllegalArgumentException("legacy canary UUID 형식이 올바르지 않습니다");
    }

    @Override
    public String toString() {
        return "LegacyCanaryIdempotencyKey[redacted]";
    }
}
