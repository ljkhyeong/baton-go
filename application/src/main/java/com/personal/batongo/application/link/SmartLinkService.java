package com.personal.batongo.application.link;

import com.personal.batongo.application.link.error.IdempotencyKeyConflictException;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.out.IssuedLinkCode;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import com.personal.batongo.application.link.port.out.LinkCreationReservationPort;
import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.application.link.port.out.TargetUrlPort;
import com.personal.batongo.domain.link.SmartLink;
import com.personal.batongo.domain.link.TargetPath;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SmartLinkService implements SmartLinkUseCase {

    private final SmartLinkRepository repository;
    private final LinkCreationReservationPort reservationPort;
    private final LinkCodePort linkCodePort;
    private final TargetUrlPort targetUrlPort;
    private final Clock clock;

    public SmartLinkService(
            SmartLinkRepository repository,
            LinkCreationReservationPort reservationPort,
            LinkCodePort linkCodePort,
            TargetUrlPort targetUrlPort,
            Clock clock
    ) {
        this.repository = repository;
        this.reservationPort = reservationPort;
        this.linkCodePort = linkCodePort;
        this.targetUrlPort = targetUrlPort;
        this.clock = clock;
    }

    @Override
    public CreatedLinkResult createLink(CreateLinkCommand command) {
        Instant now = databaseTime();
        Instant notBefore = databaseTime(command.notBefore());
        Instant expiresAt = databaseTime(command.expiresAt());
        String targetPath = TargetPath.normalize(command.targetPath());
        String idempotencyKey = command.idempotencyKey().value();
        String idempotencyKeyHash = linkCodePort.hashIdempotencyKey(idempotencyKey);
        LinkCreationReservationPort.Reservation reservation = reservationPort.reserve(
                idempotencyKeyHash,
                UUID.randomUUID(),
                now
        );
        IssuedLinkCode issuedCode = linkCodePort.issue(idempotencyKey);

        if (!reservation.owner()) {
            SmartLink existing = findLink(reservation.linkId());
            requireSameCreationRequest(
                    existing,
                    command,
                    targetPath,
                    notBefore,
                    expiresAt
            );
            return new CreatedLinkResult(toResult(existing), issuedCode.rawCode(), true);
        }

        SmartLink smartLink = SmartLink.create(
                reservation.linkId(),
                issuedCode.codeHash(),
                command.targetSystem(),
                targetPath,
                command.purpose(),
                notBefore,
                expiresAt,
                now
        );
        SmartLink saved = repository.save(smartLink);
        return new CreatedLinkResult(toResult(saved), issuedCode.rawCode(), false);
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

    private void requireSameCreationRequest(
            SmartLink existing,
            CreateLinkCommand command,
            String targetPath,
            Instant notBefore,
            Instant expiresAt
    ) {
        boolean sameRequest = existing.getTargetSystem() == command.targetSystem()
                && existing.getTargetPath().equals(targetPath)
                && existing.getPurpose() == command.purpose()
                && Objects.equals(existing.getNotBefore(), notBefore)
                && Objects.equals(existing.getExpiresAt(), expiresAt);
        if (!sameRequest) {
            throw new IdempotencyKeyConflictException();
        }
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
