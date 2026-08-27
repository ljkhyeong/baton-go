package com.personal.batongo.adapter.in.web.link;

import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.ResolvedLinkResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LinkResolverController {

    private final SmartLinkUseCase smartLinkUseCase;

    public LinkResolverController(SmartLinkUseCase smartLinkUseCase) {
        this.smartLinkUseCase = smartLinkUseCase;
    }

    @GetMapping("/l/{code}")
    public ResponseEntity<Void> resolveLink(@PathVariable String code) {
        ResolvedLinkResult result = smartLinkUseCase.resolveLink(code);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(result.destination())
                .build();
    }
}
