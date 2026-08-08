package com.personal.batongo.application.link;

import com.personal.batongo.application.link.error.IdempotencyKeyConflictException;
import com.personal.batongo.application.link.error.LinkCodeReplayMismatchException;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.error.StoredTargetPolicyViolationException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.out.IssuedLinkCode;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import com.personal.batongo.application.link.port.out.LinkCreationReservationPort;
import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkReplay;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkResolution;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkSnapshot;
import com.personal.batongo.application.link.port.out.TargetUrlPort;
import com.personal.batongo.domain.link.LinkAvailabilityPolicy;
import com.personal.batongo.domain.link.LinkValidationException;
import com.personal.batongo.domain.link.SmartLink;
import com.personal.batongo.domain.link.TrustedTarget;
import com.personal.batongo.domain.link.TrustedTargetPolicy;
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
    private final LinkCodeKeyGuard linkCodeKeyGuard;
    private final TargetUrlPort targetUrlPort;
    private final Clock clock;

    public SmartLinkService(
            SmartLinkRepository repository,
            LinkCreationReservationPort reservationPort,
            LinkCodePort linkCodePort,
            LinkCodeKeyGuard linkCodeKeyGuard,
            TargetUrlPort targetUrlPort,
            Clock clock
    ) {
        this.repository = repository;
        this.reservationPort = reservationPort;
        this.linkCodePort = linkCodePort;
        this.linkCodeKeyGuard = linkCodeKeyGuard;
        this.targetUrlPort = targetUrlPort;
        this.clock = clock;
    }

    @Override
    public CreatedLinkResult createLink(CreateLinkCommand command) {
        PreparedCreation prepared = prepareCreation(command);
        if (!prepared.admission().allowsNewReservation()) {
            return replayExistingOnly(prepared);
        }
        return reserveCreateOrReplay(prepared);
    }

    private PreparedCreation prepareCreation(CreateLinkCommand command) {
        CreationRequestAdmissionPolicy.Decision admission =
                CreationRequestAdmissionPolicy.evaluate(
                        command.idempotencyKey(),
                        command.notBefore(),
                        command.expiresAt()
                );
        String idempotencyKey = command.idempotencyKey().value();
        return new PreparedCreation(
                command,
                admission,
                idempotencyKey,
                linkCodePort.hashIdempotencyKey(idempotencyKey)
        );
    }

    private CreatedLinkResult replayExistingOnly(PreparedCreation prepared) {
        UUID existingLinkId = reservationPort.findLinkId(
                prepared.idempotencyKeyHash()
        ).orElseThrow(prepared.admission()::missingReservationException);
        String targetPath = requireAllowedTargetPath(prepared.command());
        linkCodeKeyGuard.verifyBound();
        return replayCreation(
                existingLinkId,
                prepared.command(),
                targetPath,
                prepared.admission().notBefore(),
                prepared.admission().expiresAt(),
                linkCodePort.issue(prepared.idempotencyKey())
        );
    }

    private CreatedLinkResult reserveCreateOrReplay(PreparedCreation prepared) {
        CreateLinkCommand command = prepared.command();
        String targetPath = requireAllowedTargetPath(command);
        linkCodeKeyGuard.verifyBound();
        Instant now = databaseTime();
        LinkCreationReservationPort.Reservation reservation = reservationPort.reserve(
                prepared.idempotencyKeyHash(),
                UUID.randomUUID(),
                now
        );
        IssuedLinkCode issuedCode = linkCodePort.issue(prepared.idempotencyKey());

        if (!reservation.owner()) {
            return replayCreation(
                    reservation.linkId(),
                    command,
                    targetPath,
                    prepared.admission().notBefore(),
                    prepared.admission().expiresAt(),
                    issuedCode
            );
        }

        SmartLink smartLink = SmartLink.create(
                reservation.linkId(),
                issuedCode.codeHash(),
                command.targetSystem(),
                targetPath,
                command.purpose(),
                prepared.admission().notBefore(),
                prepared.admission().expiresAt(),
                now
        );
        SmartLink saved = repository.save(smartLink);
        return new CreatedLinkResult(toResult(saved), issuedCode.rawCode(), false);
    }

    private record PreparedCreation(
            CreateLinkCommand command,
            CreationRequestAdmissionPolicy.Decision admission,
            String idempotencyKey,
            String idempotencyKeyHash
    ) {

        @Override
        public String toString() {
            return "PreparedCreation[redacted]";
        }
    }

    private String requireAllowedTargetPath(CreateLinkCommand command) {
        return TrustedTargetPolicy.requireAllowed(
                command.targetSystem(),
                command.purpose(),
                command.targetPath()
        ).targetPath();
    }

    private CreatedLinkResult replayCreation(
            UUID linkId,
            CreateLinkCommand command,
            String targetPath,
            Instant notBefore,
            Instant expiresAt,
            IssuedLinkCode issuedCode
    ) {
        StoredLinkReplay existing = findReplayLink(linkId);
        TrustedTarget trustedTarget = requireSameCreationRequest(
                existing,
                command,
                targetPath,
                notBefore,
                expiresAt
        );
        requireReplayableCode(existing, issuedCode);
        return new CreatedLinkResult(
                toResult(existing, trustedTarget),
                issuedCode.rawCode(),
                true
        );
    }

    @Override
    @Transactional(readOnly = true)
    public LinkResult getLink(UUID linkId) {
        StoredLinkSnapshot storedLink = repository.findStoredById(linkId)
                .orElseThrow(LinkNotFoundException::new);
        TrustedTarget trustedTarget = requireManagedTrustedTarget(storedLink);
        return toResult(storedLink, trustedTarget, storedLink.revokedAt());
    }

    @Override
    @Transactional(readOnly = true)
    public ResolvedLinkResult resolveLink(String rawCode) {
        String codeHash = linkCodePort.hash(rawCode);
        StoredLinkResolution storedLink = repository.findResolutionByCodeHash(codeHash)
                .orElseThrow(LinkNotFoundException::new);
        TrustedTarget trustedTarget;
        try {
            trustedTarget = TrustedTargetPolicy.requireAllowed(
                    storedLink.targetSystem(),
                    storedLink.purpose(),
                    storedLink.targetPath()
            );
        } catch (LinkValidationException exception) {
            throw new StoredTargetPolicyViolationException(storedLink.id());
        }
        LinkAvailabilityPolicy.requireResolvableAt(
                storedLink.revokedAt(),
                storedLink.notBefore(),
                storedLink.expiresAt(),
                databaseTime()
        );
        return new ResolvedLinkResult(
                storedLink.id(),
                targetUrlPort.resolve(
                        trustedTarget.targetSystem(),
                        trustedTarget.targetPath()
                )
        );
    }

    @Override
    public LinkResult revokeLink(UUID linkId) {
        StoredLinkSnapshot storedLink = repository.findStoredByIdForUpdate(linkId)
                .orElseThrow(LinkNotFoundException::new);
        TrustedTarget trustedTarget = requireManagedTrustedTarget(storedLink);
        if (storedLink.revokedAt() != null) {
            return toResult(storedLink, trustedTarget, storedLink.revokedAt());
        }

        Instant revokedAt = databaseTime();
        if (revokedAt.isBefore(storedLink.createdAt())) {
            throw new IllegalStateException("폐기 시각은 생성 시각보다 빠를 수 없습니다");
        }
        if (!repository.revokeStoredIfVersion(
                storedLink.id(),
                storedLink.version(),
                revokedAt
        )) {
            throw new IllegalStateException("링크 폐기 상태를 저장할 수 없습니다");
        }
        return toResult(storedLink, trustedTarget, revokedAt);
    }

    private StoredLinkReplay findReplayLink(UUID linkId) {
        return repository.findReplayById(linkId).orElseThrow(LinkNotFoundException::new);
    }

    private TrustedTarget requireSameCreationRequest(
            StoredLinkReplay existing,
            CreateLinkCommand command,
            String targetPath,
            Instant notBefore,
            Instant expiresAt
    ) {
        TrustedTarget trustedTarget;
        try {
            trustedTarget = TrustedTargetPolicy.requireAllowed(
                    existing.targetSystem(),
                    existing.purpose(),
                    existing.targetPath()
            );
        } catch (LinkValidationException exception) {
            throw new IdempotencyKeyConflictException();
        }
        boolean sameRequest = trustedTarget.targetSystem() == command.targetSystem()
                && trustedTarget.targetPath().equals(targetPath)
                && trustedTarget.purpose() == command.purpose()
                && Objects.equals(existing.notBefore(), notBefore)
                && Objects.equals(existing.expiresAt(), expiresAt);
        if (!sameRequest) {
            throw new IdempotencyKeyConflictException();
        }
        return trustedTarget;
    }

    private void requireReplayableCode(
            StoredLinkReplay existing,
            IssuedLinkCode issuedCode
    ) {
        if (!existing.codeHash().equals(issuedCode.codeHash())) {
            throw new LinkCodeReplayMismatchException();
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

    private LinkResult toResult(
            StoredLinkReplay storedLink,
            TrustedTarget trustedTarget
    ) {
        return new LinkResult(
                storedLink.id(),
                trustedTarget.targetSystem(),
                trustedTarget.targetPath(),
                trustedTarget.purpose(),
                storedLink.notBefore(),
                storedLink.expiresAt(),
                storedLink.revokedAt(),
                storedLink.createdAt()
        );
    }

    private TrustedTarget requireManagedTrustedTarget(StoredLinkSnapshot storedLink) {
        try {
            return TrustedTargetPolicy.requireAllowed(
                    storedLink.targetSystem(),
                    storedLink.purpose(),
                    storedLink.targetPath()
            );
        } catch (LinkValidationException exception) {
            throw new LinkNotFoundException();
        }
    }

    private LinkResult toResult(
            StoredLinkSnapshot storedLink,
            TrustedTarget trustedTarget,
            Instant revokedAt
    ) {
        return new LinkResult(
                storedLink.id(),
                trustedTarget.targetSystem(),
                trustedTarget.targetPath(),
                trustedTarget.purpose(),
                storedLink.notBefore(),
                storedLink.expiresAt(),
                revokedAt,
                storedLink.createdAt()
        );
    }
}
