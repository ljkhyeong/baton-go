package com.personal.batongo.adapter.in.web.link;

import com.personal.batongo.application.link.CreationIdempotencyKey;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreateLinkCommand;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreatedLinkResult;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/links")
public class LinkManagementController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String IDEMPOTENCY_REPLAYED_HEADER = "Idempotency-Replayed";
    private static final String REFERRER_POLICY = "Referrer-Policy";

    private final SmartLinkUseCase smartLinkUseCase;

    public LinkManagementController(SmartLinkUseCase smartLinkUseCase) {
        this.smartLinkUseCase = smartLinkUseCase;
    }

    @PostMapping
    public ResponseEntity<CreateLinkResponse> createLink(
            @RequestHeader(value = IDEMPOTENCY_KEY_HEADER, required = false)
            String idempotencyKey,
            @Valid @RequestBody CreateLinkRequest request
    ) {
        CreatedLinkResult result = smartLinkUseCase.createLink(new CreateLinkCommand(
                CreationIdempotencyKey.parseRequest(idempotencyKey),
                request.targetSystem(),
                request.targetPath(),
                request.purpose(),
                request.notBefore(),
                request.expiresAt()
        ));
        URI location = URI.create("/api/v1/links/" + result.link().id());
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .location(location)
                .cacheControl(CacheControl.noStore())
                .header(REFERRER_POLICY, "no-referrer")
                .header(IDEMPOTENCY_REPLAYED_HEADER, Boolean.toString(result.replayed()))
                .body(CreateLinkResponse.from(result));
    }

    @GetMapping("/{linkId}")
    public ResponseEntity<LinkResponse> getLink(@PathVariable UUID linkId) {
        return noStore(LinkResponse.from(smartLinkUseCase.getLink(linkId)));
    }

    @PutMapping("/{linkId}/revocation")
    public ResponseEntity<LinkResponse> revokeLink(@PathVariable UUID linkId) {
        return noStore(LinkResponse.from(smartLinkUseCase.revokeLink(linkId)));
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(REFERRER_POLICY, "no-referrer")
                .body(body);
    }
}
