package com.personal.batongo.application.link;

import com.personal.batongo.application.link.error.InvalidIdempotencyKeyException;
import java.util.UUID;

public record CreationIdempotencyKey(String value) {

    public CreationIdempotencyKey {
        UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new InvalidIdempotencyKeyException();
        }
        if (!parsed.toString().equalsIgnoreCase(value)) {
            throw new InvalidIdempotencyKeyException();
        }
        value = parsed.toString();
    }
}
