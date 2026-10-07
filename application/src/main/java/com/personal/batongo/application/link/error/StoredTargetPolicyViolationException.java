package com.personal.batongo.application.link.error;

import java.util.UUID;

public final class StoredTargetPolicyViolationException extends LinkNotFoundException {

    private final UUID linkId;

    public StoredTargetPolicyViolationException(UUID linkId) {
        this.linkId = linkId;
    }

    public UUID linkId() {
        return linkId;
    }
}
