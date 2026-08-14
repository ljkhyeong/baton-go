package com.personal.batongo.application.link.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TargetContractOperationsUseCase {

    InventoryResult inventory(InventoryQuery query);

    RemediationResult remediate(RemediationCommand command);

    record InventoryQuery(
            UUID afterLinkId,
            int limit
    ) {
    }

    record InventoryResult(
            String contractVersion,
            List<InventoryItem> items,
            UUID nextAfterLinkId,
            boolean hasMore
    ) {
    }

    record InventoryItem(
            UUID linkId,
            Compliance compliance,
            RemediationState remediationState,
            CreationRequestState creationRequestState,
            Instant createdAt,
            Instant expiresAt,
            Instant revokedAt,
            long version
    ) {
    }

    record RemediationCommand(
            UUID linkId,
            long expectedVersion
    ) {
    }

    record RemediationResult(
            UUID linkId,
            String contractVersion,
            RemediationState remediationState,
            Instant revokedAt,
            boolean alreadyRevoked
    ) {
    }

    enum Compliance {
        COMPLIANT,
        NON_COMPLIANT
    }

    enum RemediationState {
        NOT_REQUIRED,
        UNREVOKED,
        REVOKED
    }

    enum CreationRequestState {
        PRESENT,
        MISSING
    }
}
