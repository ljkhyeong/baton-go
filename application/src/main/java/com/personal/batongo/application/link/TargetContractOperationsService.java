package com.personal.batongo.application.link;

import com.personal.batongo.application.link.error.InvalidTargetContractInventoryRequestException;
import com.personal.batongo.application.link.error.InvalidTargetContractRemediationRequestException;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.error.TargetContractRemediationNotApplicableException;
import com.personal.batongo.application.link.error.TargetContractRemediationStaleException;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase;
import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkSnapshot;
import com.personal.batongo.domain.link.LinkValidationException;
import com.personal.batongo.domain.link.LinkRevocationPolicy;
import com.personal.batongo.domain.link.TrustedTargetPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TargetContractOperationsService implements TargetContractOperationsUseCase {

    private static final String CONTRACT_VERSION = "v1";
    private static final int MAX_INVENTORY_LIMIT = 500;
    private static final long MAX_REVOCABLE_VERSION = Long.MAX_VALUE - 1;

    private final SmartLinkRepository repository;
    private final Clock clock;

    public TargetContractOperationsService(
            SmartLinkRepository repository,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public InventoryResult inventory(InventoryQuery query) {
        requireValidInventoryQuery(query);
        List<StoredLinkSnapshot> scanned = repository.scanStoredAfter(
                query.afterLinkId(),
                query.limit() + 1
        );
        boolean hasMore = scanned.size() > query.limit();
        List<InventoryItem> items = scanned.stream()
                .limit(query.limit())
                .map(this::toInventoryItem)
                .toList();
        UUID nextAfterLinkId = hasMore
                ? items.getLast().linkId()
                : null;
        return new InventoryResult(
                CONTRACT_VERSION,
                items,
                nextAfterLinkId,
                hasMore
        );
    }

    @Override
    public RemediationResult remediate(RemediationCommand command) {
        if (command == null
                || command.linkId() == null
                || command.expectedVersion() < 0
                || command.expectedVersion() > MAX_REVOCABLE_VERSION) {
            throw new InvalidTargetContractRemediationRequestException();
        }
        StoredLinkSnapshot storedLink = repository.findStoredByIdForUpdate(command.linkId())
                .orElseThrow(LinkNotFoundException::new);
        if (isCompliant(storedLink)) {
            throw new TargetContractRemediationNotApplicableException();
        }
        if (storedLink.revokedAt() != null) {
            return new RemediationResult(
                    storedLink.id(),
                    CONTRACT_VERSION,
                    RemediationState.REVOKED,
                    storedLink.revokedAt(),
                    true
            );
        }
        if (storedLink.version() != command.expectedVersion()) {
            throw new TargetContractRemediationStaleException();
        }

        Instant revokedAt = LinkRevocationPolicy.requireFirstRevocationAt(
                storedLink.createdAt(),
                clock.instant()
        );
        boolean revoked = repository.revokeStoredIfVersion(
                storedLink.id(),
                command.expectedVersion(),
                revokedAt
        );
        if (!revoked) {
            throw new TargetContractRemediationStaleException();
        }
        return new RemediationResult(
                storedLink.id(),
                CONTRACT_VERSION,
                RemediationState.REVOKED,
                revokedAt,
                false
        );
    }

    private void requireValidInventoryQuery(InventoryQuery query) {
        if (query == null || query.limit() < 1 || query.limit() > MAX_INVENTORY_LIMIT) {
            throw new InvalidTargetContractInventoryRequestException();
        }
    }

    private InventoryItem toInventoryItem(StoredLinkSnapshot storedLink) {
        Compliance compliance = isCompliant(storedLink)
                ? Compliance.COMPLIANT
                : Compliance.NON_COMPLIANT;
        RemediationState remediationState = switch (compliance) {
            case COMPLIANT -> RemediationState.NOT_REQUIRED;
            case NON_COMPLIANT -> storedLink.revokedAt() == null
                    ? RemediationState.UNREVOKED
                    : RemediationState.REVOKED;
        };
        CreationRequestState creationRequestState = storedLink.creationRequestPresent()
                ? CreationRequestState.PRESENT
                : CreationRequestState.MISSING;
        return new InventoryItem(
                storedLink.id(),
                compliance,
                remediationState,
                creationRequestState,
                storedLink.createdAt(),
                storedLink.expiresAt(),
                storedLink.revokedAt(),
                storedLink.version()
        );
    }

    private boolean isCompliant(StoredLinkSnapshot storedLink) {
        try {
            TrustedTargetPolicy.requireAllowed(
                    storedLink.targetSystem(),
                    storedLink.purpose(),
                    storedLink.targetPath()
            );
            return true;
        } catch (LinkValidationException exception) {
            return false;
        }
    }

}
