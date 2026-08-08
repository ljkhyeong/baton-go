package com.personal.batongo.adapter.in.web.link;

import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CreateLinkRequest(
        @NotNull
        @JsonDeserialize(using = ExactLinkEnumDeserializer.TargetSystemDeserializer.class)
        TargetSystem targetSystem,
        @NotBlank @Size(max = 1024) String targetPath,
        @NotNull
        @JsonDeserialize(using = ExactLinkEnumDeserializer.LinkPurposeDeserializer.class)
        LinkPurpose purpose,
        @JsonDeserialize(using = StrictUtcInstantDeserializer.class) Instant notBefore,
        @JsonDeserialize(using = StrictUtcInstantDeserializer.class) Instant expiresAt
) {
}
