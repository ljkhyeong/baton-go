package com.personal.batongo.application.link.error;

public final class InvalidCreationTimeException extends RuntimeException {

    public InvalidCreationTimeException() {
        super(
                "notBefore와 expiresAt은 1582-10-15T00:00:00Z 이상 "
                        + "9999-12-31T23:59:59.999999Z 이하의 마이크로초 단위여야 합니다"
        );
    }
}
