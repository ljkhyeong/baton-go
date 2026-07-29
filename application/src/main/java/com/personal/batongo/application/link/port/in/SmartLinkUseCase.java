package com.personal.batongo.application.link.port.in;

import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public interface SmartLinkUseCase {

    CreatedLinkResult createLink(CreateLinkCommand command);

    LinkResult getLink(UUID linkId);

    ResolvedLinkResult resolveLink(String rawCode);

    LinkResult revokeLink(UUID linkId);

    record CreateLinkCommand(
            TargetSystem targetSystem,
            String targetPath,
            LinkPurpose purpose,
            Instant notBefore,
            Instant expiresAt
    ) {
    }

    record CreatedLinkResult(
            LinkResult link,
            String rawCode
    ) {
    }

    record ResolvedLinkResult(
            UUID id,
            URI destination
    ) {
    }

    record LinkResult(
            UUID id,
            TargetSystem targetSystem,
            String targetPath,
            LinkPurpose purpose,
            Instant notBefore,
            Instant expiresAt,
            Instant revokedAt,
            Instant createdAt
    ) {
    }
}
