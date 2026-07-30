package com.personal.batongo.application.link.error;

public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException() {
        super("Idempotency-Key는 canonical UUID 형식이어야 합니다");
    }
}
