package com.personal.batongo.application.link;

import com.personal.batongo.application.link.error.IdempotencyKeyConflictException;
import com.personal.batongo.application.link.error.LinkCodeReplayMismatchException;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.error.PublicLinkOriginReplayUnavailableException;
import com.personal.batongo.application.link.error.StoredTargetPolicyViolationException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.out.IssuedLinkCode;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import com.personal.batongo.application.link.port.out.LinkCreationReservationPort;
import com.personal.batongo.application.link.port.out.PublicLinkOriginPort;
import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkReplay;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkResolution;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkSnapshot;
import com.personal.batongo.application.link.port.out.TargetUrlPort;
import com.personal.batongo.domain.link.LinkAvailabilityPolicy;
import com.personal.batongo.domain.link.LinkRevocationPolicy;
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
    private final PublicLinkOriginPort publicLinkOriginPort;
    private final TargetUrlPort targetUrlPort;
    private final Clock clock;

    public SmartLinkService(
            SmartLinkRepository repository,
            LinkCreationReservationPort reservationPort,
            LinkCodePort linkCodePort,
            LinkCodeKeyGuard linkCodeKeyGuard,
            PublicLinkOriginPort publicLinkOriginPort,
            TargetUrlPort targetUrlPort,
            Clock clock
    ) {
        this.repository = repository;
        this.reservationPort = reservationPort;
        this.linkCodePort = linkCodePort;
        this.linkCodeKeyGuard = linkCodeKeyGuard;
        this.publicLinkOriginPort = publicLinkOriginPort;
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
        LinkCreationReservationPort.Reservation reservation = reservationPort.find(
                prepared.idempotencyKeyHash()
        ).orElseThrow(prepared.admission()::missingReservationException);
        TrustedTarget requestedTarget = requireAllowedTarget(prepared.command());
        linkCodeKeyGuard.verifyBound();
        return replayCreation(
                reservation,
                requestedTarget,
                prepared.admission().notBefore(),
                prepared.admission().expiresAt(),
                linkCodePort.issue(prepared.idempotencyKey())
        );
    }

    private CreatedLinkResult reserveCreateOrReplay(PreparedCreation prepared) {
        TrustedTarget requestedTarget = requireAllowedTarget(prepared.command());
        linkCodeKeyGuard.verifyBound();
        PublicLinkOrigin currentOrigin = publicLinkOriginPort.current();
        Instant now = databaseTime();
        LinkCreationReservationPort.Reservation reservation = reservationPort.reserve(
                prepared.idempotencyKeyHash(),
                UUID.randomUUID(),
                currentOrigin.serialized(),
                now
        );
        IssuedLinkCode issuedCode = linkCodePort.issue(prepared.idempotencyKey());

        if (!reservation.owner()) {
            return replayCreation(
                    reservation,
                    requestedTarget,
                    prepared.admission().notBefore(),
                    prepared.admission().expiresAt(),
                    issuedCode
            );
        }

        SmartLink smartLink = SmartLink.create(
                reservation.linkId(),
                issuedCode.codeHash(),
                requestedTarget,
                prepared.admission().notBefore(),
                prepared.admission().expiresAt(),
                now
        );
        SmartLink saved = repository.save(smartLink);
        return new CreatedLinkResult(
                toResult(saved),
                currentOrigin.shortUrl(issuedCode.rawCode()),
                false
        );
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

    private TrustedTarget requireAllowedTarget(CreateLinkCommand command) {
        return TrustedTargetPolicy.requireAllowed(
                command.targetSystem(),
                command.purpose(),
                command.targetPath()
        );
    }

    private CreatedLinkResult replayCreation(
            LinkCreationReservationPort.Reservation reservation,
            TrustedTarget requestedTarget,
            Instant notBefore,
            Instant expiresAt,
            IssuedLinkCode issuedCode
    ) {
        StoredLinkReplay existing = findReplayLink(reservation.linkId());
        TrustedTarget trustedTarget = requireSameCreationRequest(
                existing,
                requestedTarget,
                notBefore,
                expiresAt
        );
        requireReplayableCode(existing, issuedCode);
        PublicLinkOrigin storedOrigin = requireReplayOrigin(reservation.publicOrigin());
        return new CreatedLinkResult(
                toResult(existing, trustedTarget),
                storedOrigin.shortUrl(issuedCode.rawCode()),
                true
        );
    }

    private PublicLinkOrigin requireReplayOrigin(String storedOrigin) {
        try {
            return PublicLinkOrigin.fromStored(storedOrigin);
        } catch (RuntimeException exception) {
            throw new PublicLinkOriginReplayUnavailableException();
        }
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
        Instant revokedAt = LinkRevocationPolicy.requireFirstRevocationAt(
                storedLink.createdAt(),
                databaseTime()
        );
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
            TrustedTarget requestedTarget,
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
        boolean sameRequest = trustedTarget.targetSystem() == requestedTarget.targetSystem()
                && trustedTarget.targetPath().equals(requestedTarget.targetPath())
                && trustedTarget.purpose() == requestedTarget.purpose()
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
