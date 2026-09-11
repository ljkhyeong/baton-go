package com.personal.batongo.application.link.error;

public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException() {
        super("Idempotency-Key는 버전 1~5의 소문자 표준 UUID여야 합니다");
    }
}
