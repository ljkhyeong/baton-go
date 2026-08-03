package com.personal.batongo.adapter.in.web.operations;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record TargetContractRemediationRequest(
        @NotNull @PositiveOrZero Long expectedVersion
) {
}
