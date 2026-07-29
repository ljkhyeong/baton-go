package com.personal.batongo.application.link;

import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.out.IssuedLinkCode;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.application.link.port.out.TargetUrlPort;
import com.personal.batongo.domain.link.SmartLink;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SmartLinkService implements SmartLinkUseCase {

    private final SmartLinkRepository repository;
    private final LinkCodePort linkCodePort;
    private final TargetUrlPort targetUrlPort;
    private final Clock clock;

    public SmartLinkService(
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
    public CreatedLinkResult createLink(CreateLinkCommand command) {
        Instant now = databaseTime();
        IssuedLinkCode issuedCode = linkCodePort.issue();
        SmartLink smartLink = SmartLink.create(
                UUID.randomUUID(),
                issuedCode.codeHash(),
                command.targetSystem(),
                command.targetPath(),
                command.purpose(),
                databaseTime(command.notBefore()),
                databaseTime(command.expiresAt()),
                now
        );
        SmartLink saved = repository.save(smartLink);
        return new CreatedLinkResult(toResult(saved), issuedCode.rawCode());
    }

    @Override
    @Transactional(readOnly = true)
    public LinkResult getLink(UUID linkId) {
        return toResult(findLink(linkId));
    }

    @Override
    @Transactional(readOnly = true)
    public ResolvedLinkResult resolveLink(String rawCode) {
        String codeHash = linkCodePort.hash(rawCode);
        SmartLink smartLink = repository.findByCodeHash(codeHash)
                .orElseThrow(LinkNotFoundException::new);
        smartLink.requireResolvableAt(databaseTime());
        return new ResolvedLinkResult(
                smartLink.getId(),
                targetUrlPort.resolve(smartLink.getTargetSystem(), smartLink.getTargetPath())
        );
    }

    @Override
    public LinkResult revokeLink(UUID linkId) {
        SmartLink smartLink = repository.findByIdForUpdate(linkId)
                .orElseThrow(LinkNotFoundException::new);
        smartLink.revoke(databaseTime());
        return toResult(repository.save(smartLink));
    }

    private SmartLink findLink(UUID linkId) {
        return repository.findById(linkId).orElseThrow(LinkNotFoundException::new);
    }

    private Instant databaseTime() {
        return databaseTime(clock.instant());
    }

    private Instant databaseTime(Instant value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.MICROS);
    }

    private LinkResult toResult(SmartLink smartLink) {
        return new LinkResult(
                smartLink.getId(),
                smartLink.getTargetSystem(),
                smartLink.getTargetPath(),
                smartLink.getPurpose(),
                smartLink.getNotBefore(),
                smartLink.getExpiresAt(),
                smartLink.getRevokedAt(),
                smartLink.getCreatedAt()
        );
    }
}
