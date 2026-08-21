package com.personal.batongo.adapter.in.web.link;

import com.personal.batongo.application.link.port.in.SmartLinkUseCase.LinkResult;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import java.time.Instant;
import java.util.UUID;

public record LinkResponse(
        UUID id,
        TargetSystem targetSystem,
        String targetPath,
        LinkPurpose purpose,
        Instant notBefore,
        Instant expiresAt,
        Instant revokedAt,
        Instant createdAt
) {

    static LinkResponse from(LinkResult result) {
        return new LinkResponse(
                result.id(),
                result.targetSystem(),
                result.targetPath(),
                result.purpose(),
                result.notBefore(),
                result.expiresAt(),
                result.revokedAt(),
                result.createdAt()
        );
    }
}
