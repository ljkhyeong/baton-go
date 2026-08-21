package com.personal.batongo.adapter.in.web.operations;

import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.RemediationResult;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.RemediationState;
import java.time.Instant;
import java.util.UUID;

public record TargetContractRemediationResponse(
        UUID linkId,
        String contractVersion,
        RemediationState remediationState,
        Instant revokedAt,
        boolean alreadyRevoked
) {

    static TargetContractRemediationResponse from(RemediationResult result) {
        return new TargetContractRemediationResponse(
                result.linkId(),
                result.contractVersion(),
                result.remediationState(),
                result.revokedAt(),
                result.alreadyRevoked()
        );
    }
}
