package com.personal.batongo.adapter.in.web.operations;

import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.InventoryQuery;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.RemediationCommand;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations/link-target-contract-v1")
@ConditionalOnBooleanProperty(
        prefix = "baton-go.target-contract-operations",
        name = {"enabled", "private-ingress-confirmed"}
)
public class TargetContractOperationsController {

    private static final String REFERRER_POLICY = "Referrer-Policy";

    private final TargetContractOperationsUseCase operationsUseCase;

    public TargetContractOperationsController(TargetContractOperationsUseCase operationsUseCase) {
        this.operationsUseCase = operationsUseCase;
    }

    @GetMapping("/inventory")
    public ResponseEntity<TargetContractInventoryResponse> inventory(
            @RequestParam(value = "afterLinkId", required = false) UUID afterLinkId,
            @RequestParam(value = "limit", defaultValue = "100") int limit
    ) {
        TargetContractInventoryResponse response = TargetContractInventoryResponse.from(
                operationsUseCase.inventory(new InventoryQuery(afterLinkId, limit))
        );
        return noStore(response);
    }

    @PutMapping("/links/{linkId}/revocation")
    public ResponseEntity<TargetContractRemediationResponse> remediate(
            @PathVariable UUID linkId,
            @Valid @RequestBody TargetContractRemediationRequest request
    ) {
        TargetContractRemediationResponse response = TargetContractRemediationResponse.from(
                operationsUseCase.remediate(new RemediationCommand(
                        linkId,
                        request.expectedVersion()
                ))
        );
        return noStore(response);
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(REFERRER_POLICY, "no-referrer")
                .body(body);
    }
}
