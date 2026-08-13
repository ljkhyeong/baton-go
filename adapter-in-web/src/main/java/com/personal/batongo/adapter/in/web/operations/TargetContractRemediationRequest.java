package com.personal.batongo.adapter.in.web.operations;

import jakarta.validation.constraints.NotNull;

public record TargetContractRemediationRequest(
        @NotNull Long expectedVersion
) {
}
