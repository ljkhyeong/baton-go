package com.personal.batongo.application.link.error;

public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException() {
        super("같은 Idempotency-Key를 다른 링크 생성 요청에 사용할 수 없습니다");
    }
}
