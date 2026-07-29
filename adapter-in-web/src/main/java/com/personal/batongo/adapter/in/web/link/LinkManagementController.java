package com.personal.batongo.adapter.in.web.link;

import com.personal.batongo.adapter.in.web.PublicLinkProperties;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreateLinkCommand;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreatedLinkResult;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/links")
public class LinkManagementController {

    private final SmartLinkUseCase smartLinkUseCase;
    private final PublicLinkProperties publicLinkProperties;

    public LinkManagementController(
            SmartLinkUseCase smartLinkUseCase,
            PublicLinkProperties publicLinkProperties
    ) {
        this.smartLinkUseCase = smartLinkUseCase;
        this.publicLinkProperties = publicLinkProperties;
    }

    @PostMapping
    public ResponseEntity<CreateLinkResponse> createLink(
            @Valid @RequestBody CreateLinkRequest request
    ) {
        CreatedLinkResult result = smartLinkUseCase.createLink(new CreateLinkCommand(
                request.targetSystem(),
                request.targetPath(),
                request.purpose(),
                request.notBefore(),
                request.expiresAt()
        ));
        URI location = URI.create("/api/v1/links/" + result.link().id());
        return ResponseEntity.created(location).body(CreateLinkResponse.from(
                result,
                publicLinkProperties.shortUrl(result.rawCode())
        ));
    }

    @GetMapping("/{linkId}")
    public LinkResponse getLink(@PathVariable UUID linkId) {
        return LinkResponse.from(smartLinkUseCase.getLink(linkId));
    }

    @PutMapping("/{linkId}/revocation")
    public LinkResponse revokeLink(@PathVariable UUID linkId) {
        return LinkResponse.from(smartLinkUseCase.revokeLink(linkId));
    }
}
