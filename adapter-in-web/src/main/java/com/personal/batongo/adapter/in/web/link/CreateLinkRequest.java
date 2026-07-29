package com.personal.batongo.adapter.in.web.link;

import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CreateLinkRequest(
        @NotNull TargetSystem targetSystem,
        @NotBlank @Size(max = 1024) String targetPath,
        @NotNull LinkPurpose purpose,
        Instant notBefore,
        Instant expiresAt
) {
}
