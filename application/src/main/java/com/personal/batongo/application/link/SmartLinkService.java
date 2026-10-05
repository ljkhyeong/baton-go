package com.personal.batongo.application.link;

import com.personal.batongo.application.link.error.IdempotencyKeyConflictException;
import com.personal.batongo.application.link.error.InvalidRequestException;
import com.personal.batongo.application.link.error.LinkCodeReplayMismatchException;
import com.personal.batongo.application.link.error.LinkCreationReplayUnavailableException;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.error.LinkPurgedException;
import com.personal.batongo.application.link.error.PublicLinkOriginReplayUnavailableException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.out.IssuedLinkCode;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import com.personal.batongo.application.link.port.out.LinkCreationReservationPort;
import com.personal.batongo.application.link.port.out.PublicLinkOriginPort;
import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkReplay;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkSnapshot;
import com.personal.batongo.domain.link.LinkRevocationPolicy;
import com.personal.batongo.domain.link.SmartLink;
import com.personal.batongo.domain.link.TrustedTarget;
import com.personal.batongo.domain.link.TrustedTargetPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    private final Clock clock;

    public SmartLinkService(
            SmartLinkRepository repository,
            LinkCreationReservationPort reservationPort,
            LinkCodePort linkCodePort,
            LinkCodeKeyGuard linkCodeKeyGuard,
            PublicLinkOriginPort publicLinkOriginPort,
            Clock clock
    ) {
        this.repository = repository;
        this.reservationPort = reservationPort;
        this.linkCodePort = linkCodePort;
        this.linkCodeKeyGuard = linkCodeKeyGuard;
        this.publicLinkOriginPort = publicLinkOriginPort;
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
        String idempotencyKeyHash = linkCodePort.hashIdempotencyKey(command.idempotencyKey().value());
        if (!admission.allowsNewReservation()) {
            return replayExistingOnly(command, admission, idempotencyKeyHash);
        }
        return reserveCreateOrReplay(command, admission, idempotencyKeyHash);
    }

    private CreatedLinkResult replayExistingOnly(
            CreateLinkCommand command,
            CreationRequestAdmissionPolicy.Decision admission,
            String idempotencyKeyHash
    ) {
        LinkCreationReservationPort.Reservation reservation = reservationPort.find(
                idempotencyKeyHash
        ).orElseThrow(admission::missingReservationException);
        TrustedTarget requestedTarget = requireAllowedTarget(command);
        requireNotPurged(reservation, requestedTarget, admission);
        linkCodeKeyGuard.verifyBound();
        return replayCreation(
                reservation,
                requestedTarget,
                admission.notBefore(),
                admission.expiresAt(),
                linkCodePort.issue(command.idempotencyKey().value(), reservation.keyId())
        );
    }

    private CreatedLinkResult reserveCreateOrReplay(
            CreateLinkCommand command,
            CreationRequestAdmissionPolicy.Decision admission,
            String idempotencyKeyHash
    ) {
        TrustedTarget requestedTarget = requireAllowedTarget(command);
        linkCodeKeyGuard.verifyBound();
        PublicLinkOrigin currentOrigin = publicLinkOriginPort.current();
        Instant now = clock.instant();
        LinkCreationReservationPort.Reservation reservation = reservationPort.reserve(
                idempotencyKeyHash,
                UUID.randomUUID(),
                currentOrigin.serialized(),
                linkCodePort.keyRingIdentity().activeKeyId(),
                now
        );
        requireNotPurged(reservation, requestedTarget, admission);
        IssuedLinkCode issuedCode = linkCodePort.issue(
                command.idempotencyKey().value(), reservation.keyId()
        );

        if (!reservation.owner()) {
            return replayCreation(
                    reservation,
                    requestedTarget,
                    admission.notBefore(),
                    admission.expiresAt(),
                    issuedCode
            );
        }

        repository.save(new SmartLink(
                reservation.linkId(),
                issuedCode.codeHash(),
                requestedTarget,
                admission.notBefore(),
                admission.expiresAt(),
                now
        ));
        return new CreatedLinkResult(
                new LinkResult(
                        reservation.linkId(),
                        requestedTarget.targetSystem(),
                        requestedTarget.targetPath(),
                        requestedTarget.purpose(),
                        admission.notBefore(),
                        admission.expiresAt(),
                        null,
                        now,
                        now
                ),
                currentOrigin.shortUrl(issuedCode.rawCode()),
                false
        );
    }

    private void requireNotPurged(LinkCreationReservationPort.Reservation reservation,
                                  TrustedTarget target, CreationRequestAdmissionPolicy.Decision admission) {
        if (reservation.purgedAt() == null) {
            return;
        }
        String requestHash = LinkCreationFingerprint.of(target.targetSystem().name(),
                target.purpose().name(), target.targetPath(),
                admission.notBefore(), admission.expiresAt());
        if (!requestHash.equals(reservation.requestHash())) {
            throw new IdempotencyKeyConflictException();
        }
        throw new LinkPurgedException();
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
        requireSameCreationRequest(
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
                toResult(existing, requestedTarget),
                storedOrigin.shortUrl(issuedCode.rawCode()),
                true
        );
    }

    private PublicLinkOrigin requireReplayOrigin(String storedOrigin) {
        if (storedOrigin == null) {
            throw new PublicLinkOriginReplayUnavailableException();
        }
        try {
            return PublicLinkOrigin.fromStored(storedOrigin);
        } catch (IllegalArgumentException exception) {
            throw new PublicLinkOriginReplayUnavailableException();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public LinkResult getLink(UUID linkId) {
        StoredLinkSnapshot storedLink = repository.findStoredById(linkId)
                .orElseThrow(LinkNotFoundException::new);
        TrustedTarget target = requireManagedTrustedTarget(storedLink);
        return toResult(storedLink, target, storedLink.revokedAt(), clock.instant());
    }

    @Override
    @Transactional(readOnly = true)
    public LinkBatchResult getLinks(List<UUID> linkIds) {
        if (linkIds == null || linkIds.isEmpty() || linkIds.size() > 100
                || linkIds.stream().anyMatch(Objects::isNull)) {
            throw InvalidRequestException.linkBatch();
        }
        List<UUID> uniqueIds = linkIds.stream().distinct().toList();
        List<StoredLinkSnapshot> stored = repository.findStoredByIds(uniqueIds);
        Instant evaluatedAt = clock.instant();
        Map<UUID, LinkResult> found = stored.stream()
                .flatMap(link -> link.trustedTarget()
                        .map(target -> toResult(link, target, link.revokedAt(), evaluatedAt)).stream())
                .collect(Collectors.toMap(LinkResult::id, Function.identity()));
        return new LinkBatchResult(
                uniqueIds.stream().map(found::get).filter(Objects::nonNull).toList(),
                uniqueIds.stream().filter(id -> !found.containsKey(id)).toList(),
                evaluatedAt
        );
    }

    @Override
    @Transactional(readOnly = true)
    public LinkSearchResult searchLinks(LinkSearchQuery query) {
        if (!StoredLinkScan.isValidLimit(query.limit())
                || (query.createdFrom() != null && query.createdBefore() != null
                && !query.createdBefore().isAfter(query.createdFrom()))
                || (query.expiresFrom() != null && query.expiresBefore() != null
                && !query.expiresBefore().isAfter(query.expiresFrom()))) {
            throw InvalidRequestException.linkSearch();
        }
        StoredLinkScan scan = StoredLinkScan.read(repository, query.afterLinkId(), query.limit());
        Instant evaluatedAt = clock.instant();
        List<LinkResult> items = scan.rows().stream()
                .filter(stored -> query.targetSystem() == null
                        || query.targetSystem().name().equals(stored.targetSystem()))
                .filter(stored -> query.createdFrom() == null
                        || !stored.createdAt().isBefore(query.createdFrom()))
                .filter(stored -> query.createdBefore() == null
                        || stored.createdAt().isBefore(query.createdBefore()))
                .filter(stored -> query.expiresFrom() == null
                        || (stored.expiresAt() != null && !stored.expiresAt().isBefore(query.expiresFrom())))
                .filter(stored -> query.expiresBefore() == null
                        || (stored.expiresAt() != null && stored.expiresAt().isBefore(query.expiresBefore())))
                .flatMap(stored -> stored.trustedTarget()
                        .map(target -> toResult(stored, target, stored.revokedAt(), evaluatedAt)).stream())
                .filter(link -> query.status() == null || link.status() == query.status())
                .toList();
        return new LinkSearchResult(items, scan.nextAfterLinkId(), scan.hasMore(), evaluatedAt);
    }

    @Override
    public RevokedLinkResult revokeLink(UUID linkId) {
        StoredLinkSnapshot storedLink = repository.findStoredByIdForUpdate(linkId)
                .orElseThrow(LinkNotFoundException::new);
        TrustedTarget target = requireManagedTrustedTarget(storedLink);
        if (storedLink.revokedAt() != null) {
            return new RevokedLinkResult(
                    toResult(storedLink, target, storedLink.revokedAt(), clock.instant()),
                    true
            );
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
        return new RevokedLinkResult(
                toResult(storedLink, target, revokedAt, clock.instant()),
                false
        );
    }

    private void requireSameCreationRequest(
            StoredLinkReplay existing,
            TrustedTarget requestedTarget,
            Instant notBefore,
            Instant expiresAt
    ) {
        boolean sameRequest = requestedTarget.targetSystem().name().equals(existing.targetSystem())
                && requestedTarget.targetPath().equals(existing.targetPath())
                && requestedTarget.purpose().name().equals(existing.purpose())
                && Objects.equals(existing.notBefore(), notBefore)
                && Objects.equals(existing.expiresAt(), expiresAt);
        if (!sameRequest) {
            throw new IdempotencyKeyConflictException();
        }
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
        return storedLink.trustedTarget().orElseThrow(LinkNotFoundException::new);
    }

    private LinkResult toResult(
            StoredLinkSnapshot storedLink,
            TrustedTarget target,
            Instant revokedAt,
            Instant evaluatedAt
    ) {
        return new LinkResult(
                storedLink.id(),
                target.targetSystem(),
                target.targetPath(),
                target.purpose(),
                storedLink.notBefore(),
                storedLink.expiresAt(),
                revokedAt,
                storedLink.createdAt(),
                evaluatedAt
        );
    }
}
