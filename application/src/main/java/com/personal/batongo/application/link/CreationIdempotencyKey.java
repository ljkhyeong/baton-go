package com.personal.batongo.application.link;

import com.personal.batongo.application.link.error.InvalidIdempotencyKeyException;
import java.util.regex.Pattern;

public final class CreationIdempotencyKey {

    // 같은 요청이 항상 같은 해시를 갖도록 버전 1~5의 소문자 RFC 표준 UUID만 받는다.
    private static final Pattern CANONICAL_UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
    );

    private final String value;

    private CreationIdempotencyKey(String value) {
        this.value = value;
    }

    public static CreationIdempotencyKey parseRequest(String value) {
        if (value == null || !CANONICAL_UUID.matcher(value).matches()) {
            throw new InvalidIdempotencyKeyException();
        }
        return new CreationIdempotencyKey(value);
    }

    public String value() {
        return value;
    }

    @Override
    public String toString() {
        return "CreationIdempotencyKey[redacted]";
    }
}
