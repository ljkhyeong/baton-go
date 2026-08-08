package com.personal.batongo.application.link.port.in;

import com.personal.batongo.application.link.CreationIdempotencyKey;
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
            CreationIdempotencyKey idempotencyKey,
            TargetSystem targetSystem,
            String targetPath,
            LinkPurpose purpose,
            Instant notBefore,
            Instant expiresAt
    ) {

        @Override
        public String toString() {
            return "CreateLinkCommand[redacted]";
        }
    }

    record CreatedLinkResult(
            LinkResult link,
            String rawCode,
            boolean replayed
    ) {

        @Override
        public String toString() {
            return "CreatedLinkResult[redacted]";
        }
    }

    record ResolvedLinkResult(
            UUID id,
            URI destination
    ) {

        @Override
        public String toString() {
            return "ResolvedLinkResult[id=" + id + ", destination=redacted]";
        }
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

        @Override
        public String toString() {
            return "LinkResult[id=" + id + ", target=redacted]";
        }
    }
}
