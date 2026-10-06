package com.personal.batongo.application.link;

import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.error.StoredTargetPolicyViolationException;
import com.personal.batongo.application.link.port.in.ResolveLinkUseCase;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLink;
import com.personal.batongo.application.link.port.out.TargetUrlPort;
import com.personal.batongo.domain.link.LinkAvailabilityPolicy;
import com.personal.batongo.domain.link.TrustedTarget;
import java.time.Clock;
import org.springframework.stereotype.Service;

@Service
public class LinkResolutionService implements ResolveLinkUseCase {

    private final SmartLinkRepository repository;
    private final LinkCodePort linkCodePort;
    private final TargetUrlPort targetUrlPort;
    private final Clock clock;

    public LinkResolutionService(
            SmartLinkRepository repository,
            LinkCodePort linkCodePort,
            TargetUrlPort targetUrlPort,
            Clock clock
    ) {
        this.repository = repository;
        this.linkCodePort = linkCodePort;
        this.targetUrlPort = targetUrlPort;
        this.clock = clock;
    }

    @Override
    public ResolvedLinkResult resolveLink(String rawCode) {
        String codeHash = linkCodePort.hash(rawCode);
        StoredLink storedLink = repository.findByCodeHash(codeHash)
                .orElseThrow(LinkNotFoundException::new);
        TrustedTarget trustedTarget = storedLink.trustedTarget()
                .orElseThrow(() -> new StoredTargetPolicyViolationException(storedLink.id()));
        LinkAvailabilityPolicy.requireResolvableAt(
                storedLink.revokedAt(),
                storedLink.notBefore(),
                storedLink.expiresAt(),
                clock.instant()
        );
        return new ResolvedLinkResult(targetUrlPort.resolve(trustedTarget));
    }
}
