package com.personal.batongo.application.link.error;

import java.util.Objects;
import java.util.UUID;

public final class StoredTargetPolicyViolationException extends LinkNotFoundException {

    private final UUID linkId;

    public StoredTargetPolicyViolationException(UUID linkId) {
        this.linkId = Objects.requireNonNull(linkId, "링크 식별자는 필수입니다");
    }

    public UUID linkId() {
        return linkId;
    }
}
