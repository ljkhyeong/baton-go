package com.personal.batongo.adapter.in.web.link;

import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CreateLinkRequest(
        @NotNull TargetSystem targetSystem,
        @NotNull String targetPath,
        @NotNull LinkPurpose purpose,
        @JsonDeserialize(using = StrictUtcInstantDeserializer.class) Instant notBefore,
        @JsonDeserialize(using = StrictUtcInstantDeserializer.class) Instant expiresAt
) {

    @Override
    public String toString() {
        return "CreateLinkRequest[target=redacted]";
    }
}
