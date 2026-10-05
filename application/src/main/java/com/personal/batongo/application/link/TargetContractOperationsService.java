package com.personal.batongo.application.link;

import com.personal.batongo.application.link.error.InvalidRequestException;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.error.TargetContractRemediationNotApplicableException;
import com.personal.batongo.application.link.error.TargetContractRemediationStaleException;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase;
import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkSnapshot;
import com.personal.batongo.domain.link.LinkRevocationPolicy;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TargetContractOperationsService implements TargetContractOperationsUseCase {

    private static final String CONTRACT_VERSION = "v1";
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
        if (!StoredLinkScan.isValidLimit(query.limit())) {
            throw InvalidRequestException.targetContractInventory();
        }
        StoredLinkScan scan = StoredLinkScan.read(repository, query.afterLinkId(), query.limit());
        return new InventoryResult(
                CONTRACT_VERSION,
                scan.rows().stream().map(this::toInventoryItem).toList(),
                scan.nextAfterLinkId(),
                scan.hasMore()
        );
    }

    @Override
    public RemediationResult remediate(RemediationCommand command) {
        if (command.expectedVersion() < 0
                || command.expectedVersion() > MAX_REVOCABLE_VERSION) {
            throw InvalidRequestException.targetContractRemediation();
        }
        StoredLinkSnapshot storedLink = repository.findStoredByIdForUpdate(command.linkId())
                .orElseThrow(LinkNotFoundException::new);
        if (storedLink.trustedTarget().isPresent()) {
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
        repository.revokeStored(
                storedLink.id(),
                storedLink.version(),
                revokedAt
        );
        return new RemediationResult(
                storedLink.id(),
                CONTRACT_VERSION,
                RemediationState.REVOKED,
                revokedAt,
                false
        );
    }

    private InventoryItem toInventoryItem(StoredLinkSnapshot storedLink) {
        Compliance compliance = storedLink.trustedTarget().isPresent()
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

}
