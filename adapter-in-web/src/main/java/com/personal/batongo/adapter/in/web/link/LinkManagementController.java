package com.personal.batongo.adapter.in.web.link;

import com.personal.batongo.adapter.in.web.ManagementOperationLogger;
import com.personal.batongo.application.link.CreationIdempotencyKey;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreateLinkCommand;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreatedLinkResult;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.LinkSearchQuery;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.RevokedLinkResult;
import com.personal.batongo.domain.link.LinkAvailabilityPolicy.Status;
import com.personal.batongo.domain.link.TargetSystem;
import jakarta.validation.Valid;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/links", produces = MediaType.APPLICATION_JSON_VALUE)
public class LinkManagementController {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed";

    private final SmartLinkUseCase smartLinkUseCase;
    private final ManagementOperationLogger operationLogger;

    public LinkManagementController(
            SmartLinkUseCase smartLinkUseCase, ManagementOperationLogger operationLogger
    ) {
        this.smartLinkUseCase = smartLinkUseCase;
        this.operationLogger = operationLogger;
    }

    @PostMapping
    public ResponseEntity<CreateLinkResponse> createLink(
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false)
            String idempotencyKey,
            @Valid @RequestBody CreateLinkRequest request,
            Principal principal
    ) {
        CreatedLinkResult result = smartLinkUseCase.createLink(new CreateLinkCommand(
                CreationIdempotencyKey.parseRequest(idempotencyKey),
                request.targetSystem(),
                request.targetPath(),
                request.purpose(),
                request.notBefore(),
                request.expiresAt()
        ));
        operationLogger.completed(
                result.replayed() ? "LINK_CREATE_REPLAY" : "LINK_CREATE",
                result.link().id(),
                principal
        );
        URI location = URI.create("/api/v1/links/" + result.link().id());
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .location(location)
                .header(IDEMPOTENCY_REPLAYED_HEADER, Boolean.toString(result.replayed()))
                .body(CreateLinkResponse.from(result));
    }

    @GetMapping
    public ResponseEntity<LinkSearchResponse> searchLinks(
            @RequestParam(value = "afterLinkId", required = false) UUID afterLinkId,
            @RequestParam(value = "limit", defaultValue = "100") int limit,
            @RequestParam(value = "targetSystem", required = false) TargetSystem targetSystem,
            @RequestParam(value = "createdFrom", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(value = "createdBefore", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdBefore,
            @RequestParam(value = "status", required = false) Status status
    ) {
        return ResponseEntity.ok(LinkSearchResponse.from(smartLinkUseCase.searchLinks(
                new LinkSearchQuery(afterLinkId, limit, targetSystem, createdFrom, createdBefore, status)
        )));
    }

    @GetMapping("/{linkId}")
    public ResponseEntity<LinkResponse> getLink(@PathVariable UUID linkId) {
        return ResponseEntity.ok(LinkResponse.from(smartLinkUseCase.getLink(linkId)));
    }

    @GetMapping("/batch")
    public LinkBatchResponse getLinks(@RequestParam("linkIds") List<UUID> linkIds) {
        return LinkBatchResponse.from(smartLinkUseCase.getLinks(linkIds));
    }

    @PutMapping("/{linkId}/revocation")
    public ResponseEntity<LinkResponse> revokeLink(@PathVariable UUID linkId, Principal principal) {
        RevokedLinkResult result = smartLinkUseCase.revokeLink(linkId);
        operationLogger.completed(
                result.alreadyRevoked() ? "LINK_REVOKE_REPLAY" : "LINK_REVOKE",
                result.link().id(),
                principal
        );
        return ResponseEntity.ok(LinkResponse.from(result.link()));
    }
}
