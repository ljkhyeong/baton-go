package com.personal.batongo.adapter.in.web.link;

import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreatedLinkResult;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public record CreateLinkResponse(
        UUID id,
        URI shortUrl,
        TargetSystem targetSystem,
        String targetPath,
        LinkPurpose purpose,
        Instant notBefore,
        Instant expiresAt,
        Instant revokedAt,
        Instant createdAt
) {

    public static CreateLinkResponse from(CreatedLinkResult result, URI shortUrl) {
        return new CreateLinkResponse(
                result.link().id(),
                shortUrl,
                result.link().targetSystem(),
                result.link().targetPath(),
                result.link().purpose(),
                result.link().notBefore(),
                result.link().expiresAt(),
                result.link().revokedAt(),
                result.link().createdAt()
        );
    }

    @Override
    public String toString() {
        return "CreateLinkResponse[id=" + id + ", shortUrl=redacted, target=redacted]";
    }
}
