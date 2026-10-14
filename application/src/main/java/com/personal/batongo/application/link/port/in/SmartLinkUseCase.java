package com.personal.batongo.application.link.port.in;

import com.personal.batongo.application.link.CreationIdempotencyKey;
import com.personal.batongo.domain.link.LinkAvailabilityPolicy;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SmartLinkUseCase {

    CreatedLinkResult createLink(CreateLinkCommand command);

    LinkResult getLink(UUID linkId);

    LinkBatchResult getLinks(List<UUID> linkIds);

    LinkSearchResult searchLinks(LinkSearchQuery query);

    RevokedLinkResult revokeLink(UUID linkId);

    record LinkBatchResult(List<LinkResult> items, List<UUID> notFoundIds, Instant evaluatedAt) {
        public LinkBatchResult {
            items = List.copyOf(items);
            notFoundIds = List.copyOf(notFoundIds);
        }
    }

    record LinkSearchQuery(
            UUID afterLinkId,
            int limit,
            TargetSystem targetSystem,
            Instant createdFrom,
            Instant createdBefore,
            Instant expiresFrom,
            Instant expiresBefore,
            LinkAvailabilityPolicy.Status status
    ) {
    }

    record LinkSearchResult(
            List<LinkResult> items,
            UUID nextAfterLinkId,
            boolean hasMore,
            Instant evaluatedAt
    ) {
        public LinkSearchResult {
            items = List.copyOf(items);
        }
    }

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
            URI shortUrl,
            boolean replayed
    ) {

        @Override
        public String toString() {
            return "CreatedLinkResult[redacted]";
        }
    }

    /** 계약을 위반해 숨긴 저장 대상이면 폐기 시각만 기록하고 {@code link}는 null이다. */
    record RevokedLinkResult(
            LinkResult link,
            boolean alreadyRevoked
    ) {

        public boolean hidden() {
            return link == null;
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
            Instant createdAt,
            Instant evaluatedAt
    ) {

        public LinkAvailabilityPolicy.Status status() {
            return LinkAvailabilityPolicy.evaluate(revokedAt, notBefore, expiresAt, evaluatedAt);
        }

        @Override
        public String toString() {
            return "LinkResult[id=" + id + ", target=redacted]";
        }
    }
}
