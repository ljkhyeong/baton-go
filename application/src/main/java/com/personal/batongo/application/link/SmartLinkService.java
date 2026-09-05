package com.personal.batongo.application.link;

import com.personal.batongo.application.link.error.IdempotencyKeyConflictException;
import com.personal.batongo.application.link.error.InvalidRequestException;
import com.personal.batongo.application.link.error.LinkCodeReplayMismatchException;
import com.personal.batongo.application.link.error.LinkCreationReplayUnavailableException;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.error.LinkPurgedException;
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
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.LinkRevocationPolicy;
import com.personal.batongo.domain.link.LinkValidationException;
import com.personal.batongo.domain.link.SmartLink;
import com.personal.batongo.domain.link.TargetSystem;
import com.personal.batongo.domain.link.TrustedTarget;
import com.personal.batongo.domain.link.TrustedTargetPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
        CreationRequestAdmissionPolicy.Decision admission =
                CreationRequestAdmissionPolicy.evaluate(
                        command.idempotencyKey(),
                        command.notBefore(),
                        command.expiresAt()
                );
        PreparedCreation prepared = new PreparedCreation(
                command,
                admission,
                linkCodePort.hashIdempotencyKey(command.idempotencyKey().value())
        );
        if (!prepared.admission().allowsNewReservation()) {
            return replayExistingOnly(prepared);
        }
        return reserveCreateOrReplay(prepared);
    }

    private CreatedLinkResult replayExistingOnly(PreparedCreation prepared) {
        LinkCreationReservationPort.Reservation reservation = reservationPort.find(
                prepared.idempotencyKeyHash()
        ).orElseThrow(prepared.admission()::missingReservationException);
        TrustedTarget requestedTarget = requireAllowedTarget(prepared.command());
        requireNotPurged(reservation, requestedTarget, prepared);
        linkCodeKeyGuard.verifyBound();
        return replayCreation(
                reservation,
                requestedTarget,
                prepared.admission().notBefore(),
                prepared.admission().expiresAt(),
                linkCodePort.issue(prepared.command().idempotencyKey().value(), reservation.keyId())
        );
    }

    private CreatedLinkResult reserveCreateOrReplay(PreparedCreation prepared) {
        TrustedTarget requestedTarget = requireAllowedTarget(prepared.command());
        linkCodeKeyGuard.verifyBound();
        PublicLinkOrigin currentOrigin = publicLinkOriginPort.current();
        Instant now = clock.instant();
        LinkCreationReservationPort.Reservation reservation = reservationPort.reserve(
                prepared.idempotencyKeyHash(),
                UUID.randomUUID(),
                currentOrigin.serialized(),
                linkCodePort.keyRingIdentity().activeKeyId(),
                now
        );
        requireNotPurged(reservation, requestedTarget, prepared);
        IssuedLinkCode issuedCode = linkCodePort.issue(
                prepared.command().idempotencyKey().value(), reservation.keyId()
        );

        if (!reservation.owner()) {
            return replayCreation(
                    reservation,
                    requestedTarget,
                    prepared.admission().notBefore(),
                    prepared.admission().expiresAt(),
                    issuedCode
            );
        }

        repository.save(new SmartLink(
                reservation.linkId(),
                issuedCode.codeHash(),
                requestedTarget,
                prepared.admission().notBefore(),
                prepared.admission().expiresAt(),
                now
        ));
        return new CreatedLinkResult(
                new LinkResult(
                        reservation.linkId(),
                        requestedTarget.targetSystem(),
                        requestedTarget.targetPath(),
                        requestedTarget.purpose(),
                        prepared.admission().notBefore(),
                        prepared.admission().expiresAt(),
                        null,
                        now,
                        now
                ),
                currentOrigin.shortUrl(issuedCode.rawCode()),
                false
        );
    }

    private void requireNotPurged(LinkCreationReservationPort.Reservation reservation,
                                  TrustedTarget target, PreparedCreation prepared) {
        if (reservation.purgedAt() == null) {
            return;
        }
        String requestHash = LinkCreationFingerprint.of(target.targetSystem().name(),
                target.purpose().name(), target.targetPath(),
                prepared.admission().notBefore(), prepared.admission().expiresAt());
        if (!requestHash.equals(reservation.requestHash())) {
            throw new IdempotencyKeyConflictException();
        }
        throw new LinkPurgedException();
    }

    private record PreparedCreation(
            CreateLinkCommand command,
            CreationRequestAdmissionPolicy.Decision admission,
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
        StoredLinkReplay existing = repository.findReplayById(reservation.linkId())
                .orElseThrow(() -> new LinkCreationReplayUnavailableException(
                        reservation.linkId()
                ));
        TrustedTarget trustedTarget = requireSameCreationRequest(
                existing,
                requestedTarget,
                notBefore,
                expiresAt
        );
        if (!existing.codeHash().equals(issuedCode.codeHash())) {
            throw new LinkCodeReplayMismatchException();
        }
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
    public LinkSearchResult searchLinks(LinkSearchQuery query) {
        if (query.limit() < 1 || query.limit() > 500
                || (query.createdFrom() != null && query.createdBefore() != null
                && !query.createdBefore().isAfter(query.createdFrom()))) {
            throw InvalidRequestException.linkSearch();
        }
        List<StoredLinkSnapshot> scanned = repository.scanStoredAfter(
                query.afterLinkId(), query.limit() + 1
        );
        Instant evaluatedAt = clock.instant();
        boolean hasMore = scanned.size() > query.limit();
        List<LinkResult> items = scanned.stream()
                .limit(query.limit())
                .filter(stored -> query.targetSystem() == null
                        || query.targetSystem().name().equals(stored.targetSystem()))
                .filter(stored -> query.createdFrom() == null
                        || !stored.createdAt().isBefore(query.createdFrom()))
                .filter(stored -> query.createdBefore() == null
                        || stored.createdAt().isBefore(query.createdBefore()))
                .filter(stored -> TrustedTargetPolicy.isAllowed(
                        stored.targetSystem(), stored.purpose(), stored.targetPath()
                ))
                .map(stored -> new LinkResult(
                        stored.id(),
                        TargetSystem.valueOf(stored.targetSystem()),
                        stored.targetPath(),
                        LinkPurpose.valueOf(stored.purpose()),
                        stored.notBefore(),
                        stored.expiresAt(),
                        stored.revokedAt(),
                        stored.createdAt(),
                        evaluatedAt
                ))
                .filter(link -> query.status() == null || link.status() == query.status())
                .toList();
        // 필터 결과가 비어도 검사한 마지막 행 다음으로 진행한다.
        UUID nextAfterLinkId = hasMore ? scanned.get(query.limit() - 1).id() : null;
        return new LinkSearchResult(items, nextAfterLinkId, hasMore, evaluatedAt);
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
                clock.instant()
        );
        return new ResolvedLinkResult(targetUrlPort.resolve(trustedTarget));
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
                clock.instant()
        );
        repository.revokeStored(
                storedLink.id(),
                storedLink.version(),
                revokedAt
        );
        return toResult(storedLink, trustedTarget, revokedAt);
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
                storedLink.createdAt(),
                clock.instant()
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
                storedLink.createdAt(),
                clock.instant()
        );
    }
}
