package com.personal.batongo.adapter.in.web.operations;

import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.Compliance;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.CreationRequestState;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.InventoryItem;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.InventoryResult;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.RemediationState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TargetContractInventoryResponse(
        String contractVersion,
        List<Item> items,
        UUID nextAfterLinkId,
        boolean hasMore
) {

    static TargetContractInventoryResponse from(InventoryResult result) {
        return new TargetContractInventoryResponse(
                result.contractVersion(),
                result.items().stream().map(Item::from).toList(),
                result.nextAfterLinkId(),
                result.hasMore()
        );
    }

    public record Item(
            UUID linkId,
            Compliance compliance,
            RemediationState remediationState,
            CreationRequestState creationRequestState,
            Instant createdAt,
            Instant expiresAt,
            Instant revokedAt,
            long version
    ) {

        private static Item from(InventoryItem item) {
            return new Item(
                    item.linkId(),
                    item.compliance(),
                    item.remediationState(),
                    item.creationRequestState(),
                    item.createdAt(),
                    item.expiresAt(),
                    item.revokedAt(),
                    item.version()
            );
        }
    }
}
